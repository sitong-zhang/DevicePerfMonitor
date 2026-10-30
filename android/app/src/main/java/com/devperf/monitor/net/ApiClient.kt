package com.devperf.monitor.net

import android.util.Base64
import android.util.Log
import com.devperf.monitor.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 后端 API 客户端（账号 / 验证码 / 数据同步 / 管理端唯一联网出口）。
 *
 * 与 AnnouncementRepository / UpdateRepository 同一风格：内置
 * HttpURLConnection + org.json，不引入额外联网依赖。所有请求都发往
 * [BuildConfig.SERVER_URL] 指向的自建后端（backend/ 目录，Flask + SQLite）。
 */
object ApiClient {

    private const val TAG = "ApiClient"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    /** 请求结果：ok=true 时 data 为响应 JSON，否则 msg 为可展示给用户的错误。 */
    data class Result(val ok: Boolean, val code: Int, val data: JSONObject?, val msg: String?)

    /**
     * 官方后端地址：启动时由 [resolveServerUrl] 从仓库官方文件自动发现并写入，
     * 无需用户手动填写；为空时回落到 [BuildConfig.SERVER_URL]（编译期注入，
     * 默认 10.0.2.2 仅供模拟器联调）。
     */
    var officialUrl: String? = null

    fun baseUrl(): String =
        officialUrl?.takeIf { it.isNotBlank() }?.trimEnd('/') ?: BuildConfig.SERVER_URL

    /**
     * 从官方文件（仓库 backend-host 分支根目录 backend-host.txt）自动发现当前后端地址。
     * 该文件由后端托管工作流持续更新，开发者集中维护、用户无需任何配置。
     * 发现成功则写入 [officialUrl] 并返回；失败返回 null（调用方回落到默认地址）。
     */
    suspend fun resolveServerUrl(): String? = withContext(Dispatchers.IO) {
        val raw = fetchRaw()
            ?: fetchContentsApi()
        if (!raw.isNullOrBlank()) {
            officialUrl = raw
            raw
        } else {
            null
        }
    }

    /** 主通道：raw CDN 取纯文本，无限流、响应快。 */
    private fun fetchRaw(): String? {
        val url =
            "https://raw.githubusercontent.com/sitong-zhang/DevicePerfMonitor/backend-host/backend-host.txt"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
            }
            if (conn.responseCode != 200) return null
            conn.inputStream.bufferedReader().use { it.readText() }.trim()
                .takeIf { it.isNotBlank() && it.startsWith("http") }
        } catch (e: Exception) {
            Log.w(TAG, "raw 通道取后端地址失败", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** 兜底通道：GitHub Contents API（base64），raw 不可达时使用。 */
    private fun fetchContentsApi(): String? {
        val api =
            "https://api.github.com/repos/sitong-zhang/DevicePerfMonitor/contents/backend-host.txt?ref=backend-host"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(api).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "DevicePerfMonitor")
                connectTimeout = 8000
                readTimeout = 8000
            }
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            String(
                Base64.decode(json.optString("content", "").replace("\n", ""), Base64.DEFAULT),
                Charsets.UTF_8,
            ).trim().takeIf { it.isNotBlank() && it.startsWith("http") }
        } catch (e: Exception) {
            Log.w(TAG, "API 通道取后端地址失败", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** GET 请求。extraHeaders 可带 Authorization 等。 */
    fun get(path: String, extraHeaders: Map<String, String> = emptyMap()): Result =
        request("GET", path, null, extraHeaders)

    /** POST 请求，body 为 JSON（可为 null 表示无 body）。 */
    fun post(path: String, body: JSONObject?, extraHeaders: Map<String, String> = emptyMap()): Result =
        request("POST", path, body, extraHeaders)

    private fun request(
        method: String,
        path: String,
        body: JSONObject?,
        extraHeaders: Map<String, String>,
    ): Result {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(baseUrl().trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = method
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                for ((k, v) in extraHeaders) setRequestProperty(k, v)
            }
            body?.let { conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) } }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code in 200..299) {
                val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
                Result(ok = json.optBoolean("ok", true), code = code, data = json, msg = json.optString("msg").ifEmpty { null })
            } else {
                val json = runCatching { JSONObject(text) }.getOrNull()
                Result(false, code, null, json?.optString("msg")?.ifEmpty { null } ?: "网络错误（$code）")
            }
        } catch (e: Exception) {
            Log.w(TAG, "请求失败 $method $path", e)
            Result(false, 0, null, "无法连接服务器，请检查网络")
        } finally {
            conn?.disconnect()
        }
    }
}
