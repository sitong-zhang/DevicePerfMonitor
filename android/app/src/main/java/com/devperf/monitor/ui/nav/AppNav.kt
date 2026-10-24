package com.devperf.monitor.ui.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.devperf.monitor.ui.MonitorViewModel
import com.devperf.monitor.ui.UpdateDialog
import com.devperf.monitor.ui.kit.LocalBackdrop
import com.devperf.monitor.ui.kit.appLayerBackdrop
import com.devperf.monitor.ui.kit.pressScale
import com.devperf.monitor.ui.kit.rememberAppBackdrop
import com.devperf.monitor.ui.kit.rememberHaptics
import com.devperf.monitor.ui.screen.MetricDetailScreen
import com.devperf.monitor.ui.screen.OverviewScreen
import com.devperf.monitor.ui.screen.SettingsScreen
import com.devperf.monitor.ui.theme.DesignTokens
import com.devperf.monitor.ui.theme.LocalAppMotion
import com.devperf.monitor.ui.theme.LocalTokenColors
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
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

    val colors = LocalTokenColors.current

    Box(modifier = Modifier.fillMaxSize()) {
        // glass flavor：应用级 backdrop 只挂在「纯背景节点」上——这是 Kyant0
        // catalog BackdropDemoScaffold 的标准结构：背景层（layerBackdrop + 渐变）
        // 与 NavHost、底栏互为兄弟，所有 drawBackdrop 玻璃元素都在录制子树之外。
        //
        // 1.0.13 曾把 layerBackdrop 直接挂在 NavHost 上，玻璃卡片因此落在录制
        // 子树内：LayerBackdropNode 录制时会重放玻璃节点的 drawLayer，采样到的
        // 是正在录音的同一个 RenderNode——自指采样，未定义行为，真机一进首页
        // 就闪退。库本身没有任何问题（Kotlin Multiplatform，Android 侧消费
        // backdrop-android 是官方路径），是挂载结构用错了。
        // plain flavor 为 null，全部回落到 MD3 原样外观。
        val backdrop = if (com.devperf.monitor.BuildConfig.LIQUID_GLASS) {
            com.devperf.monitor.ui.kit.rememberAppBackdrop()
        } else {
            null
        }
        if (backdrop != null) {
            // 背景层：主题渐变与各页面背景同源同色，玻璃的折射/模糊采样这一层
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .appLayerBackdrop(backdrop)
                    .background(
                        Brush.verticalGradient(
                            listOf(colors.background, colors.surface, colors.background),
                        ),
                    ),
            )
        }
        androidx.compose.runtime.CompositionLocalProvider(
            com.devperf.monitor.ui.kit.LocalBackdrop provides backdrop
        ) {
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

        // 应用内更新提示（1.0.15 新增）：有新版本时弹窗，其余状态不显示
        UpdateDialog(viewModel)
    }
}

/**
 * 浮动胶囊底栏（自绘，1.0.9）。
 *
 * 以前用 M3 的 NavigationBar：直角矩形、贴着屏幕边缘、选中态是方形的
 * secondaryContainer 指示器——形态和配色都偏"系统默认"。现在改成：
 * 圆角胶囊容器 + 真投影（浮在内容之上）+ 描边，选中态是一条**滑动的 pill 指示器**，
 * 用 Expressive 的 fastSpatial 弹簧从一个 tab 滑到另一个。
 *
 * 几何参数全部来自 design-tokens 的 bottomBar 段，与 iOS 的 FloatingCapsuleBar
 * 用同一组数字，双端视觉一致。
 */
