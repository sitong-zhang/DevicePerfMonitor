package com.devperf.monitor.ui.nav

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.LocalContext
import com.devperf.monitor.MonitorApp
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
import com.devperf.monitor.ui.screen.AccountScreen
import com.devperf.monitor.ui.screen.DeepDetailScreen
import com.devperf.monitor.ui.screen.DeepMetricsScreen
import com.devperf.monitor.ui.screen.FeedbackScreen
import com.devperf.monitor.ui.screen.MetricDetailScreen
import com.devperf.monitor.ui.screen.OverviewScreen
import com.devperf.monitor.ui.screen.OverlayCustomizerScreen
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
    const val DEEP = "deep"
    const val SETTINGS = "settings"
    const val ACCOUNT = "account"
    const val FEEDBACK = "feedback"
    const val DETAIL = "detail/{metricId}"
    const val DEEP_DETAIL = "deepDetail/{deepKey}"
    /** 悬浮窗定制器（1.0.44）：自由增删 + 拖拽排序 + 实时预览。 */
    const val OVERLAY_CUSTOMIZER = "overlayCustomizer"

    fun detail(metricId: String) = "detail/$metricId"

    fun deepDetail(key: String) = "deepDetail/$key"
}

private data class TopLevelTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

/**
 * 顶层 tab 互切的方向（Material Motion Shared Axis X）：
 * 目标 tab 在来源 tab 右边 → +1（新页自右滑入、旧页向左让位）；
 * 在左边 → -1（方向相反）。
 *
 * 只要有一端不是顶层 tab（压栈进详情 / 返回），返回 0 —— 交给 push/pop
 * 的视差转场处理，两套动效不混用。
 */
private fun sharedAxisDirection(
    from: String?,
    to: String?,
    tabs: List<TopLevelTab>,
): Int {
    val f = tabs.indexOfFirst { it.route == from }
    val t = tabs.indexOfFirst { it.route == to }
    if (f < 0 || t < 0 || f == t) return 0
    return if (t > f) 1 else -1
}

