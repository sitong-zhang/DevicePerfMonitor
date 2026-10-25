package com.devperf.monitor.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.devperf.monitor.R
import com.devperf.monitor.data.ThemeMode
import com.devperf.monitor.ui.kit.LocalBackdrop
import com.devperf.monitor.ui.kit.appLayerBackdrop
import com.devperf.monitor.ui.nav.drawBrandWallpaper
import com.devperf.monitor.ui.theme.DevicePerfTheme
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 背景层的 NATIVE 真绘制验证（1.0.25 新增）。
 *
 * 1.0.13 真机闪退（layerBackdrop 自指采样）的根因侧就是背景层结构：录制子树内
 * 出现了玻璃元素。本测试复刻 AppNav 的背景层结构（appLayerBackdrop 只录壁纸 +
 * logo 角标），并在 NATIVE 图形模式下同步走完真实绘制，验证：
 * - LayerBackdrop 录制结构可用；
 * - drawBrandWallpaper 的 center-crop drawImage、竖向罩层、右上角 logo 角标
 *   （clipPath + drawImage）真实产出像素；
 * - 罩层确实压暗了浅色壁纸（文字可读性保障，1.0.25 换浅蓝绿壁纸后的关键回归）。
 *
 * 为什么不画玻璃元素：Robolectric 的软件 canvas 拒绝 RuntimeShader /
 * RenderEffect（"Software rendering doesn't support RuntimeShader"），而所有
 * catalog 玻璃组件的绘制链最终都经过 backdrop 的 RuntimeShader（LiquidButton/
 * Toggle/BottomTabs 经 InteractiveHighlight，LiquidSlider 经 HighlightModifier）。
 * 玻璃侧由组合冒烟（[GlassComponentsRenderTest]）+ 发布后真机覆盖；本测试专注
 * 背景层这一侧，它正是 1.0.13 崩溃场景的录制子树内容。
 *
 * 截图方式说明：不用 ui-test 的 captureToImage——Compose 1.11.4 的
 * WindowCapture.forceRedraw 尚无 Robolectric 短路分支（androidx-main 上才有），
 * 依赖 frame loop 的 onDraw 回调在 Robolectric 里永远不会触发，必然 2s 超时。
 * 这里改为在 UI 线程同步执行 view.draw(Canvas)，NATIVE 图形模式下会真实
 * 光栅化全部绘制指令，等价于强制走完绘制流程。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackdropDrawingRenderTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun backdropBackground_drawsWithoutCrash() {
        // 壁纸原图的平均亮度（0~255）：罩层回归的基准。decodeResource 不解码
        // composable，避免 imageResource 的 @Composable 限制。
        val wallpaperBitmap = BitmapFactory.decodeResource(
            composeRule.activity.resources,
            R.drawable.wallpaper_light,
        )
        val wallpaperAvgLuminance = averageLuminance(wallpaperBitmap)
        wallpaperBitmap.recycle()

        composeRule.setContent {
            DevicePerfTheme(themeMode = ThemeMode.DARK) {
                val backdrop = rememberLayerBackdrop()
                val wallpaper: ImageBitmap =
                    ImageBitmap.imageResource(R.drawable.wallpaper_light)
                val logo: ImageBitmap =
                    ImageBitmap.imageResource(R.drawable.wallpaper_logo)
                Box(modifier = Modifier.fillMaxSize()) {
                    // 同 AppNav：录制子树内只有背景内容，玻璃元素不进来
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .appLayerBackdrop(backdrop),
                    ) {
                        Canvas(modifier = Modifier.matchParentSize()) {
                            drawBrandWallpaper(wallpaper, logo)
                        }
                    }
                    // LocalBackdrop 照常下发，保证结构复刻完整
                    CompositionLocalProvider(LocalBackdrop provides backdrop) {
                        Box(modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 同步绘制：NATIVE 模式下 view.draw 会真实光栅化 Compose 的绘制指令，
        // 任何 backdrop 录制/采样问题都会在这里抛出来
        val decorView: View = composeRule.activity.window.decorView
        if (decorView.width == 0 || decorView.height == 0) {
            decorView.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            )
            decorView.layout(0, 0, decorView.measuredWidth, decorView.measuredHeight)
        }
        check(decorView.width > 0 && decorView.height > 0) {
            "decorView 尺寸异常: ${decorView.width}x${decorView.height}"
        }
        val bitmap = Bitmap.createBitmap(
            decorView.width,
            decorView.height,
            Bitmap.Config.ARGB_8888,
        )
        decorView.draw(Canvas(bitmap))

        val image = bitmap.asImageBitmap()
        assertTrue("位图宽度应大于 0", image.width > 0)
        assertTrue("位图高度应大于 0", image.height > 0)

        // 壁纸 + 罩层 + logo 角标应该产出实际像素，而不是全透明的空画布
        val pixels = image.toPixelMap()
        val stride = 64
        var paintedPixels = 0
        var drawnLuminanceSum = 0.0
        for (y in 0 until pixels.height step stride) {
            for (x in 0 until pixels.width step stride) {
                val c = pixels[x, y]
                if (c.alpha > 0.01f) {
                    paintedPixels++
                    drawnLuminanceSum += (0.2126 * c.red + 0.7152 * c.green + 0.0722 * c.blue) * 255f
                }
            }
        }
        assertTrue(
            "采样点中应存在已绘制内容（壁纸），实际只有 $paintedPixels 个非透明点",
            paintedPixels > 0,
        )

        // 罩层生效回归：绘制结果的平均亮度应显著低于壁纸原图的平均亮度。
        // 浅色壁纸（平均亮度高）在深色主题下必须被竖向罩层压暗，否则文字不可读。
        val drawnAvgLuminance = drawnLuminanceSum / paintedPixels
        assertTrue(
            "罩层应把浅色壁纸压暗：绘制平均亮度 $drawnAvgLuminance 应低于" +
                "壁纸原图平均亮度 $wallpaperAvgLuminance 的 80%",
            drawnAvgLuminance < wallpaperAvgLuminance * 0.8,
        )
    }

    private fun averageLuminance(bitmap: Bitmap): Double {
        val stride = 64
        var sum = 0.0
        var count = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val p = bitmap.getPixel(x, y)
                sum += 0.2126 * AndroidColor.red(p) +
                    0.7152 * AndroidColor.green(p) +
                    0.0722 * AndroidColor.blue(p)
                count++
                x += stride
            }
            y += stride
        }
        return if (count > 0) sum / count else 0.0
    }
}
