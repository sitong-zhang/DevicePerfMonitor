package com.molecule.manager.network

import android.content.Context
import android.util.Base64
import com.molecule.security.TokenVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * GitHubClient —— 管理端与 GitHub 交互。
 *
 * 1.0.1 起只保留公告管理（用户拍板撤掉反馈收件箱/回复功能，反馈改由用户端
 * 直接进邮箱）：
 *   - 读（免 token，公开仓库）：公告 JSON（raw）；
 *   - 写（需 token，唯一写路径）：发布公告（GET sha + PUT contents API）。
 *
 * token 来自 [TokenVault]，受环境/完整性约束，明文不长期驻留。
 */

/** 线上公告数据结构。 */
data class AnnouncementData(
    val id: String,
    val title: String,
    val content: String,
    val updatedAt: String,
)

/** 全能签 签名回执（全能签 签名完成后上报的 issue，labels=brand-sign）。 */
data class SignReportData(
    val number: Long,
    val title: String,
    val body: String,
    val createdAt: String,
)

/**
 * 数据上传包（App 端打包整个应用数据后上报的 issue，labels=data-upload）。
 * 来源应用不固定（DataKit 可嵌入任意软件），通道固定到管理端。
 */
data class DataUploadData(
    val number: Long,
    val title: String,
    val app: String,
    val bundleID: String,
    val exportedAt: String,
    val fileCount: Int,
    val skippedCount: Int,
    val rawChars: Int,
    val isZip: Boolean = false,
)

object GitHubClient {

    private const val OWNER = "sitong-zhang"
    private const val REPO = "DevicePerfMonitor"
    private const val RAW_ANNOUNCEMENT =
        "https://raw.githubusercontent.com/$OWNER/$REPO/main/announcement.json"
    private const val CONTENTS_API =
        "https://api.github.com/repos/$OWNER/$REPO/contents/announcement.json"
    private const val SIGN_REPORTS_API =
        "https://api.github.com/repos/$OWNER/$REPO/issues?labels=brand-sign&state=open&per_page=20&sort=created&direction=desc"
    private const val DATA_UPLOADS_API =
        "https://api.github.com/repos/$OWNER/$REPO/issues?labels=data-upload&state=open&per_page=20&sort=created&direction=desc"

    /**
     * 带重试的 GET：手机网络到 GitHub API 偶发 Connection reset / 5xx，
     * 自动重试（共 3 次，指数退避）；HTTP 4xx 属真实响应不重试。
     */
    private fun getWithRetry(url: String, token: String? = null, attempts: Int = 3): Pair<Int, String> {
        var lastEx: java.io.IOException? = null
        repeat(attempts) { i ->
            try {
                val (code, body) = get(url, token)
                if (code < 500) return code to body
                lastEx = java.io.IOException("HTTP $code")
            } catch (e: java.io.IOException) {
                lastEx = e
            }
            if (i < attempts - 1) {
                try { Thread.sleep(800L * (i + 1)) } catch (_: InterruptedException) {}
            }
        }
        throw lastEx ?: java.io.IOException("网络请求失败")
    }

