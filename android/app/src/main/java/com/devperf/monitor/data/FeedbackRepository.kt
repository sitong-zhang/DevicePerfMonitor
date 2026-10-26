package com.devperf.monitor.data

import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.devperf.monitor.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 用户反馈仓库（1.0.26 新增）。
 *
 * 用户原话：「用户能反馈任何事情……我在管理端就能看」。链路：
 * 1. App 设置页「意见反馈」收集文本 + 可选联系方式；
 * 2. 本仓库把反馈创建成带 `feedback` label 的 GitHub Issue（管理端
 *    「反馈收件箱」按 label 拉取列表，见本地管理端工具）；
 * 3. 没配 token（本地构建/CI 没注入）时降级：生成预填好的 GitHub 网页
 *    issue 链接交给系统浏览器，反馈路径不断。
 *
 * 关于 token 进包：反馈是低频低危写操作（最坏情况就是在仓库里刷 issue，
 *  owner 可删可锁），用 fine-grained PAT 只授 `issues:write` 单仓库权限，
 * 通过 buildConfigField 注入（-PdevperfFeedbackToken 或环境变量）。
 * 不发版时不注入即可走网页降级通道。
 */
class FeedbackRepository {

    /** 提交结果的三态：直连成功 / 降级到网页 / 失败。 */
    sealed interface SubmitResult {
        /** token 通道：issue 已创建，[issueUrl] 为用户可查看的链接（可能为空）。 */
        data class Submitted(val issueUrl: String?) : SubmitResult

        /** 无 token 降级：预填好的 GitHub 网页 issue 链接。 */
        data class BrowserFallback(val url: String) : SubmitResult

        /** 网络或服务端失败（含 HTTP 错误信息）。 */
        data class Failed(val reason: String) : SubmitResult
    }

    /**
     * 提交反馈。[content] 为反馈正文（已去空白，非空由调用方保证），
     * [contact] 为可选联系方式（空串表示没留）。
     */
    suspend fun submit(content: String, contact: String): SubmitResult =
        withContext(Dispatchers.IO) {
            val token = decodeToken().trim()
            if (token.isEmpty()) {
                return@withContext SubmitResult.BrowserFallback(
                    buildBrowserFallbackUrl(buildIssueTitle(), buildIssueBody(content, contact)),
                )
            }
            try {
                // label 不存在时创建 issue 会 422；先 best-effort 确保 label 在
                // （已存在返回 422，无所谓，忽略即可）。
                ensureLabel(token)
                val issueUrl = createIssue(token, buildIssueTitle(), buildIssueBody(content, contact))
                SubmitResult.Submitted(issueUrl)
            } catch (e: Exception) {
                Log.w(TAG, "反馈提交失败", e)
                SubmitResult.Failed(e.message ?: "网络异常，请稍后重试")
            }
        }

