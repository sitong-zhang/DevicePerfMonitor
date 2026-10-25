package com.devperf.monitor.ui.wallpaper

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.devperf.monitor.R
import com.devperf.monitor.data.MonitorSettings
import com.devperf.monitor.data.SettingsRepository
import com.devperf.monitor.data.WallpaperId
import com.devperf.monitor.ui.screen.WallpaperPicker
import com.devperf.monitor.ui.theme.DevicePerfTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 1.0.26「壁纸自由切换」测试：
 * - [Wallpapers] 纯函数（采样比、文件解码、失败路径）；
 * - SettingsRepository 的壁纸持久化 round-trip（含自定义路径）；
 * - [rememberWallpaperImage] 的内置/自定义解析（含文件缺失回落）；
 * - 设置页 [WallpaperPicker] 的组合冒烟（选中态、点击回调）。
 *
 * 环境说明：
 * - sdk=34：与本工程其他 Robolectric 测试一致（SDK 36/37 需 Java 21，
 *   本机是 Java 20）；
 * - graphicsMode=NATIVE：壁纸断言依赖**真实位图解码**（像素比对、非图片
 *   拒绝、尺寸校验），legacy 模式下 BitmapFactory 是永远成功的桩，测不到
 *   这些东西。picker 冒烟只用 Image/Icon/Text，无 AGSL，NATIVE 下同样安全
 *   （真绘制路径的 NATIVE 用法见 BackdropDrawingRenderTest）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WallpaperTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    // ------------------------------------------------------------------
    // Wallpapers 纯逻辑
    // ------------------------------------------------------------------

    @Test
    fun sampleSizeFor_capsLongestEdgeAt2048() {
        // 小于阈值不采样
        assertEquals(1, Wallpapers.sampleSizeFor(1024, 2220))
        assertEquals(1, Wallpapers.sampleSizeFor(2048, 2048))
        // 超过阈值按 2 的幂逐级减半，直到最长边 <= 2048
        assertEquals(2, Wallpapers.sampleSizeFor(4096, 1000))
        assertEquals(4, Wallpapers.sampleSizeFor(8192, 1000))
        assertEquals(2, Wallpapers.sampleSizeFor(1000, 4096))
    }

    @Test
    fun decodeCustom_readsRealImage_rejectsMissingAndNonImage() {
        // 真实图片：从资源复制一份到 filesDir，走与设置页相同的落盘位置
        val dest = Wallpapers.customFile(context)
        context.resources.openRawResource(R.drawable.wallpaper_light).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        val decoded = Wallpapers.decodeCustom(dest.absolutePath)
        assertNotNull("复制进 filesDir 的壁纸应能解码", decoded)
        assertTrue("解码尺寸应保持原图 1024x2220", decoded!!.width == 1024 && decoded.height == 2220)

        // 不存在的文件 → null（调用方据此回落内置）
        assertNull(Wallpapers.decodeCustom("/no/such/file.jpg"))

        // 非图片文件（文本）→ null，不让无效自定义壁纸把背景搞挂
        val bogus = File(context.filesDir, "not_an_image.jpg").apply {
            writeText("这不是图片")
        }
        assertNull(Wallpapers.decodeCustom(bogus.absolutePath))

        dest.delete()
        bogus.delete()
    }

    @Test
    fun importFromUri_nullForUnresolvableUri_isSafe() = runBlocking {
        // contentResolver 打不开的 Uri 必须安全返回 null，不抛
        val result = Wallpapers.importFromUri(context, Uri.parse("content://nope/x"))
        assertNull(result)
    }

    // ------------------------------------------------------------------
    // SettingsRepository 持久化 round-trip
    // ------------------------------------------------------------------

    @Test
    fun settings_roundTrip_wallpaperAndCustomPath() = runBlocking {
        val repo = SettingsRepository(context)

        // 默认值：内置青蓝、无自定义路径
        val initial = repo.settings.first()
        assertEquals(WallpaperId.LIGHT_TEAL, initial.wallpaper)
        assertNull(initial.wallpaperCustomPath)

        // 切内置
        repo.setWallpaper(WallpaperId.AURORA)
        assertEquals(WallpaperId.AURORA, repo.settings.first().wallpaper)

        // 设自定义：写入路径 + 自动切到 CUSTOM
        val path = File(context.filesDir, "fake_wallpaper.jpg").absolutePath
        repo.setWallpaperCustom(path)
        val after = repo.settings.first()
        assertEquals(WallpaperId.CUSTOM, after.wallpaper)
        assertEquals(path, after.wallpaperCustomPath)

        // 从自定义切回内置：路径保留（用户再点自定义还能用），但壁纸回落内置
        repo.setWallpaper(WallpaperId.MINT)
        val back = repo.settings.first()
        assertEquals(WallpaperId.MINT, back.wallpaper)
        assertEquals(path, back.wallpaperCustomPath)
    }

    // ------------------------------------------------------------------
    // rememberWallpaperImage 解析（每次测试只 setContent 一次，
    // 多个变体放进同一棵树里分别捕获）
    // ------------------------------------------------------------------

    @Test
    fun rememberWallpaperImage_resolvesBuiltIns() {
        var light: ImageBitmap? = null
        var aurora: ImageBitmap? = null
        var mint: ImageBitmap? = null
        composeRule.setContent {
            DevicePerfTheme {
                Column {
                    Box { light = rememberWallpaperImage(MonitorSettings(wallpaper = WallpaperId.LIGHT_TEAL)) }
                    Box { aurora = rememberWallpaperImage(MonitorSettings(wallpaper = WallpaperId.AURORA)) }
                    Box { mint = rememberWallpaperImage(MonitorSettings(wallpaper = WallpaperId.MINT)) }
                }
            }
        }
        composeRule.waitForIdle()
        val lp = light!!.toPixelMap()[8, 8].toArgb()
        val ap = aurora!!.toPixelMap()[8, 8].toArgb()
        val mp = mint!!.toPixelMap()[8, 8].toArgb()
        // 三张内置壁纸同尺寸（1024x2220）但取色不同（青蓝 / 极光紫 / 薄荷绿）
        assertTrue("三张壁纸像素应互不相同", lp != ap && ap != mp && lp != mp)
    }

    @Test
    fun rememberWallpaperImage_customMissingFile_fallsBackToBuiltIn() {
        var builtIn: ImageBitmap? = null
        var customMissing: ImageBitmap? = null
        composeRule.setContent {
            DevicePerfTheme {
                Column {
                    Box { builtIn = rememberWallpaperImage(MonitorSettings(wallpaper = WallpaperId.LIGHT_TEAL)) }
                    // CUSTOM 但文件不在（被系统清理）：必须回落内置，不能黑屏
                    Box {
                        customMissing = rememberWallpaperImage(
                            MonitorSettings(
                                wallpaper = WallpaperId.CUSTOM,
                                wallpaperCustomPath = "/no/such/file.jpg",
                            ),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        // 文件缺失时 produceState 一启动就解析出 null，无需等待异步解码
        assertEquals(
            builtIn!!.toPixelMap()[8, 8].toArgb(),
            customMissing!!.toPixelMap()[8, 8].toArgb(),
        )
    }

    @Test
    fun rememberWallpaperImage_customExistingFile_loadsIt() {
        val dest = Wallpapers.customFile(context)
        context.resources.openRawResource(R.drawable.wallpaper_aurora).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        try {
            var builtIn: ImageBitmap? = null
            var custom = mutableStateOf<ImageBitmap?>(null)
            composeRule.setContent {
                DevicePerfTheme {
                    Column {
                        Box { builtIn = rememberWallpaperImage(MonitorSettings(wallpaper = WallpaperId.LIGHT_TEAL)) }
                        Box {
                            custom.value = rememberWallpaperImage(
                                MonitorSettings(
                                    wallpaper = WallpaperId.CUSTOM,
                                    wallpaperCustomPath = dest.absolutePath,
                                ),
                            )
                        }
                    }
                }
            }
            val fallbackPixel = builtIn!!.toPixelMap()[8, 8].toArgb()
            // 自定义文件走 produceState(IO 解码)：轮询等它出结果，
            // 与内置青蓝不同 → 证明加载的是自定义文件而不是回落图
            composeRule.waitUntil(timeoutMillis = 5_000) {
                custom.value?.toPixelMap()?.get(8, 8)?.toArgb() != fallbackPixel
            }
            assertTrue(
                "自定义壁纸应与内置青蓝不同",
                custom.value!!.toPixelMap()[8, 8].toArgb() != fallbackPixel,
            )
        } finally {
            dest.delete()
        }
    }

    // ------------------------------------------------------------------
    // 设置页壁纸选择行冒烟
    // ------------------------------------------------------------------

    @Test
    fun wallpaperPicker_showsOptions_selectionAndCallbacks() {
        var selected: WallpaperId? = null
        var galleryClicked = false
        composeRule.setContent {
            DevicePerfTheme {
                WallpaperPicker(
                    settings = MonitorSettings(wallpaper = WallpaperId.LIGHT_TEAL),
                    onSelectBuiltIn = { selected = it },
                    onPickFromGallery = { galleryClicked = true },
                )
            }
        }

        // 三个内置 + 相册入口都在
        composeRule.onNodeWithText("青蓝").assertExists()
        composeRule.onNodeWithText("极光").assertExists()
        composeRule.onNodeWithText("薄荷").assertExists()
        composeRule.onNodeWithText("相册").assertExists()
        // 默认选中青蓝：有选中角标
        composeRule.onNodeWithContentDescription("已选中").assertExists()

        // 点「极光」缩略图（图本身在可点 Box 内；底部文字在 Box 外，点了没用）
        // → 回调 AURORA
        composeRule.onNodeWithContentDescription("极光").performClick()
        composeRule.waitForIdle()
        assertEquals(WallpaperId.AURORA, selected)

        // 点「相册」入口 → 打开系统相册面板
        composeRule.onNodeWithText("相册").performClick()
        composeRule.waitForIdle()
        assertTrue(galleryClicked)
    }

    @Test
    fun wallpaperPicker_customMode_showsCustomThumbWhenFileExists() {
        val dest = Wallpapers.customFile(context)
        context.resources.openRawResource(R.drawable.wallpaper_mint).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        try {
            var selected: WallpaperId? = null
            var galleryClicked = false
            composeRule.setContent {
                DevicePerfTheme {
                    WallpaperPicker(
                        settings = MonitorSettings(
                            wallpaper = WallpaperId.CUSTOM,
                            wallpaperCustomPath = dest.absolutePath,
                        ),
                        onSelectBuiltIn = { selected = it },
                        onPickFromGallery = { galleryClicked = true },
                    )
                }
            }
            // 自定义缩略图走 IO 解码，等它渲染出来
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithContentDescription("已选中")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("自定义").assertExists()
            composeRule.onNodeWithContentDescription("已选中").assertExists()

            // 自定义缩略图可点（再点一次 = 重新选图）：走相册入口，不动内置壁纸。
            // 点图本身（底部文字标签在可点 Box 之外，点了没用）。
            composeRule.onNodeWithContentDescription("自定义").performClick()
            composeRule.waitForIdle()
            assertTrue(galleryClicked)
            assertNull(selected)
        } finally {
            dest.delete()
        }
    }
}