@Composable
private fun BottomNavBar(
    currentRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val cs = MaterialTheme.colorScheme
    val capsule = RoundedCornerShape(DesignTokens.Radius.PILL.dp)
    val selectedIndex = topLevelTabs.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)

    // 1.0.13 glass flavor：容器走 Kyant0 Backdrop 的 drawBackdrop（参数用
    // LiquidBottomTabs 示例既有值：Capsule + vibrancy/blur 8dp/lens 24dp×24dp，
    // surface 用主题 surfaceContainer 40% 透明保证可读性）；plain flavor
    // 保留原 Surface + 投影 + 描边。
    val backdrop = LocalBackdrop.current
    val glassOn = com.devperf.monitor.BuildConfig.LIQUID_GLASS && backdrop != null

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = DesignTokens.BottomBar.MARGIN_HORIZONTAL.dp,
                vertical = DesignTokens.BottomBar.MARGIN_BOTTOM.dp,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val barContent: @Composable () -> Unit = {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val itemWidth = maxWidth / topLevelTabs.size
                // 滑动指示器：位置由弹簧驱动，切换 tab 时滑过去而不是瞬移
                val indicatorOffset by animateFloatAsState(
                    targetValue = selectedIndex.toFloat(),
                    animationSpec = spring(
                        dampingRatio = DesignTokens.Motion.Spring.FAST_SPATIAL_DAMPING,
                        stiffness = DesignTokens.Motion.Spring.FAST_SPATIAL_STIFFNESS,
                    ),
                    label = "navIndicator",
                )
                Box(
                    modifier = Modifier
                        .offset(x = itemWidth * indicatorOffset)
                        .padding(DesignTokens.BottomBar.INDICATOR_INSET.dp)
                        .width(itemWidth)
                        .fillMaxHeight()
                        .background(
                            color = cs.primary.copy(alpha = DesignTokens.BottomBar.INDICATOR_ALPHA),
                            shape = capsule,
                        ),
                )
                Row(modifier = Modifier.fillMaxSize()) {
                    topLevelTabs.forEach { tab ->
                        val selected = tab.route == currentRoute
                        NavBarItem(
                            tab = tab,
                            selected = selected,
                            onSelect = {
                                if (!selected) {
                                    haptics.select()
                                    onSelect(tab.route)
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        if (glassOn) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DesignTokens.BottomBar.HEIGHT.dp)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            vibrancy()
                            blur(8f.dp.toPx())
                            lens(24f.dp.toPx(), 24f.dp.toPx())
                        },
                        onDrawSurface = {
                            drawRect(cs.surfaceContainer.copy(alpha = 0.4f))
                        }
                    )
            ) {
                barContent()
            }
        } else {
            // 容器：level2 投影（比卡片高一级，因为它浮在内容之上）
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DesignTokens.BottomBar.HEIGHT.dp)
                    .shadow(
                        elevation = (DesignTokens.Elevation.LEVEL2.SHADOW_Y +
                            DesignTokens.Elevation.LEVEL2.RADIUS / 4f).dp,
                        shape = capsule,
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = DesignTokens.Elevation.LEVEL2.ALPHA),
                        spotColor = Color.Black.copy(alpha = DesignTokens.Elevation.LEVEL2.ALPHA),
                    ),
                shape = capsule,
                color = cs.surfaceContainer,
                border = BorderStroke(
                    width = DesignTokens.Surface.BORDER_WIDTH.dp,
                    color = cs.outline.copy(alpha = DesignTokens.Surface.BORDER_ALPHA),
                ),
            ) {
                barContent()
            }
        }
    }
}

/** 底栏的一项：图标 + 标签，选中时放大一点并换成主色。 */
@Composable
private fun NavBarItem(
    tab: TopLevelTab,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val scale by animateFloatAsState(
        targetValue = if (selected) DesignTokens.BottomBar.SELECTED_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = DesignTokens.Motion.Spring.FAST_SPATIAL_DAMPING,
            stiffness = DesignTokens.Motion.Spring.FAST_SPATIAL_STIFFNESS,
        ),
        label = "navItemScale",
    )
    val tint = if (selected) cs.primary else cs.onSurfaceVariant
    Box(
        modifier = modifier
            .fillMaxHeight()
            .pressScale(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onSelect,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = tab.icon,
                contentDescription = tab.label,
                tint = tint,
                modifier = Modifier
                    .size(DesignTokens.BottomBar.ICON_SIZE.dp)
                    .scale(scale),
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = tab.label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = DesignTokens.BottomBar.LABEL_SIZE.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = tint,
            )
        }
    }
}
