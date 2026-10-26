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

object GitHubClient {

    private const val OWNER = "sitong-zhang"
    private const val REPO = "DevicePerfMonitor"
    private const val RAW_ANNOUNCEMENT =
        "https://raw.githubusercontent.com/$OWNER/$REPO/main/announcement.json"
    private const val CONTENTS_API =
        "https://api.github.com/repos/$OWNER/$REPO/contents/announcement.json"

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
        val (code, body) = get(RAW_ANNOUNCEMENT)
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
        val (getCode, getBody) = get(CONTENTS_API, token)
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
