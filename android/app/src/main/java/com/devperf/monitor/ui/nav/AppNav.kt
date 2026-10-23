package com.devperf.monitor.ui.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.devperf.monitor.ui.MonitorViewModel
import com.devperf.monitor.ui.kit.rememberHaptics
import com.devperf.monitor.ui.screen.MetricDetailScreen
import com.devperf.monitor.ui.screen.OverviewScreen
import com.devperf.monitor.ui.screen.SettingsScreen
import com.devperf.monitor.ui.theme.DesignTokens
import com.devperf.monitor.ui.theme.LocalAppMotion
import androidx.compose.ui.unit.dp

/**
 * 应用导航：首页（总览）/ 设置 两个顶级目的地 + 指标详情页。
 *
 * 动效规范（design-tokens.json）：页面切换一律使用三条曲线之一
 * （enter 320ms / exit 240ms / emphasized 300ms），不使用 MD3 默认过渡。
 */
object Routes {
    const val OVERVIEW = "overview"
    const val SETTINGS = "settings"
    const val DETAIL = "detail/{metricId}"

    fun detail(metricId: String) = "detail/$metricId"
}

private data class TopLevelTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val topLevelTabs = listOf(
    TopLevelTab(Routes.OVERVIEW, "总览", Icons.Filled.Home),
    TopLevelTab(Routes.SETTINGS, "设置", Icons.Filled.Settings),
)

@Composable
fun AppNav(
    viewModel: MonitorViewModel,
    onRequestOverlayPermission: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in topLevelTabs.map { it.route }

    // NavHost / AnimatedVisibility 的 enter/exit 均为非 Composable lambda，
    // 不能在其中读取 CompositionLocal —— 先在 Composable 作用域取出曲线值
    val motion = LocalAppMotion.current
    val enterSpec = androidx.compose.animation.core.tween<IntOffset>(
        durationMillis = motion.enterDurationMs,
        easing = motion.enter,
    )
    val exitSpec = androidx.compose.animation.core.tween<IntOffset>(
        durationMillis = motion.exitDurationMs,
        easing = motion.exit,
    )

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Routes.OVERVIEW,
            // 进入使用 enter 曲线（320ms），符合三条动效曲线规范
            enterTransition = {
                slideInVertically(animationSpec = enterSpec) { it / 6 }
            },
            exitTransition = {
                slideOutVertically(animationSpec = exitSpec) { -it / 6 }
            },
        ) {
            composable(Routes.OVERVIEW) {
                OverviewScreen(
                    viewModel = viewModel,
                    onOpenDetail = { id -> navController.navigate(Routes.detail(id.name)) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    viewModel = viewModel,
                    onRequestOverlayPermission = onRequestOverlayPermission,
                )
            }
            composable(Routes.DETAIL) { entry ->
                val coreId = entry.arguments?.getString("metricId")
                    ?.let { name ->
                        runCatching {
                            com.devperf.monitor.core.CoreMetricId.valueOf(name)
                        }.getOrNull()
                    }
                MetricDetailScreen(
                    viewModel = viewModel,
                    coreId = coreId,
                    onBack = { navController.popBackStack() },
                )
            }
        }

        // 底部导航栏：Material 3 NavigationBar（Expressive 动效由 motionScheme 提供）
        AnimatedVisibility(
            visible = showBottomBar,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(animationSpec = enterSpec) { it },
            exit = slideOutVertically(animationSpec = exitSpec) { it },
        ) {
            BottomNavBar(
                currentRoute = currentRoute,
                onSelect = { route ->
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
            )
        }
    }
}

@Composable
private fun BottomNavBar(
    currentRoute: String?,
    onSelect: (String) -> Unit,
) {
    val haptics = rememberHaptics()
    // 悬浮式底栏：左右/底部留 24dp，与卡片体系留出一致的呼吸空间；
    // 容器用 surfaceContainer（主题里已映射到 Catppuccin 表面色），不加色调层，保持深色平整
    NavigationBar(
        modifier = Modifier.padding(
            start = DesignTokens.Spacing.S24.dp,
            end = DesignTokens.Spacing.S24.dp,
            bottom = DesignTokens.Spacing.S24.dp,
        ),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
    ) {
        topLevelTabs.forEach { tab ->
            val selected = tab.route == currentRoute
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (tab.route != currentRoute) {
                        haptics.select()
                        onSelect(tab.route)
                    }
                },
                icon = {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                    )
                },
                label = { Text(tab.label) },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
