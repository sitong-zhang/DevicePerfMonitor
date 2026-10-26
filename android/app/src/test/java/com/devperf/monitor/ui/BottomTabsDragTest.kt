package com.devperf.monitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.devperf.monitor.R
import com.devperf.monitor.data.ThemeMode
import com.devperf.monitor.ui.kit.LocalBackdrop
import com.devperf.monitor.ui.kit.appLayerBackdrop
import com.devperf.monitor.ui.nav.BottomNavBar
import com.devperf.monitor.ui.nav.drawBrandWallpaper
import com.devperf.monitor.ui.theme.DevicePerfTheme
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 底栏 tab 拖拽回归（1.0.27 重写）。
 *
 * 之前这版测试是「自己抄一份 AppNav 的内联副本」——被测的是测试里的副本，
 * AppNav 真的改了它也测不出来（典型的假绿：代码改了，测试对着副本继续绿灯）。
 * 1.0.27 起直接驱动真实组件 [BottomNavBar]（对测试包可见），并用真实
 * NavController 收尾，assert 的每一步都是线上路径。
 *
 * 覆盖三种交互与 AppNav 的真实挂载结构（背景层录像 + LocalBackdrop + NavHost +
 * 底栏浮层）：拖到第二个 tab、拖回第一个 tab、点击 tab 文案。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BottomTabsDragTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** AppNav 的结构复刻：背景层录 backdrop，底栏浮在 NavHost 之上。 */
    private fun setNavContent(onSelectLog: MutableList<String>) {
        composeRule.setContent {
            DevicePerfTheme(themeMode = ThemeMode.DARK) {
                val backdrop = rememberLayerBackdrop()
                val wallpaper: ImageBitmap =
                    ImageBitmap.imageResource(R.drawable.wallpaper_light)
                val logo: ImageBitmap = ImageBitmap.imageResource(R.drawable.wallpaper_logo)
                val navController = rememberNavController()
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = backStackEntry?.destination?.route
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.matchParentSize().appLayerBackdrop(backdrop)) {
                        Canvas(modifier = Modifier.matchParentSize()) {
                            drawBrandWallpaper(wallpaper, logo)
                        }
                    }
                    CompositionLocalProvider(LocalBackdrop provides backdrop) {
                        NavHost(
                            navController = navController,
                            startDestination = "overview",
                        ) {
                            composable("overview") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .testTag("page_overview"),
                                )
                            }
                            composable("settings") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .testTag("page_settings"),
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .testTag("tabs"),
                        ) {
                            BottomNavBar(
                                currentRoute = currentRoute,
                                // 幂等导航，与 AppNav 里的一致
                                onSelect = { route ->
                                    onSelectLog += route
                                    navController.navigate(route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun dragPill(fromTabLeft: Boolean) {
        val node = composeRule.onNodeWithTag("tabs")
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val tabW = bounds.width / 2f
        val centerY = bounds.height / 2f
        node.performTouchInput {
            val startX = if (fromTabLeft) tabW * 0.5f else tabW * 1.5f
            val delta = if (fromTabLeft) tabW * 0.95f else -tabW * 0.95f
            down(Offset(startX, centerY))
            moveBy(Offset(delta * 0.6f, 0f))
            moveBy(Offset(delta * 0.3f, 0f))
            moveBy(Offset(delta * 0.05f, 0f))
            up()
        }
        composeRule.mainClock.advanceTimeBy(1500)
        composeRule.waitForIdle()
    }

    @Test
    fun dragPillToSecondTab_navigates() {
        val log = mutableListOf<String>()
        setNavContent(log)

        dragPill(fromTabLeft = true)

        println("PROBE drag->tab2 log=$log")
        composeRule.onNodeWithTag("page_settings")
            .assertExists("拖到第二个 tab 应导航到设置页")
        assertTrue("应回调一次 settings", log.count { it == "settings" } >= 1)
    }

    @Test
    fun dragPillBackToFirstTab_navigatesBack() {
        val log = mutableListOf<String>()
        setNavContent(log)

        dragPill(fromTabLeft = true)
        dragPill(fromTabLeft = false)

        println("PROBE drag->tab2->back log=$log")
        composeRule.onNodeWithTag("page_overview")
            .assertExists("拖回第一个 tab 应导航回总览页")
        assertEquals(
            "拖过去再拖回来后，最后一次回调应是 overview",
            "overview",
            log.lastOrNull(),
        )
    }

    @Test
    fun tapTabLabel_navigates() {
        val log = mutableListOf<String>()
        setNavContent(log)

        // 点第二个 tab 的图标：ab 图标带 contentDescription，比按坐标点稳。
        // 注意组件会把 tab 内容画两遍（可见层 + 录 backdrop 的隐藏层），所以同名节点有两个。
        composeRule.onAllNodesWithContentDescription("设置")[0].performClick()
        composeRule.mainClock.advanceTimeBy(1500)
        composeRule.waitForIdle()
        println("PROBE tap tab2 log=$log")
        composeRule.onNodeWithTag("page_settings")
            .assertExists("点第二个 tab 应导航到设置页")
    }
}
