package com.molecule.manager.network

import android.content.Context
import android.util.Base64
import com.molecule.security.TokenVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * GitHubClient —— 管理端与 GitHub 交互。
 *
 * 两条路径：
 *   - 读（免 token，公开仓库）：公告 JSON（raw）、反馈 issues 列表；
 *   - 写（需 token，唯一写路径）：发布公告（PUT contents API）。
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

/** 反馈 issue（GitHub issues API，label=feedback）。 */
data class FeedbackIssue(
    val number: Int,
    val title: String,
    val state: String,
    val createdAt: String,
    val body: String,
    val user: String,
)

/** issue 下的单条评论。 */
data class FeedbackComment(
    val id: Long,
    val user: String,
    val body: String,
    val createdAt: String,
)

object GitHubClient {

    private const val OWNER = "sitong-zhang"
    private const val REPO = "DevicePerfMonitor"
    private const val RAW_ANNOUNCEMENT =
        "https://raw.githubusercontent.com/$OWNER/$REPO/main/announcement.json"
    private const val CONTENTS_API =
        "https://api.github.com/repos/$OWNER/$REPO/contents/announcement.json"
    private const val ISSUES_API =
        "https://api.github.com/repos/$OWNER/$REPO/issues?labels=feedback&state=all&per_page=50"

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

    private fun post(url: String, token: String, jsonBody: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
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

    private fun patch(url: String, token: String, jsonBody: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "PATCH"
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

    /** 读取反馈 issues（免 token，公开仓库）。 */
    suspend fun fetchFeedbackIssues(): List<FeedbackIssue> = withContext(Dispatchers.IO) {
        val (code, body) = get(ISSUES_API)
        require(code in 200..299) { "读取反馈失败：HTTP $code" }
        val arr = JSONArray(body)
        val list = mutableListOf<FeedbackIssue>()
        for (i in 0 until arr.length()) {
            val it = arr.getJSONObject(i)
            // 过滤掉 PR（issues API 会包含 PR）
            if (it.has("pull_request")) continue
            list.add(
                FeedbackIssue(
                    number = it.optInt("number", 0),
                    title = it.optString("title", ""),
                    state = it.optString("state", ""),
                    createdAt = it.optString("created_at", ""),
                    body = it.optString("body", ""),
                    user = it.optJSONObject("user")?.optString("login", "") ?: "",
                ),
            )
        }
        list
    }

    /** 读取某条 issue 的全部评论（公开，免 token 也可读）。 */
    suspend fun fetchIssueComments(number: Int, token: String? = null): List<FeedbackComment> =
        withContext(Dispatchers.IO) {
            val url = "https://api.github.com/repos/$OWNER/$REPO/issues/$number/comments"
            val (code, body) = get(url, token)
            require(code in 200..299) { "读取评论失败：HTTP $code" }
            val arr = JSONArray(body)
            val list = mutableListOf<FeedbackComment>()
            for (i in 0 until arr.length()) {
                val it = arr.getJSONObject(i)
                list.add(
                    FeedbackComment(
                        id = it.optLong("id", 0L),
                        user = it.optJSONObject("user")?.optString("login", "") ?: "",
                        body = it.optString("body", ""),
                        createdAt = it.optString("created_at", ""),
                    ),
                )
            }
            list
        }

    /**
     * 回复反馈：在 issue 下发表评论（需 token 的写操作）。
     */
    suspend fun postIssueComment(
        context: Context,
        number: Int,
        reply: String,
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val token = TokenVault.getToken(context)
            ?: return@withContext 401 to "未授权：安全模块未提供 token（环境不纯 / 未注入）。"
        val url = "https://api.github.com/repos/$OWNER/$REPO/issues/$number/comments"
        val payload = JSONObject().apply {
            put("body", reply)
        }.toString()
        val (code, resp) = post(url, token, payload)
        code to if (code in 200..299) "回复已发布" else "回复失败：HTTP $code $resp"
    }

    /**
     * 标记反馈已解决：关闭对应 issue（需 token 的写操作）。
     * closed=true 关闭，false 重新打开。
     */
    suspend fun setIssueState(
        context: Context,
        number: Int,
        closed: Boolean,
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val token = TokenVault.getToken(context)
            ?: return@withContext 401 to "未授权：安全模块未提供 token（环境不纯 / 未注入）。"
        val url = "https://api.github.com/repos/$OWNER/$REPO/issues/$number"
        val payload = JSONObject().apply {
            put("state", if (closed) "closed" else "open")
        }.toString()
        val (code, resp) = patch(url, token, payload)
        code to if (code in 200..299) {
            if (closed) "已标记为已解决" else "已重新打开"
        } else "操作失败：HTTP $code $resp"
    }
}
