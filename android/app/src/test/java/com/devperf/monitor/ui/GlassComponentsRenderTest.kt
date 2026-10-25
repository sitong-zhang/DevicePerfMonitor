package com.devperf.monitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.devperf.monitor.R
import com.devperf.monitor.data.ThemeMode
import com.devperf.monitor.ui.kit.GlassButton
import com.devperf.monitor.ui.kit.GlassSlider
import com.devperf.monitor.ui.kit.GlassSwitch
import com.devperf.monitor.ui.kit.LocalBackdrop
import com.devperf.monitor.ui.kit.appLayerBackdrop
import com.devperf.monitor.ui.nav.drawBrandWallpaper
import com.devperf.monitor.ui.theme.DevicePerfTheme
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.catalog.components.LiquidBottomTab
import com.kyant.backdrop.catalog.components.LiquidBottomTabs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 玻璃结构组合冒烟（1.0.25 新增）。
 *
 * 背景：1.0.13 真机闪退的根因就是 backdrop 录制子树里出现了玻璃元素（自指采样），
 * 这类问题编译期看不出来，只有真跑一遍组合才会暴露。本测试用 Robolectric 复刻
 * AppNav 的结构：背景层（appLayerBackdrop）与玻璃元素互为兄弟，玻璃元素
 * （catalog LiquidBottomTabs + GlassButton/Switch/Slider）在录制子树之外。
 *
 * 图形模式说明：本测试跑 Robolectric 默认的 legacy 图形模式。NATIVE 模式无法
 * 用于含 InteractiveHighlight 的组件——其 AGSL 用了 `layout(color)` uniform
 * qualifier（Android 官方文档确认 API 33+ 真机合法，且 vendor 自上游 Kyant
 * 仓库不改源码），但 Robolectric native runtime 内置的 SkSL 编译器不接受该
 * qualifier，组合期直接抛 IllegalArgumentException。legacy 模式下 RuntimeShader
 * 是 no-op stub，组合 → 布局全流程可正常走完。真绘制路径（背景层 + LiquidSlider）
 * 的 NATIVE 验证见 [BackdropDrawingRenderTest]，环境能力探针见
 * [RuntimeShaderProbeTest]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlassComponentsRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun glassStructure_composesWithoutCrash() {
        composeRule.setContent {
            DevicePerfTheme(themeMode = ThemeMode.DARK) {
                // 复刻 AppNav 的 backdrop 结构：背景层录壁纸，玻璃元素在录制子树外
                val backdrop = rememberLayerBackdrop()
                val wallpaper: ImageBitmap =
                    ImageBitmap.imageResource(R.drawable.wallpaper_light)
                val logo: ImageBitmap =
                    ImageBitmap.imageResource(R.drawable.wallpaper_logo)
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .appLayerBackdrop(backdrop),
                    ) {
                        Canvas(modifier = Modifier.matchParentSize()) {
                            drawBrandWallpaper(wallpaper, logo)
                        }
                    }
                    CompositionLocalProvider(LocalBackdrop provides backdrop) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // 顶部留白同各页面（72dp）——避开壁纸右上角的 logo 角标
                            Box(modifier = Modifier.padding(top = 56.dp))
                            GlassButton(text = "玻璃按钮", onClick = {})
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                GlassSwitch(checked = true, onCheckedChange = {})
                            }
                            GlassSlider(
                                value = 0.6f,
                                onValueChange = {},
                                valueRange = 0.4f..1f,
                            )
                            var selectedTab by remember { mutableIntStateOf(0) }
                            LiquidBottomTabs(
                                selectedTabIndex = { selectedTab },
                                onTabSelected = { selectedTab = it },
                                backdrop = backdrop,
                                tabsCount = 2,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                LiquidBottomTab(onClick = { selectedTab = 0 }) {
                                    Icon(
                                        imageVector = Icons.Filled.Home,
                                        contentDescription = null,
                                    )
                                    Text("总览")
                                }
                                LiquidBottomTab(onClick = { selectedTab = 1 }) {
                                    Icon(
                                        imageVector = Icons.Filled.Settings,
                                        contentDescription = null,
                                    )
                                    Text("设置")
                                }
                            }
                        }
                    }
                }
            }
        }
        // 组合 + 布局完成即通过：所有 catalog 组件（含 InteractiveHighlight 的
        // shader 构造）与 backdrop 结构都真实走了一遍。语义树断言确认控件真的
        // 进入了组合，而不是被条件分支悄悄跳过。
        composeRule.waitForIdle()
        composeRule.onNodeWithText("玻璃按钮").assertExists("玻璃按钮应进入组合")
        composeRule.onNodeWithText("总览").assertExists("底栏 tab「总览」应进入组合")
        composeRule.onNodeWithText("设置").assertExists("底栏 tab「设置」应进入组合")
    }
}
