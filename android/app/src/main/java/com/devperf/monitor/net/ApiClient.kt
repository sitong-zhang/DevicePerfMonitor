package com.devperf.monitor.net

import android.util.Log
import com.devperf.monitor.BuildConfig
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
    data class Result(val ok: Boolean, val data: JSONObject?, val msg: String?)

    fun baseUrl(): String = BuildConfig.SERVER_URL

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
            conn = (URL(BuildConfig.SERVER_URL.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
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
                Result(ok = json.optBoolean("ok", true), data = json, msg = json.optString("msg").ifEmpty { null })
            } else {
                val json = runCatching { JSONObject(text) }.getOrNull()
                Result(false, null, json?.optString("msg")?.ifEmpty { null } ?: "网络错误（$code）")
            }
        } catch (e: Exception) {
            Log.w(TAG, "请求失败 $method $path", e)
            Result(false, null, "无法连接服务器，请检查网络")
        } finally {
            conn?.disconnect()
        }
    }
}