    private fun get(url: String, token: String? = null): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.setRequestProperty("User-Agent", "MoleculeManager")
        if (!token.isNullOrBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $token")
        }
        val code = conn.responseCode
        val body = if (code in 200..299) {
            conn.inputStream.bufferedReader().readText()
        } else {
            conn.errorStream?.bufferedReader()?.readText() ?: ""
        }
        conn.disconnect()
        return code to body
    }

    private fun put(url: String, token: String, jsonBody: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "PUT"
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.doOutput = true
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.setRequestProperty("User-Agent", "MoleculeManager")
        conn.outputStream.write(jsonBody.toByteArray(StandardCharsets.UTF_8))
        val code = conn.responseCode
        val body = if (code in 200..299) {
            conn.inputStream.bufferedReader().readText()
        } else {
            conn.errorStream?.bufferedReader()?.readText() ?: ""
        }
        conn.disconnect()
        return code to body
    }

    /** 读取线上公告（免 token）。 */
    suspend fun fetchAnnouncement(): AnnouncementData = withContext(Dispatchers.IO) {
        val (code, body) = getWithRetry(RAW_ANNOUNCEMENT)
        require(code in 200..299) { "读取公告失败：HTTP $code" }
        val jo = JSONObject(body)
        AnnouncementData(
            id = jo.optString("id", ""),
            title = jo.optString("title", ""),
            content = jo.optString("content", ""),
            updatedAt = jo.optString("updatedAt", ""),
        )
    }

    /**
     * 读取 全能签 签名回执（免 token，公开仓库的 issues 只读）。
     * 每条回执是 Brand 签名完成后自动上报的 issue（labels=brand-sign）。
     */
    suspend fun fetchSignReports(): List<SignReportData> = withContext(Dispatchers.IO) {
        val (code, body) = getWithRetry(SIGN_REPORTS_API)
        require(code in 200..299) { "读取签名记录失败：HTTP $code" }
        val arr = org.json.JSONArray(body)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SignReportData(
                number = o.optLong("number", 0),
                title = o.optString("title", ""),
                body = o.optString("body", ""),
                createdAt = o.optString("created_at", ""),
            )
        }
    }

    /**
     * 读取数据上传包（免 token，labels=data-upload）。
     * 新版 body 为 `[ZIP_B64]…[/ZIP_B64]` 段 + 末尾 `json` 摘要；
     * 兼容老版纯 JSON 包。从标题取来源应用与时间，从正文取包体大小。
     */
    suspend fun fetchDataUploads(): List<DataUploadData> = withContext(Dispatchers.IO) {
        val (code, body) = getWithRetry(DATA_UPLOADS_API)
        require(code in 200..299) { "读取数据上传失败：HTTP $code" }
        val arr = org.json.JSONArray(body)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val title = o.optString("title", "")
            val app = title.removePrefix("[数据上传] ")
                .substringBefore(" · ").trim().ifBlank { "?" }
            val exportedAt = title.substringAfter(" · ").substringBefore(" · ").trim()
            val raw = o.optString("body", "")
            val isZip = raw.contains("[ZIP_B64]")
            val zipChars = if (isZip) {
                raw.substringAfter("[ZIP_B64]")
                    .substringBefore("[/ZIP_B64]")
                    .trim()
                    .length
            } else 0
            DataUploadData(
                number = o.optLong("number", 0),
                title = title,
                app = app,
                bundleID = "",
                exportedAt = exportedAt,
                fileCount = 0,
                skippedCount = 0,
                rawChars = zipChars,
                isZip = isZip,
            )
        }
    }

    /**
     * 发布公告（需 token，唯一写路径）。
     * 先 GET 拿当前 sha，再 PUT 新内容。返回 HTTP 状态码与消息。
     */
    suspend fun publishAnnouncement(
        context: Context,
        title: String,
        content: String,
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val token = TokenVault.getToken(context)
            ?: return@withContext 401 to "未授权：安全模块未提供 token（环境不纯 / 未注入）。"

        // 1) 取当前文件的 sha（更新需要）
        val (getCode, getBody) = getWithRetry(CONTENTS_API, token)
        if (getCode !in 200..299) {
            return@withContext getCode to "获取当前公告失败：HTTP $getCode $getBody"
        }
        val sha = JSONObject(getBody).optString("sha", "")

        // 2) 组装新公告 JSON 并 base64
        val newJson = JSONObject().apply {
            put("id", System.currentTimeMillis().toString())
            put("title", title)
            put("content", content)
            put("updatedAt", java.time.Instant.now().toString())
        }.toString()
        val contentB64 = Base64.encodeToString(
            newJson.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP,
        )

        // 3) PUT
        val putBody = JSONObject().apply {
            put("message", "chore: 更新公告 via 管理端")
            put("content", contentB64)
            put("sha", sha)
        }.toString()
        val (putCode, putResp) = put(CONTENTS_API, token, putBody)
        putCode to if (putCode in 200..299) "发布成功" else "发布失败：HTTP $putCode $putResp"
    }
}
