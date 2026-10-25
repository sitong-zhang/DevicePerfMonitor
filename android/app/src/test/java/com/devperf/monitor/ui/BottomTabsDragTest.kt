package com.devperf.monitor.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import com.devperf.monitor.ui.nav.drawBrandWallpaper
import com.devperf.monitor.ui.theme.DevicePerfTheme
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.catalog.components.LiquidBottomTab
import com.kyant.backdrop.catalog.components.LiquidBottomTabs
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 底栏 tab 拖拽回归（1.0.26 排障）。
 *
 * 用户反馈：LiquidBottomTabs 从「总览」拖到「设置」可以，但按住往回拖就卡死，
 * 必须点第 1 个 tab 才恢复。复刻 AppNav 结构，legacy 图形模式（shader 为 stub），
 * 模拟「拖过去 → 拖回来」两轮手势，断言 onTabSelected 两次都触发。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BottomTabsDragTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun bottomTabs_dragBackAndForth() {
        val events = mutableListOf<Int>()
        composeRule.setContent {
            DevicePerfTheme(themeMode = ThemeMode.DARK) {
                val backdrop = rememberLayerBackdrop()
                val wallpaper: ImageBitmap =
                    ImageBitmap.imageResource(R.drawable.wallpaper_light)
                val logo: ImageBitmap =
                    ImageBitmap.imageResource(R.drawable.wallpaper_logo)
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.matchParentSize().appLayerBackdrop(backdrop)) {
                        Canvas(modifier = Modifier.matchParentSize()) {
                            drawBrandWallpaper(wallpaper, logo)
                        }
                    }
                    var selectedIndex by remember { mutableIntStateOf(0) }
                    CompositionLocalProvider(LocalBackdrop provides backdrop) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .testTag("tabs"),
                        ) {
                            LiquidBottomTabs(
                                selectedTabIndex = { selectedIndex },
                                onTabSelected = { events += it; selectedIndex = it },
                                backdrop = backdrop,
                                tabsCount = 2,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                LiquidBottomTab(onClick = { events += 0; selectedIndex = 0 }) {
                                    Icon(Icons.Filled.Home, contentDescription = null)
                                    Text("总览")
                                }
                                LiquidBottomTab(onClick = { events += 1; selectedIndex = 1 }) {
                                    Icon(Icons.Filled.Settings, contentDescription = null)
                                    Text("设置")
                                }
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val node = composeRule.onNodeWithTag("tabs")
        val semanticsNode = node.fetchSemanticsNode()
        val width = semanticsNode.boundsInRoot.width
        val tabW = width / 2f
        val centerY = semanticsNode.boundsInRoot.height / 2f

        // 第一轮：按住 tab0 的 pill 拖到 tab1
        node.performTouchInput {
            down(Offset(tabW / 2f, centerY))
            moveBy(Offset(tabW * 0.9f, 0f))
            moveBy(Offset(tabW * 0.05f, 0f))
            up()
        }
        composeRule.mainClock.advanceTimeBy(1500)
        composeRule.waitForIdle()
        println("PROBE bottomTabs events after first drag: $events, selectedIndex snapshot")

        // 第二轮：按住 tab1 的 pill 拖回 tab0
        node.performTouchInput {
            down(Offset(tabW * 1.5f, centerY))
            moveBy(Offset(-tabW * 0.9f, 0f))
            moveBy(Offset(-tabW * 0.05f, 0f))
            up()
        }
        composeRule.mainClock.advanceTimeBy(1500)
        composeRule.waitForIdle()
        println("PROBE bottomTabs events after second drag: $events")

        assertEquals("两轮拖拽后应依次选中 1 再选中 0", listOf(1, 0), events)
    }

    /**
     * 真实导航链路回归（1.0.26 排障核心用例）。
     *
     * 与 [bottomTabs_dragBackAndForth] 的区别：onTabSelected 这里驱动真实
     * NavController（等价于 AppNav 的 BottomNavBar），currentRoute 是异步更新的
     * —— 正是真机上「拖回失效」的可疑窗口。断言两轮拖拽后路由收敛在拖拽目标上，
     * 且 onTabSelected 不出现重复/回弹发射。
     */
    @Test
    fun bottomTabs_realNavigation_dragBack() {
        val events = mutableListOf<Int>()
        composeRule.setContent {
            DevicePerfTheme(themeMode = ThemeMode.DARK) {
                val backdrop = rememberLayerBackdrop()
                val wallpaper: ImageBitmap =
                    ImageBitmap.imageResource(R.drawable.wallpaper_light)
                val logo: ImageBitmap =
                    ImageBitmap.imageResource(R.drawable.wallpaper_logo)
                val navController = rememberNavController()
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = backStackEntry?.destination?.route
                val selectedIndex = if (currentRoute == "settings") 1 else 0
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
                            enterTransition = {
                                slideInVertically(animationSpec = tween(320)) { it / 6 }
                            },
                            exitTransition = {
                                slideOutVertically(animationSpec = tween(240)) { -it / 6 }
                            },
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
                            LiquidBottomTabs(
                                selectedTabIndex = { selectedIndex },
                                onTabSelected = { index ->
                                    events += index
                                    val route = if (index == 1) "settings" else "overview"
                                    if (route != currentRoute) {
                                        navController.navigate(route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                backdrop = backdrop,
                                tabsCount = 2,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                LiquidBottomTab(
                                    onClick = {
                                        events += 0
                                        if (currentRoute != "overview") {
                                            navController.navigate("overview") {
                                                popUpTo(navController.graph.findStartDestination().id) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    },
                                ) {
                                    Icon(Icons.Filled.Home, contentDescription = null)
                                    Text("总览")
                                }
                                LiquidBottomTab(
                                    onClick = {
                                        events += 1
                                        if (currentRoute != "settings") {
                                            navController.navigate("settings") {
                                                popUpTo(navController.graph.findStartDestination().id) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    },
                                ) {
                                    Icon(Icons.Filled.Settings, contentDescription = null)
                                    Text("设置")
                                }
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val node = composeRule.onNodeWithTag("tabs")
        val semanticsNode = node.fetchSemanticsNode()
        val width = semanticsNode.boundsInRoot.width
        val tabW = width / 2f
        val centerY = semanticsNode.boundsInRoot.height / 2f

        // 第一轮：拖到 tab1（设置）
        node.performTouchInput {
            down(Offset(tabW / 2f, centerY))
            moveBy(Offset(tabW * 0.9f, 0f))
            moveBy(Offset(tabW * 0.05f, 0f))
            up()
        }
        composeRule.mainClock.advanceTimeBy(1500)
        composeRule.waitForIdle()
        assertEquals("拖到 tab1 后应只触发一次 onTabSelected(1)", listOf(1), events)
        composeRule.onNodeWithTag("page_settings").assertExists("拖到 tab1 后应导航到设置页")

        // 第二轮：按住 tab1 的 pill 拖回 tab0（总览）
        node.performTouchInput {
            down(Offset(tabW * 1.5f, centerY))
            moveBy(Offset(-tabW * 0.9f, 0f))
            moveBy(Offset(-tabW * 0.05f, 0f))
            up()
        }
        composeRule.mainClock.advanceTimeBy(1500)
        composeRule.waitForIdle()
        assertEquals("拖回 tab0 后应只再触发一次 onTabSelected(0)", listOf(1, 0), events)
        composeRule.onNodeWithTag("page_overview").assertExists("拖回 tab0 后应导航回总览页")
    }
}
