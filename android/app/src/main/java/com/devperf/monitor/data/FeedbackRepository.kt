package com.devperf.monitor.data

import android.os.Build
import androidx.annotation.VisibleForTesting
import com.devperf.monitor.BuildConfig
import java.net.URLEncoder

/**
 * 用户反馈仓库（1.0.28 起改为邮箱通道）。
 *
 * 用户拍板：不再依赖 GitHub token（fine-grained PAT 需要网页端手工创建，
 * 拿不到），反馈直接走用户手机上的邮件 App 发到官方邮箱。链路：
 * 1. 反馈页收集正文 + 可选联系方式；
 * 2. 本仓库拼出预填好的 `mailto:` 链接（收件人=官方邮箱，主题=版本+机型，
 *    正文=反馈内容+联系方式+设备信息）；
 * 3. 拉起邮件 App，用户点「发送」即达——发件人是用户自己的邮箱，开发者可直接回复。
 *
 * 收件邮箱由构建注入（buildConfigField FEEDBACK_EMAIL，-PdevperfFeedbackEmail
 * 或环境变量 DEVPERF_FEEDBACK_EMAIL 可覆盖），没配时用内置默认值。
 */
class FeedbackRepository {

    /** 反馈收件邮箱：构建注入，缺省内置。 */
    val targetEmail: String get() =
        BuildConfig.FEEDBACK_EMAIL.ifBlank { DEFAULT_EMAIL }

    /** 反馈主题：版本 + 机型，收件箱里一眼能看出是哪个版本的哪台设备。 */
    fun buildMailSubject(): String =
        "【用户反馈】DevicePerfMonitor v${BuildConfig.VERSION_NAME} · ${Build.BRAND} ${Build.MODEL}"

    /** 反馈正文：分节清晰排版，开发者一眼能定位问题与设备。 */
    @VisibleForTesting
    internal fun buildMailBody(content: String, contact: String): String = buildString {
        appendLine("【反馈内容】")
        appendLine(content.ifBlank { "（用户未填写）" })
        appendLine()
        appendLine("【联系方式】")
        appendLine(contact.ifBlank { "（未留）" })
        appendLine()
        appendLine("————————————")
        append(deviceInfo())
        appendLine()
        append("（本邮件由「设备性能监控」App 自动生成 · 分子智能科技）")
    }

    /** 设备信息块：帮定位「只有我这台机器这样」类问题，分四行对齐展示。 */
    fun deviceInfo(): String = buildString {
        append("机型：").append(Build.BRAND).append(' ').append(Build.MODEL)
        appendLine()
        append("系统：Android ").append(Build.VERSION.RELEASE)
            .append("（API ").append(Build.VERSION.SDK_INT).append(')')
        appendLine()
        append("App：v").append(BuildConfig.VERSION_NAME)
            .append("（").append(BuildConfig.VERSION_CODE).append(')')
        appendLine()
        append("处理器：").append(Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown abi")
    }

    /**
     * 拼预填邮件链接：`mailto:<收件人>?subject=<主题>&body=<正文>`。
     * 各邮件 App（Gmail / QQ邮箱 / 系统邮件）都识别该格式；收件人是纯邮箱
     * 地址不做编码（`@` 编码成 %40 部分邮件 App 不识别），主题/正文里的
     * 中文、`&`、换行统一 URL 编码后由邮件 App 还原。
     */
    fun buildMailto(content: String, contact: String): String =
        "mailto:$targetEmail" +
            "?subject=${enc(buildMailSubject())}" +
            "&body=${enc(buildMailBody(content, contact))}"

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    companion object {
        /** 内置默认收件邮箱（构建未注入时兜底）。 */
        const val DEFAULT_EMAIL = "wpqptwy@qq.com"
    }
}
