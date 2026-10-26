package com.devperf.monitor.ui.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.devperf.monitor.R
import com.devperf.monitor.ui.MonitorViewModel
import com.devperf.monitor.ui.AnnouncementDialog
import com.devperf.monitor.ui.UpdateDialog
import com.devperf.monitor.ui.UpdateUiState
import com.devperf.monitor.ui.kit.LocalBackdrop
import com.devperf.monitor.ui.kit.appLayerBackdrop
import com.devperf.monitor.ui.kit.pressScale
import com.devperf.monitor.ui.kit.rememberAppBackdrop
import com.devperf.monitor.ui.kit.rememberHaptics
import com.devperf.monitor.ui.screen.FeedbackScreen
import com.devperf.monitor.ui.screen.MetricDetailScreen
import com.devperf.monitor.ui.screen.OverviewScreen
import com.devperf.monitor.ui.screen.SettingsScreen
import com.devperf.monitor.ui.theme.DesignTokens
import com.devperf.monitor.ui.theme.LocalAppMotion
import com.devperf.monitor.ui.wallpaper.rememberWallpaperImage
import com.kyant.backdrop.catalog.components.LiquidBottomTab
import com.kyant.backdrop.catalog.components.LiquidBottomTabs
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

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
    const val FEEDBACK = "feedback"

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
    val updateState by viewModel.updateState.collectAsState()
    // 1.0.26 壁纸自由切换：背景层按设置解析（内置三选一 / 相册自定义）
    val settings by viewModel.settings.collectAsState()

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
            // 背景层：品牌壁纸（1.0.26 起可在设置页切换：青蓝 / 极光 / 薄荷 /
            // 相册自定义）+ 暗色罩层 + 右上角公司 logo 角标。
            // 罩层的理由：内置壁纸是浅色的，而 App 是深色主题（浅色文字），
            // 直接铺浅色壁纸所有文字都会糊；角标位置与各页顶部留白见
            // drawBrandWallpaper。
            val wallpaper = rememberWallpaperImage(settings)
            val logo = ImageBitmap.imageResource(R.drawable.wallpaper_logo)
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .appLayerBackdrop(backdrop),
            ) {
                Canvas(Modifier.matchParentSize()) {
                    drawBrandWallpaper(wallpaper, logo)
                }
            }
        }
        // LocalBackdrop 下发范围必须覆盖 NavHost + 底栏 + 弹窗：1.0.24 及以前
        // 只包了 NavHost，底栏与弹窗取到 null，玻璃分支全是死代码（底栏因此
        // 一直走 MD3 Surface 兜底，观感掉一个档次）。
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
                        onNavigateFeedback = { navController.navigate(Routes.FEEDBACK) },
                    )
                }
                composable(Routes.FEEDBACK) {
                    FeedbackScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
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

            // 底部导航栏
            AnimatedVisibility(
                visible = showBottomBar,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(animationSpec = enterSpec) { it },
                exit = slideOutVertically(animationSpec = exitSpec) { it },
            ) {
                BottomNavBar(
                    currentRoute = currentRoute,
                    // 幂等导航：重复 navigate 到当前页不会堆实例（launchSingleTop +
                    // popUpTo 起始目的地）。Kyant0 catalog 的 demo 里 onTabSelected
                    // 写的是 `{ selectedTabIndex = it }` 这种纯 setter；我们必须把
                    // 「先判断再跳」的逻辑留在 Stack 外——组件内部的 LaunchedEffect
                    // 只捕获第一次组合的 lambda，里面读到的 currentRoute 永远是陈旧的，
                    // 于是拖动 pill 回第一个 tab 时它自己把调用吞掉了（表观：拖回去没反应）。
                    onSelect = { route ->
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

            // 应用内更新提示（1.0.15 新增）：有新版本时弹窗，其余状态不显示
            UpdateDialog(viewModel)
            // 公告（1.0.22 新增）：强制更新拦截期间不弹，避免两个不可关闭框叠在一起
            val updateBlocking = updateState is UpdateUiState.Available ||
                updateState is UpdateUiState.Downloading ||
                updateState is UpdateUiState.Ready
            if (!updateBlocking) AnnouncementDialog(viewModel)
        }
    }
}

/**
 * 浮动胶囊底栏（1.0.25 起主窗口直接调用 Kyant0 catalog 组件）。
 *
 * 以前用 M3 的 NavigationBar：直角矩形、贴着屏幕边缘、选中态是方形的
 * secondaryContainer 指示器——形态和配色都偏"系统默认"。1.0.9 起改成自绘
 * 浮动胶囊；1.0.25 起主窗口（[LocalBackdrop] 非 null）直接调用 Kyant0/
 * AndroidLiquidGlass catalog 的 LiquidBottomTabs + LiquidBottomTab（源码
 * vendor，参数未改）：64dp 玻璃胶囊栏 + 滑动玻璃 pill 指示器，支持拖动切换。
 *
 * 悬浮窗 / 无玻璃（backdrop 为 null）保留自绘路径：Surface + 投影 + 描边 +
 * fastSpatial 弹簧滑动指示器，几何参数仍取 design-tokens 的 bottomBar 段，
 * 与 iOS 的 FloatingCapsuleBar 用同一组数字。
 */
@Composable
internal fun BottomNavBar(
    currentRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val cs = MaterialTheme.colorScheme
    val capsule = RoundedCornerShape(DesignTokens.Radius.PILL.dp)
    val selectedIndex = topLevelTabs.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)

    // 1.0.26 修复「底栏 pill 拖回失效」：LiquidBottomTabs 的 selectedTabIndex 是
    // () -> Int 读取器，组件内部用它做 remember 的 key。若每次重组都传新 lambda
    // 实例，key 就每次都变——路由异步更新（navigate 后 currentRoute 下一帧才变）
    // 的窗口内只要发生一次重组，currentIndex 就被重置回路由旧值：pill 弹回原
    // tab、onTabSelected 被重复回调、页面被导航回原页面，用户感知「拖回去卡死，
    // 必须点第 1 个 tab」。按 selectedIndex 的值 remember，保证值不变时实例稳定。
    val selectedTabIndexProvider = remember(selectedIndex) { { selectedIndex } }

    // LiquidBottomTabs 内部把回调捕获在 LaunchedEffect(dampedDragAnimation) 里
    // （effect 的 key 恒不变，回调因此永远是第一次组合那一版）。若回调里读取
    // currentRoute / onSelect 这些 Compose 参数或外部 lambda，读到的就是陈旧值：
    // 拖动 pill 回到第 1 个 tab 时会因为「当前页 == 目标页」而吞掉导航。
    // rememberUpdatedState 让回调每次都读到最新实例——语义对齐上游 demo 的
    // 纯 setter（{ selectedTabIndex = it }），配合 AppNav 里的幂等 navigate。
    val currentOnSelect by rememberUpdatedState(onSelect)

    // 主窗口（backdrop 非 null）走 catalog LiquidBottomTabs（见下方 glassOn 分支）；
    // 悬浮窗 / 无玻璃保留自绘路径：Surface + 投影 + 描边 + 弹簧滑动指示器。
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
            // 主窗口：直接调用 Kyant0/AndroidLiquidGlass catalog 的 LiquidBottomTabs +
            // LiquidBottomTab（源码已原样 vendor，未改任何效果参数）——64dp 胶囊玻璃栏
            // （vibrancy + blur(8dp) + lens(24dp,24dp)），选中态是滑动的玻璃 pill：
            // DampedDragAnimation 弹簧物理 + 按压放大/速度形变 + InteractiveHighlight 高光，
            // 且支持左右拖动切换 tab；tab 图标/文字染色后录进 tabsBackdrop，pill 玻璃透出
            // 选中项内容（官方 demo 同款结构）。高度由组件内部的 64dp 行驱动。
            LiquidBottomTabs(
                selectedTabIndex = selectedTabIndexProvider,
                onTabSelected = { index ->
                    // 拖动 pill 切换：与点击 tab 走同一条幂等导航链路
                    haptics.select()
                    currentOnSelect(topLevelTabs[index].route)
                },
                backdrop = backdrop,
                tabsCount = topLevelTabs.size,
                modifier = Modifier.fillMaxWidth(),
            ) {
                topLevelTabs.forEach { tab ->
                    LiquidBottomTab(
                        onClick = {
                            haptics.select()
                            currentOnSelect(tab.route)
                        },
                    ) {
                        // 图标/文字用统一内容色：选中感由滑动玻璃 pill 提供（官方 demo 同款），
                        // 不再按 selected 换色——pill 会把这层染色后透出来。
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            tint = cs.onSurface,
                            modifier = Modifier.size(DesignTokens.BottomBar.ICON_SIZE.dp),
                        )
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = DesignTokens.BottomBar.LABEL_SIZE.sp,
                            ),
                            color = cs.onSurface,
                        )
                    }
                }
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

