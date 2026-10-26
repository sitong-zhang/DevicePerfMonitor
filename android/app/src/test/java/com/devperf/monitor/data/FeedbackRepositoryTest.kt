package com.devperf.monitor.data

import com.devperf.monitor.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URLDecoder

/**
 * 1.0.28 反馈仓库（邮箱通道）纯逻辑测试。
 *
 * 不联网：邮箱通道是纯本地 mailto 拼装，正好全覆盖。只测格式化与
 * mailto 编码这些纯函数。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeedbackRepositoryTest {

    private val repo = FeedbackRepository()

    @Test
    fun buildMailSubject_containsVersionAndDevice() {
        val subject = repo.buildMailSubject()
        assertTrue("主题应带 App 版本", subject.contains(BuildConfig.VERSION_NAME))
        assertTrue("主题应标明是用户反馈", subject.contains("用户反馈"))
        assertTrue("主题应带机型（Robolectric 默认 robolectric）", subject.contains("robolectric"))
    }

    @Test
    fun buildMailBody_hasContentContactAndDeviceInfo() {
        val body = repo.buildMailBody("滑动图表时偶现卡顿", "邮箱 me@example.com")
        assertTrue(body.contains("反馈内容"))
        assertTrue(body.contains("滑动图表时偶现卡顿"))
        assertTrue(body.contains("联系方式"))
        assertTrue(body.contains("邮箱 me@example.com"))
        // 设备信息至少要有 Android 版本，且带品牌落款
        assertTrue(repo.deviceInfo().contains("Android"))
        assertTrue(body.contains("分子智能科技"))
    }

    @Test
    fun buildMailBody_blankContact_markedAsNotProvided() {
        val body = repo.buildMailBody("想要暗色模式", "")
        assertTrue(body.contains("（未留）"))
    }

    @Test
    fun buildMailto_encodesRecipientSubjectAndBody() {
        val subject = repo.buildMailSubject()
        val body = repo.buildMailBody("反馈内容 & 符号\n换行", "")
        val url = repo.buildMailto("反馈内容 & 符号\n换行", "")
        // mailto: 收件人是官方邮箱
        assertTrue("mailto 应指向收件邮箱", url.startsWith("mailto:"))
        assertTrue(url.contains("@"))
        // 主题与正文经过 URL 编码后能解回原文（中文、&、换行都不能丢）
        val query = url.substringAfter("?")
        val params = query.split("&").associate {
            val kv = it.split("=", limit = 2)
            kv[0] to URLDecoder.decode(kv[1], "UTF-8")
        }
        assertEquals(subject, params["subject"])
        assertEquals(body, params["body"])
    }

    @Test
    fun targetEmail_defaultsWhenNotInjected() {
        // 测试构建未注入 FEEDBACK_EMAIL 时应回退内置默认邮箱
        val email = repo.targetEmail
        assertTrue("收件邮箱应非空", email.isNotBlank())
        assertTrue("收件邮箱应是合法格式", email.contains("@"))
    }
}