    /** 设备信息块：帮定位「只有我这台机器这样」类问题。 */
    fun deviceInfo(): String = buildString {
        append(Build.BRAND).append(' ').append(Build.MODEL)
        append(" / Android ").append(Build.VERSION.RELEASE)
        append(" (API ").append(Build.VERSION.SDK_INT).append(')')
        append(" / App ").append(BuildConfig.VERSION_NAME)
        append(" (").append(BuildConfig.VERSION_CODE).append(')')
        append(" / ").append(Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown abi")
    }

    /** issue 标题：反馈 · 版本 · 机型，管理端列表一眼能看出是哪类问题。 */
    fun buildIssueTitle(): String =
        "反馈 · v${BuildConfig.VERSION_NAME} · ${Build.BRAND} ${Build.MODEL}"

    /** issue 正文：反馈内容 + 联系方式 + 设备信息。 */
    @VisibleForTesting
    internal fun buildIssueBody(content: String, contact: String): String = buildString {
        append("## 反馈内容\n\n")
        append(content.ifBlank { "（用户没写内容）" })
        append("\n\n## 联系方式\n\n")
        append(contact.ifBlank { "（未留）" })
        append("\n\n## 设备信息\n\n")
        append(deviceInfo())
    }

    /**
     * 网页降级链接：GitHub new-issue 页预填标题/正文/label。
     * App 侧点击「提交」后由设置页直接拉起浏览器打开。
     */
    @VisibleForTesting
    internal fun buildBrowserFallbackUrl(title: String, body: String): String =
        "$REPO_URL/issues/new?labels=$FEEDBACK_LABEL" +
            "&title=${enc(title)}&body=${enc(body)}"

    // ------------------------------------------------------------------
    // 内部：GitHub REST
    // ------------------------------------------------------------------

    /**
     * 取出并解码反馈 token：构建时做了 base64（见 build.gradle.kts 的
     * buildConfigField GITHUB_FEEDBACK_TOKEN_B64），这里解码回明文。
     * 字段为空或解码失败都回落为空串，触发网页降级通道。
     */
    private fun decodeToken(): String {
        val b64 = BuildConfig.GITHUB_FEEDBACK_TOKEN_B64
        if (b64.isEmpty()) return ""
        return runCatching {
            String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8)
        }.getOrDefault("")
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** best-effort 确保 `feedback` label 存在（已存在 = 422，忽略）。 */
    private fun ensureLabel(token: String) {
        val payload = JSONObject()
            .put("name", FEEDBACK_LABEL)
            .put("color", "0e8a16")
            .put("description", "App 用户反馈（管理端收件箱）")
            .toString()
            .toByteArray()
        val (_, code) = githubRequest(
            method = "POST",
            url = "$API_BASE/labels",
            token = token,
            body = payload,
        )
        // 201 新建 / 422 已存在都算 OK；其他码不阻断（创建 issue 时再报）
        if (code != 201 && code != 422) {
            Log.w(TAG, "确保 feedback label 存在失败：HTTP $code（继续尝试创建 issue）")
        }
    }

    private fun createIssue(token: String, title: String, body: String): String? {
        val payload = JSONObject()
            .put("title", title)
            .put("body", body)
            .put("labels", org.json.JSONArray().put(FEEDBACK_LABEL))
            .toString()
            .toByteArray()
        val (resp, code) = githubRequest(
            method = "POST",
            url = "$API_BASE/issues",
            token = token,
            body = payload,
        )
        if (code !in 200..299) {
            throw IOException("创建 issue 失败（HTTP $code）：${resp.take(200)}")
        }
        return runCatching { JSONObject(resp).optString("html_url").takeIf { it.isNotBlank() } }
            .getOrNull()
    }

    /** 发一次 GitHub API 请求，返回 (响应体文本, HTTP 状态码；0 = 网络异常)。 */
    private fun githubRequest(
        method: String,
        url: String,
        token: String,
        body: ByteArray? = null,
    ): Pair<String, Int> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", USER_AGENT)
                if (body != null) {
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                    outputStream.use { it.write(body) }
                }
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val code = conn.responseCode
            val text = runCatching {
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }
            }.getOrNull().orEmpty()
            text to code
        } catch (e: Exception) {
            Log.w(TAG, "GitHub 请求异常：$method $url", e)
            (e.message ?: "network error") to 0
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val TAG = "FeedbackRepository"
        private const val USER_AGENT = "DevicePerfMonitor-Feedback"

        /** 反馈仓库：与 UpdateRepository 同一个（sitong-zhang/DevicePerfMonitor）。 */
        const val REPO = "sitong-zhang/DevicePerfMonitor"
        private const val API_BASE = "https://api.github.com/repos/$REPO"
        private const val REPO_URL = "https://github.com/$REPO"

        /** 反馈 label：管理端收件箱按它过滤，App 创建 issue 时带上。 */
        const val FEEDBACK_LABEL = "feedback"
    }
}
