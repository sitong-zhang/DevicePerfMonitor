package com.devperf.monitor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.devperf.monitor.data.ThemeMode
import com.devperf.monitor.ui.kit.GlassSlider
import com.devperf.monitor.ui.theme.DevicePerfTheme
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 滑块拖拽复现测试（1.0.26 排障）。
 *
 * 用户反馈：设置页里悬浮窗不透明度 / 默认缩放两个滑块「有移动效果但拖不动」。
 * 怀疑 LazyColumn 的垂直滚动手势消费了 move 事件，导致 catalog LiquidSlider
 * 的 inspectDragGestures 提前走 onDragCancel，onValueChange 从未被调用。
 * 本测试复刻设置页结构（LazyColumn + GlassSlider 玻璃分支）并模拟带垂直
 * 抖动的水平拖拽。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlassSliderDragTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun glassSlider_horizontalDrag_changesValue() {
        var value = 0.5f
        composeRule.setContent {
            DevicePerfTheme(themeMode = ThemeMode.DARK) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        Text("占位", modifier = Modifier.padding(16.dp))
                    }
                    item {
                        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                            GlassSlider(
                                value = value,
                                onValueChange = { value = it },
                                valueRange = 0.4f..1f,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("opacity"),
                            )
                        }
                    }
                    items(20) { i ->
                        Text("填充项 $i", modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 按住轨道左端（thumb 命中区之外）向右拖——1.0.26 前的官方手势在此区域
        // 没有任何响应，只有 tap 跳转（用户反馈「有移动效果但拖不动」）
        composeRule.onNodeWithTag("opacity").performTouchInput {
            down(Offset(20f, centerY))
            moveBy(Offset(40f, 3f))
            moveBy(Offset(40f, -8f))
            moveBy(Offset(40f, 7f))
            up()
        }
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.waitForIdle()

        println("PROBE glassSlider value after track drag: $value")
        assertNotEquals("按住轨道拖拽后 value 应发生变化", 0.5f, value)
        assertTrue("向右拖拽 value 应变大（初始 0.5，实际 $value）", value > 0.6f)
    }

    @Test
    fun glassSlider_verticalIntent_doesNotChangeValue() {
        // 垂直意图放行给外层 LazyColumn：按轨道后纯垂直拖不应改值
        var value = 0.5f
        composeRule.setContent {
            DevicePerfTheme(themeMode = ThemeMode.DARK) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                            GlassSlider(
                                value = value,
                                onValueChange = { value = it },
                                valueRange = 0.4f..1f,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("opacity"),
                            )
                        }
                    }
                    items(20) { i ->
                        Text("填充项 $i", modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("opacity").performTouchInput {
            down(Offset(20f, centerY))
            moveBy(Offset(0f, 30f))
            moveBy(Offset(2f, 40f))
            up()
        }
        composeRule.mainClock.advanceTimeBy(300)
        composeRule.waitForIdle()

        println("PROBE glassSlider value after vertical drag: $value")
        assertTrue("垂直拖拽不应改值（实际 $value）", kotlin.math.abs(value - 0.5f) < 0.001f)
    }
}
