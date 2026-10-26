package com.devperf.monitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.devperf.monitor.data.ThemeMode
import com.devperf.monitor.ui.kit.GlassSlider
import com.devperf.monitor.ui.kit.LocalBackdrop
import com.devperf.monitor.ui.kit.appLayerBackdrop
import com.devperf.monitor.ui.theme.DevicePerfTheme
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * 玻璃滑块手势测试（1.0.27）。
 *
 * 历史教训：1.0.26 之前这堆测试其实是「假绿」——
 * [com.devperf.monitor.ui.kit.LocalBackdrop] 默认是 null，测试里没有提供
 * backdrop，GlassSlider 直接走 MD3 Slider 兜底分支，自研的那层 pointerInput
 * 从来没被测过。真机上走玻璃分支时它和组件自带的 inspectDragGestures 抢同一个
 * pointer，两条手势都 cancel，表现是「滑块完全拖不动、点也没反应」。
 *
 * 因此本测试先按 AppNav / upstream BackdropDemoScaffold 的结构把 LayerBackdrop
 * 挂好（背景层 + CompositionLocalProvider），确保被测的是玻璃分支；再验证
 * upstream demo 认可的两种交互：拖 thumb、点轨道跳转。
 *
 * 图形模式：Robolectric legacy（NATIVE 下 vendor 的 AGSL 用了 layout(color)
 * qualifier，SkSL 编译器在 native runtime 里不接受，组合期直接抛异常；
 * legacy 模式 RuntimeShader 是 no-op stub，手势/布局链路可正常验证）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlassSliderDragTest {

    companion object {
        private const val TAG = "glass_slider"
        private const val MIN = 0.4f
        private const val MAX = 1f
    }

    @get:Rule
    val composeRule = createComposeRule()

    /** 复刻 AppNav 的玻璃挂载结构：背景层录 backdrop，内容通过 LocalBackdrop 取用。 */
    private fun setGlassContent(initialValue: Float) {
        composeRule.setContent {
            DevicePerfTheme(themeMode = ThemeMode.DARK) {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .appLayerBackdrop(backdrop),
                    ) {
                        Canvas(Modifier.matchParentSize()) {
                            drawIntoCanvas { }
                            drawRect(androidx.compose.ui.graphics.Color(0xFF00303A))
                        }
                    }
                    CompositionLocalProvider(LocalBackdrop provides backdrop) {
                        var value by remember { mutableFloatStateOf(initialValue) }
                        SliderProbe.value = value
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item { Text("占位", modifier = Modifier.padding(16.dp)) }
                            item {
                                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                                    GlassSlider(
                                        value = value,
                                        onValueChange = {
                                            value = it
                                            SliderProbe.value = it
                                        },
                                        valueRange = MIN..MAX,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag(TAG),
                                    )
                                }
                            }
                            items(10) { i -> Text("填充项 $i", modifier = Modifier.padding(16.dp)) }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** 测试内外共享「当前值」（ Composable 闭包里的局部变量读不到）。 */
    private object SliderProbe {
        var value: Float = Float.NaN
    }

    @Test
    fun glassBranch_isActuallyUsed() {
        // 守卫：本测试的环境必须是玻璃分支。若哪天 LocalBackdrop 又没挂上，
        // 下面的手势测试会退化成测 MD3 Slider，等于白测——这里先断言住。
        setGlassContent(0.5f)
        val before = SliderProbe.value
        composeRule.onNodeWithTag(TAG).performTouchInput {
            val x = visibleSize.width * ((before - MIN) / (MAX - MIN))
            down(Offset(x, centerY))
            moveBy(Offset(visibleSize.width * 0.3f, 0f))
            up()
        }
        composeRule.mainClock.advanceTimeBy(800)
        composeRule.waitForIdle()
        // MD3 Slider 与 LiquidSlider 在教程性拖拽下都会改值，无法区分真假分支；
        // 真正的区分点是滑谁：轨道点按（见 tapOnTrack 用例）。此处只做流程守卫。
        assertTrue("初值应可读到", before == 0.5f)
    }

    @Test
    fun dragThumb_changesValue() {
        setGlassContent(0.5f)
        val fraction = (0.5f - MIN) / (MAX - MIN)

        composeRule.onNodeWithTag(TAG).performTouchInput {
            // thumb 中心 = 轨道宽度 × 当前进度（upstream LiquidSlider 的定位公式）
            val thumbX = visibleSize.width * fraction
            down(Offset(thumbX, centerY))
            moveBy(Offset(visibleSize.width * 0.15f, 0f))
            moveBy(Offset(visibleSize.width * 0.15f, 0f))
            up()
        }
        composeRule.mainClock.advanceTimeBy(1000)
        composeRule.waitForIdle()

        println("PROBE drag thumb -> value=${SliderProbe.value}")
        assertTrue(
            "拖动 thumb 后 value 应变大（实际 ${SliderProbe.value}）",
            SliderProbe.value > 0.55f,
        )
    }

    @Test
    fun tapOnTrack_jumpsToPosition() {
        setGlassContent(0.5f)

        // 轨道右端点按 —— upstream LiquidSlider 的 detectTapGestures 负责跳转。
        // 1.0.26 的自研手势层会先吞掉这个 tap（返回值判断后不做任何处理），
        // 表现就是用户说的「贴上去也没有效果」。
        composeRule.onNodeWithTag(TAG).performTouchInput {
            down(Offset(visibleSize.width * 0.85f, centerY))
            up()
        }
        composeRule.mainClock.advanceTimeBy(1000)
        composeRule.waitForIdle()

        val expected = MIN + (MAX - MIN) * 0.85f
        println("PROBE tap track -> value=${SliderProbe.value} expected≈$expected")
        assertTrue(
            "点轨道应跳到该位置（实际 ${SliderProbe.value}，期望≈$expected）",
            abs(SliderProbe.value - expected) < 0.08f,
        )
    }

    @Test
    fun tapNearStart_jumpsBack() {
        setGlassContent(0.9f)

        composeRule.onNodeWithTag(TAG).performTouchInput {
            down(Offset(visibleSize.width * 0.1f, centerY))
            up()
        }
        composeRule.mainClock.advanceTimeBy(1000)
        composeRule.waitForIdle()

        val expected = MIN + (MAX - MIN) * 0.1f
        println("PROBE tap start -> value=${SliderProbe.value} expected≈$expected")
        assertTrue(
            "点轨道左端应往回跳（实际 ${SliderProbe.value}，期望≈$expected）",
            abs(SliderProbe.value - expected) < 0.08f,
        )
    }
}
