package com.devperf.monitor.data

import com.devperf.monitor.BuildConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URLDecoder

/**
 * 1.0.26 反馈仓库纯逻辑测试。
 *
 * 不联网：token 通道依赖 buildConfigField 注入的 PAT（测试构建里为空串），
 * 正好覆盖「无 token → 网页降级」这条默认路径；直连通道的 HTTP 细节
 * 由真机/发版后验证，这里只测格式化与降级 URL 这些纯函数。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeedbackRepositoryTest {

    private val repo = FeedbackRepository()

    @Test
    fun buildIssueTitle_containsVersionAndDevice() {
        val title = repo.buildIssueTitle()
        assertTrue("标题应带 App 版本", title.contains(BuildConfig.VERSION_NAME))
        assertTrue("标题应以「反馈」开头，管理端列表好识别", title.startsWith("反馈"))
    }

    @Test
    fun buildIssueBody_hasContentContactAndDeviceInfo() {
        val body = repo.buildIssueBody("滑动图表时偶现卡顿", "邮箱 me@example.com")
        assertTrue(body.contains("## 反馈内容"))
        assertTrue(body.contains("滑动图表时偶现卡顿"))
        assertTrue(body.contains("## 联系方式"))
        assertTrue(body.contains("邮箱 me@example.com"))
        assertTrue(body.contains("## 设备信息"))
        // 设备信息至少要有 Android 版本行
        assertTrue(repo.deviceInfo().contains("Android"))
    }

    @Test
    fun buildIssueBody_blankContact_markedAsNotProvided() {
        val body = repo.buildIssueBody("想要暗色模式", "")
        assertTrue(body.contains("（未留）"))
        assertFalse(body.contains("想要暗色模式\n\n## 联系方式\n\n\n\n"))
    }

    @Test
    fun buildBrowserFallbackUrl_encodesTitleAndBody() {
        val title = repo.buildIssueTitle()
        val body = repo.buildIssueBody("反馈内容 & 符号", "")
        val url = repo.buildBrowserFallbackUrl(title, body)
        // 指向仓库 new-issue 页，带 feedback label
        assertTrue(url.startsWith("https://github.com/sitong-zhang/DevicePerfMonitor/issues/new?"))
        assertTrue(url.contains("labels=feedback"))
        // 标题与正文经过 URL 编码后能解回原文（中文、&、换行都不能丢）
        val query = url.substringAfter("?")
        val params = query.split("&").associate {
            val kv = it.split("=", limit = 2)
            kv[0] to URLDecoder.decode(kv[1], "UTF-8")
        }
        assertEquals(title, params["title"])
        assertEquals(body, params["body"])
    }

    @Test
    fun submit_withoutToken_fallsBackToBrowser() = runBlocking {
        // 测试构建未注入 token：必须走网页降级，且链接与纯函数生成的一致
        // （若哪天 CI 注入了 token，这条会失败——那是故意的，提醒补直连测试）
        assertTrue(
            "测试构建不应注入反馈 token（否则本断言失效，需补 token 通道测试）",
            BuildConfig.GITHUB_FEEDBACK_TOKEN.isEmpty(),
        )
        when (val result = repo.submit("界面挺好看的，提个建议", "")) {
            is FeedbackRepository.SubmitResult.BrowserFallback -> {
                assertEquals(
                    repo.buildBrowserFallbackUrl(
                        repo.buildIssueTitle(),
                        repo.buildIssueBody("界面挺好看的，提个建议", ""),
                    ),
                    result.url,
                )
            }
            else -> error("无 token 时应降级到浏览器通道，实际：$result")
        }
    }
}