// ---------------------------------------------------------------------------
// 背景品牌壁纸：浅蓝绿抽象壁纸 + 暗色罩层 + 右上角公司 logo 角标（1.0.25 起）
//
// 这是 App 根背景层要录进 backdrop 的内容。玻璃元素折射/模糊采样它，所以画面要有
// 亮度层次：原壁纸本身是浅色蓝绿曲线（明暗都有），叠一层竖向黑色罩层把整体压暗
// 到深色主题可用的程度——上（状态栏区）/下（浮动底栏区）加重，中部略轻（中部是
// 玻璃卡片区，卡片自带 tint）。右上角是「分子智能科技」logo 白板角标：选右上是
// 因为各页左上角是返回键 / 标题；Overview / Settings / 详情页顶部留出 72dp 空白
// 就是给它让的位置（角标占 10~60dp）。
// ---------------------------------------------------------------------------
// 1.0.25 起：浅色抽象壁纸满铺 + 竖向黑色罩层（浅壁纸 × 深色主题的必选项）+
// 右上角公司 logo 角标。internal 是为了无头渲染冒烟测试（GlassComponentsRenderTest）
// 能直接驱动这份生产代码路径。
internal fun DrawScope.drawBrandWallpaper(wallpaper: ImageBitmap, logo: ImageBitmap) {
    val w = size.width
    val h = size.height

    // 1. 壁纸满铺（center-crop：按屏幕与壁纸的较大缩放比铺，长边溢出裁掉，不变形）
    val scale = maxOf(w / wallpaper.width, h / wallpaper.height)
    val dw = wallpaper.width * scale
    val dh = wallpaper.height * scale
    drawImage(
        image = wallpaper,
        dstOffset = IntOffset(((w - dw) / 2f).roundToInt(), ((h - dh) / 2f).roundToInt()),
        dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
    )

    // 2. 暗色罩层：浅色壁纸 + 深色主题的必选项，但 1.0.25 压得太暗（用户反馈壁纸
    //    看不清），1.0.26 调轻——上（状态栏区）/下（浮动底栏区）略重，中部最轻
    //    （中部是玻璃卡片区，卡片自带 tint）。亮度回归见 BackdropDrawingRenderTest。
    drawRect(
        brush = Brush.verticalGradient(
            0.00f to Color.Black.copy(alpha = 0.30f),
            0.50f to Color.Black.copy(alpha = 0.16f),
            1.00f to Color.Black.copy(alpha = 0.34f),
        ),
    )

    // 3. 公司 logo 角标：右上角白底板 + 圆角 + 轻投影（假阴影：下移 2dp、外扩 2dp
    //    的黑色圆角板）。尺寸 88×50dp，距顶 10dp、距右 14dp。
    val badgeW = 88f.dp.toPx()
    val badgeH = badgeW * logo.height / logo.width
    val left = w - 14f.dp.toPx() - badgeW
    val top = 10f.dp.toPx()
    val corner = CornerRadius(10f.dp.toPx())
    val shadowInset = 2f.dp.toPx()
    // 投影
    drawPath(
        path = Path().apply {
            addRoundRect(
                RoundRect(
                    left = left - shadowInset,
                    top = top - shadowInset + 2f.dp.toPx(),
                    right = left + badgeW + shadowInset,
                    bottom = top + badgeH + shadowInset + 2f.dp.toPx(),
                    topLeftCornerRadius = corner,
                    topRightCornerRadius = corner,
                    bottomRightCornerRadius = corner,
                    bottomLeftCornerRadius = corner,
                ),
            )
        },
        color = Color.Black.copy(alpha = 0.28f),
    )
    // logo 白板（圆角裁剪）
    clipPath(
        path = Path().apply {
            addRoundRect(
                RoundRect(
                    left = left,
                    top = top,
                    right = left + badgeW,
                    bottom = top + badgeH,
                    topLeftCornerRadius = corner,
                    topRightCornerRadius = corner,
                    bottomRightCornerRadius = corner,
                    bottomLeftCornerRadius = corner,
                ),
            )
        },
    ) {
        drawImage(
            image = logo,
            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
            dstSize = IntSize(badgeW.roundToInt(), badgeH.roundToInt()),
        )
    }
}