private val topLevelTabs = listOf(
    TopLevelTab(Routes.OVERVIEW, "总览", Icons.Filled.Home),
    TopLevelTab(Routes.DEEP, "监测", Icons.Filled.Refresh),
    TopLevelTab(Routes.SETTINGS, "设置", Icons.Filled.Settings),
    TopLevelTab(Routes.ACCOUNT, "账号", Icons.Filled.Person),
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
    // 账号状态：禁用时整个 App 锁定为「仅反馈」模式（账号界面/禁用闸门）
    val remote by viewModel.remoteState.collectAsState()
    val disabled = remote?.status == "disabled"
    // 反馈页以浮层形式覆盖在当前页面之上（正常态的账号页 / 禁用态闸门都可触发）
    var showFeedback by remember { mutableStateOf(false) }
    val accountManager = (LocalContext.current.applicationContext as MonitorApp).accountManager

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
    // 缩放 / 淡入淡出的曲线（Float 维度），与位移同一组设计令牌
    val enterSpecFloat = tween<Float>(
        durationMillis = motion.enterDurationMs,
        easing = motion.enter,
    )
    val exitSpecFloat = tween<Float>(
        durationMillis = motion.exitDurationMs,
        easing = motion.exit,
    )
    // 压栈/返回时旧页「让位消失」的位移系数：旧页比新页慢一档，制造纵深层次
    val PARALLAX = 0.35f
    // tab 互切（Shared Axis X）的位移比例：20% 屏宽，Material Motion 推荐值
    val TAB_AXIS_RATIO = 0.20f
    // 底栏显隐用渐显渐隐（Float 曲线），开销远低于整栏纵向滑入
    val fadeSpec = androidx.compose.animation.core.tween<Float>(
        durationMillis = motion.enterDurationMs,
        easing = motion.enter,
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
            if (disabled) {
                // 账号被管理员禁用：锁定为「仅反馈」模式——只显示账号/禁用视图，
                // 其中仅开放「提交反馈」与「退出登录」，其余功能（总览/监测/设置等）全部不可达。
                // 反馈页以独立页面呈现（替换而非覆盖）：showFeedback 为真时整页切到反馈，
                // 下层锁定页随之消失，符合 iOS 式「页面切换即让位」的交互。
                // 1.0.44：切换本身也走与压栈/返回同一套动效（横向滑入 + 放大 + 淡入），
                // 不再硬切——锁定态下同样有完整的「消失逻辑」。
                AnimatedContent(
                    targetState = showFeedback,
                    transitionSpec = {
                        if (targetState) {
                            // 进入反馈：从右滑入 + 放大 + 淡入
                            (
                                slideInHorizontally(animationSpec = enterSpec) { it } +
                                    scaleIn(animationSpec = enterSpecFloat, initialScale = 0.94f) +
                                    fadeIn(animationSpec = enterSpecFloat)
                                ) togetherWith (
                                slideOutHorizontally(animationSpec = exitSpec) {
                                    (it * PARALLAX).roundToInt()
                                } +
                                    scaleOut(animationSpec = exitSpecFloat, targetScale = 0.94f) +
                                    fadeOut(animationSpec = exitSpecFloat)
                                )
                        } else {
                            // 返回锁定页：方向相反
                            (
                                slideInHorizontally(animationSpec = enterSpec) {
                                    -(it * PARALLAX).roundToInt()
                                } +
                                    scaleIn(animationSpec = enterSpecFloat, initialScale = 0.94f) +
                                    fadeIn(animationSpec = enterSpecFloat)
                                ) togetherWith (
                                slideOutHorizontally(animationSpec = exitSpec) { it } +
                                    scaleOut(animationSpec = exitSpecFloat, targetScale = 0.94f) +
                                    fadeOut(animationSpec = exitSpecFloat)
                                )
                        }
                    },
                    label = "lockedFeedback",
                ) { showing ->
                    if (showing) {
                        FeedbackScreen(onBack = { showFeedback = false })
                    } else {
                        AccountScreen(
                            viewModel = viewModel,
                            locked = true,
                            onFeedback = { showFeedback = true },
                            onLogout = { accountManager.logout() },
                        )
                    }
                }
            } else {
            SharedTransitionLayout {
            NavHost(
                navController = navController,
                startDestination = Routes.OVERVIEW,
                // 过渡策略（1.0.44 重写）：全部页面**都有可见的消失/出现动效**，
                // 参考 Material Motion（material.io Shared Axis）+ android/nowinandroid
                // 的 SharedTransitionLayout 结构。之前顶层 tab 互切是 EnterTransition.None
                // 硬切（旧页瞬间蒸发、新页凭空出现），手感断层——本次补上。
                // - 顶层 tab 互切：Shared Axis X —— 按 tab 下标方向横向滑动 + 淡入淡出
                //   （新页从目标方向 20% 位移滑入，旧页反向让位淡出）。位移只取 20%
                //   是 Material 的推荐值：够看清方向，又不会让两页长时间重叠合成
                //   （玻璃 backdrop 同时采样两页的开销因此可控）。
                // - 压入子页：新页从右滑入 + 轻微放大 0.94→1 + 淡入；旧页向左慢一档
                //   让位（视差 PARALLAX + 缩放 + 淡出）——「消失逻辑」全站统一。
                // - 返回：当前页向右滑出 + 缩小 + 淡出消失；下层页从左淡入。
                // - Navigation 2.9.4 默认开启 predictive back：侧滑返回时系统进度实时
                //   驱动 popExit 的位移/缩放，页面跟手（即「交互逻辑」）。
                enterTransition = {
                    val axis = sharedAxisDirection(
                        from = initialState.destination.route,
                        to = targetState.destination.route,
                        tabs = topLevelTabs,
                    )
                    if (axis != 0) {
                        // tab 互切：横向 Shared Axis（20% 位移 + 淡入）
                        slideInHorizontally(animationSpec = enterSpec) {
                            (it * TAB_AXIS_RATIO * axis).roundToInt()
                        } + fadeIn(animationSpec = enterSpecFloat)
                    } else {
                        slideInHorizontally(animationSpec = enterSpec) { it } +
                            scaleIn(animationSpec = enterSpecFloat, initialScale = 0.94f) +
                            fadeIn(animationSpec = enterSpecFloat)
                    }
                },
                exitTransition = {
                    val axis = sharedAxisDirection(
                        from = initialState.destination.route,
                        to = targetState.destination.route,
                        tabs = topLevelTabs,
                    )
                    if (axis != 0) {
                        // tab 互切：旧页反向让位（20% 位移 + 淡出）
                        slideOutHorizontally(animationSpec = exitSpec) {
                            -(it * TAB_AXIS_RATIO * axis).roundToInt()
                        } + fadeOut(animationSpec = exitSpecFloat)
                    } else {
                        slideOutHorizontally(animationSpec = exitSpec) { (it * PARALLAX).roundToInt() } +
                            scaleOut(animationSpec = exitSpecFloat, targetScale = 0.94f) +
                            fadeOut(animationSpec = exitSpecFloat)
                    }
                },
                popEnterTransition = {
                    slideInHorizontally(animationSpec = enterSpec) { -(it * PARALLAX).roundToInt() } +
                        scaleIn(animationSpec = enterSpecFloat, initialScale = 0.94f) +
                        fadeIn(animationSpec = enterSpecFloat)
                },
                popExitTransition = {
                    slideOutHorizontally(animationSpec = exitSpec) { it } +
                        scaleOut(animationSpec = exitSpecFloat, targetScale = 0.94f) +
                        fadeOut(animationSpec = exitSpecFloat)
                },
            ) {
                composable(Routes.OVERVIEW) {
                    OverviewScreen(
                        viewModel = viewModel,
                        onOpenDetail = { id -> navController.navigate(Routes.detail(id.name)) },
                        onOpenDeepDetail = { key -> navController.navigate(Routes.deepDetail(key)) },
                    )
                }
                composable(Routes.DEEP) {
                    DeepMetricsScreen(viewModel = viewModel)
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        viewModel = viewModel,
                        onRequestOverlayPermission = onRequestOverlayPermission,
                        onOpenOverlayCustomizer = {
                            navController.navigate(Routes.OVERLAY_CUSTOMIZER)
                        },
                    )
                }
                composable(Routes.OVERLAY_CUSTOMIZER) {
                    OverlayCustomizerScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.ACCOUNT) {
                    AccountScreen(
                        viewModel = viewModel,
                        locked = false,
                        onFeedback = { navController.navigate(Routes.FEEDBACK) },
                        onLogout = { accountManager.logout() },
                    )
                }
                composable(Routes.FEEDBACK) {
                    FeedbackScreen(onBack = { navController.popBackStack() })
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
                composable(Routes.DEEP_DETAIL) { entry ->
                    DeepDetailScreen(
                        viewModel = viewModel,
                        deepKey = entry.arguments?.getString("deepKey"),
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            }

            // 底部导航栏
            AnimatedVisibility(
                visible = showBottomBar,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(animationSpec = fadeSpec),
                exit = fadeOut(animationSpec = fadeSpec),
            ) {
                BottomNavBar(
                    currentRoute = currentRoute,
                    // 幂等导航：重复 navigate 到当前页不会堆实例（launchSingleTop +
                    // popUpTo 起始目的地）。Kyant0 catalog 的 demo 里 onTabSelected
                    // 写的是 `{ selectedTabIndex = it }` 这种纯 setter；我们必须把
                    // 「先判断再跳」的逻辑留在 Stack 外——组件内部的 LaunchedEffect
                    // 只捕获第一次组合的 lambda，里面读到的 currentRoute 永远是陈旧的，
                    //   于是拖动 pill 回第一个 tab 时它自己把调用吞掉了（表观：拖回去没反应）。
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

            // 账号被管理端标记为「受限」：顶部常驻横幅展示限制说明（1.0.38）
            val restricted = remote?.status == "restricted"
            var restrictionDismissed by remember {
                androidx.compose.runtime.mutableStateOf(false)
            }
            if (restricted && !restrictionDismissed) {
                RestrictionBanner(
                    note = remote?.restrictionNote.orEmpty(),
                    onDismiss = { restrictionDismissed = true },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
            }

        }
    }
}

/**
 * 受限账号顶部横幅（1.0.38 新增）：管理端把账号改为「受限」后 30 秒内出现，
 * 展示限制说明，点 × 收起（本轮会话内不再出现）。
 */
@Composable
private fun RestrictionBanner(note: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .padding(top = 12.dp)
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .shadow(6.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF3A2E10),
        border = BorderStroke(1.dp, Color(0xFFB8860B)),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "⚠️",
                fontSize = 16.sp,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "账号受限",
                    color = Color(0xFFFFC857),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
                if (note.isNotBlank()) {
                    Text(
                        note,
                        color = Color(0xFFE8DCC0),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Text("✕", color = Color(0xFFB8A87E), fontSize = 13.sp)
            }
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
 * 与悬浮窗胶囊栏用同一组数字。
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
    val routeIndex = topLevelTabs.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)

    // 选中态单一真相源：selectedTab 直接持有「绝对索引」，照搬 Kyant0 catalog demo
    // 的写法（GlassComponentsRenderTest 里的官方用法）。点击 / 拖动都先把它同步写成
    // 目标索引——这一步是同步、即时的，pill 立刻跟过去，不再依赖导航把路由带过来
    // 的那一帧延迟。导航交给 onSelect；路由因其它原因变化（如从详情页返回总览）时，
    // 下方 LaunchedEffect(routeIndex) 再把 selectedTab 拉回当前页面，保证
    // 「pill 停留位置 == 当前页面」永远成立（点击能切页、滑动也能切页，且两者都会
    // 把滑块带过去）。
    var selectedTab by remember { mutableIntStateOf(routeIndex) }
    LaunchedEffect(routeIndex) { selectedTab = routeIndex }

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
                    targetValue = selectedTab.toFloat(),
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
                    topLevelTabs.forEachIndexed { i, tab ->
                        val selected = i == selectedTab
                        NavBarItem(
                            tab = tab,
                            selected = selected,
                            onSelect = {
                                if (!selected) {
                                    haptics.select()
                                    selectedTab = i
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
            // selectedTabIndex 直接用 { selectedTab }（点击/拖动都已先写它），pill 跟随。
            LiquidBottomTabs(
                selectedTabIndex = { selectedTab },
                onTabSelected = { index ->
                    // 拖动 pill 落定：写 selectedTab + 走同一条幂等导航链路
                    haptics.select()
                    selectedTab = index
                    currentOnSelect(topLevelTabs[index].route)
                },
                backdrop = backdrop,
                tabsCount = topLevelTabs.size,
                modifier = Modifier.fillMaxWidth(),
            ) {
                topLevelTabs.forEachIndexed { i, tab ->
                    LiquidBottomTab(
                        onClick = {
                            haptics.select()
                            selectedTab = i
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
