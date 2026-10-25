package com.devperf.monitor.overlay

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationCompat
import androidx.compose.runtime.Recomposer
import androidx.core.app.NotificationManagerCompat
import com.devperf.monitor.AppLock
import com.devperf.monitor.MainActivity
import com.devperf.monitor.MonitorApp
import com.devperf.monitor.R
import com.devperf.monitor.analysis.FocusedHealth
import com.devperf.monitor.core.Metric
import com.devperf.monitor.data.MetricsRepository
import com.devperf.monitor.data.MonitorSettings
import com.devperf.monitor.data.SettingsRepository
import com.devperf.monitor.ui.kit.LocalBackdrop
import com.devperf.monitor.ui.kit.appLayerBackdrop
import com.devperf.monitor.ui.kit.rememberAppBackdrop
import com.devperf.monitor.ui.theme.DevicePerfTheme
import com.devperf.monitor.ui.wallpaper.rememberWallpaperImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 全局悬浮窗服务。
 *
 * 生命周期与降级：
 * - 必须在前台上下文启动（MainActivity 开关 / 开机广播豁免）；
 *   从后台启动 FGS 被系统拒绝时自动降级为不显示悬浮窗并 stopSelf（不崩溃）；
 * - 未授予 SYSTEM_ALERT_WINDOW 时不添加窗口，记录日志后停止
 *   （权限引导由主应用设置页负责，避免后台跳 Activity 被系统限制）；
 * - 前台常驻通知提供「显示/隐藏」与「停止」动作，双击隐藏后可从此恢复；
 * - 采集仓库由 [MonitorApp] 持有，悬浮窗复用同一实例，不重复采集。
 *
 * 宿主说明：Compose 1.11 起 ComposeView 挂窗口时必须能从 ViewTree 取到
 * LifecycleOwner + SavedStateRegistryOwner（见 [ServiceViewTreeOwner]），
 * 这里由 Service 自己提供；悬浮窗内容仍用 runtime 的 collectAsState——
 * 合成随窗口 attach/detach 创建与销毁（DisposeOnDetachedFromWindowOrReleasedFromPool），
 * 不走宿主的可见性回调。
 */
class OverlayService : android.app.Service() {

    /**
     * 合成上下文：主线程派发 + 帧时钟。
     *
     * 帧时钟是 Compose 的硬性要求——Recomposer.runRecomposeAndApplyChanges 和
     * LaunchedEffect 里的动画（Animatable.animateTo 走 withFrameNanos）都会读
     * coroutineContext[MonotonicFrameClock]，缺了就抛 IllegalStateException。
     * Activity 路径下由 compose-ui 内部的 AndroidUiDispatcher 提供（internal API），
     * Service 自跑 Recomposer 必须自己补，见 [OverlayFrameClock]。
     */
    private val overlayFrameClock = OverlayFrameClock()
    // 主线程派发 + 帧时钟；类型推断即为 CoroutineContext，不写显式标注
    private val overlayContext = Dispatchers.Main.immediate + overlayFrameClock

    private val serviceScope = CoroutineScope(SupervisorJob() + overlayContext)

    private lateinit var repository: MetricsRepository
    private lateinit var settingsRepository: SettingsRepository

    private var controller: OverlayController? = null

    /**
     * 悬浮窗可见性状态机（纯逻辑，见 [OverlayVisibilityState]）。
     *
     * 1.0.8 用裸 Boolean 记录、hide 时不复位，导致隐藏过一次之后所有
     * ACTION_SHOW 都被吞掉、悬浮窗再也挂不出来。现在收敛到状态机，
     * 由单测钉住「hide → show 能再次挂出」。
     */
    private val overlayVisibility = OverlayVisibilityState()

    /** 设置页默认缩放的上次应用值；用于区分「用户双指缩放」与「设置变更」。 */
    private var lastAppliedScale = Float.NaN

    /**
     * 自己管理的合成器：悬浮窗没有 Activity 宿主，
     * 用 Recomposer 驱动 Compose，替代默认的 ViewTreeLifecycleOwner 路径。
     * effect 上下文必须带上帧时钟，否则界面里的数字补间动画一跑就崩。
     */
    private val overlayRecomposer = Recomposer(overlayContext)
    private var recomposerJob: kotlinx.coroutines.Job? = null

    /**
     * ComposeView 挂窗口所需的 ViewTree 宿主（LifecycleOwner + SavedStateRegistryOwner）。
     * Service 没有 Activity/Fragment 宿主，Compose 1.11 的 resolveComposeViewContext
     * 缺这两个 Owner 会直接抛，见 [ServiceViewTreeOwner]。
     * 生命周期由本 Service 转发；悬浮窗与垃圾桶两个 ComposeView 共用。
     */
    private val viewTreeOwner = ServiceViewTreeOwner()

    override fun onCreate() {
        super.onCreate()
        viewTreeOwner.onCreate()
        repository = (application as MonitorApp).metricsRepository
        settingsRepository = (application as MonitorApp).settingsRepository
        createChannel()
        startForegroundSafely(buildNotification())
        // 确保采集在跑（悬浮窗数据源）；幂等
        runCatching { repository.start() }
        controller = OverlayController(
            this,
            getSystemService(WindowManager::class.java),
            viewTreeOwner,
        ) {
            // 垃圾桶关闭：更新设置并停止服务
            serviceScope.launch {
                runCatching { settingsRepository.setOverlayEnabled(false) }
                stopSelf()
            }
        }
        observeSettings()
        // 驱动悬浮窗的 Compose 合成（没有 Activity 宿主，自己跑）。
        // serviceScope 已带帧时钟，runRecomposeAndApplyChanges 才能取到 MonotonicFrameClock。
        recomposerJob = serviceScope.launch {
            overlayRecomposer.runRecomposeAndApplyChanges()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        viewTreeOwner.onStartCommand()
        when (intent?.action) {
            // 只保证服务在跑、不显示窗口（应用前台时调用，见 ACTION_ENSURE 注释）
            ACTION_ENSURE -> Unit
            ACTION_SHOW -> showOverlay()
            ACTION_HIDE -> hideOverlay()
            ACTION_CLOSE -> {
                serviceScope.launch {
                    runCatching { settingsRepository.setOverlayEnabled(false) }
                    stopSelf()
                }
            }
            // 没有明确指令（典型是 START_STICKY 重建、intent 为 null）：不自作主张
            // 弹窗——此时用户可能正在应用里，突然冒出来很难看。等 MonitorApp 的
            // 前后台命令来决定显示/隐藏。
            else -> Unit
        }
        return START_STICKY
    }

    /**
     * 屏幕配置变化（旋转 / 分屏 / 折叠屏 / 多窗口）。
     * Service 作为 ContextWrapper 会收到系统回调；转发给控制器重新夹取窗口，
     * 修复旋转后悬浮窗跑出屏幕（适配不完整）。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        controller?.onScreenConfigChanged()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        controller?.detach()
        controller = null
        // 服务销毁 → 窗口必然不在了，复位状态（下次重新 start 才能挂出）
        overlayVisibility.reset()
        recomposerJob?.cancel()
        recomposerJob = null
        runCatching { overlayRecomposer.close() }
        // 窗口先摘除（合成随 detach 销毁），再销毁宿主
        viewTreeOwner.onDestroy()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------

    private fun showOverlay() {
        // 强制更新未完成：哪怕有已排队的 SHOW 命令（锁生效之前发出去的）也不挂窗口
        if (!AppLock.overlayAllowed()) {
            Log.i(TAG, "强制更新未完成，拒绝挂出悬浮窗")
            return
        }
        if (!OverlayPermissions.canDrawOverlays(this)) {
            // 降级：无悬浮窗权限时不添加窗口，记录日志后停止（设置页负责引导授权）
            Log.w(TAG, "无悬浮窗权限，悬浮窗不显示（降级）")
            stopSelf()
            return
        }
        // 已在显示 → 不重复 addView（重复添加同一个 view 会抛）。状态机保证
        // 隐藏之后这里能再次返回 true（1.0.8 的 bug 就出在这一行）。
        if (!overlayVisibility.requestShow()) return
        runCatching { controller?.show(createOverlayView()) }
            .onFailure {
                // 挂载失败（窗口令牌异常等）必须复位，否则下次再也挂不出来
                Log.w(TAG, "悬浮窗挂载失败，复位可见性状态", it)
                overlayVisibility.reset()
            }
    }

    /** 隐藏窗口并复位可见性状态，保证之后还能再挂出来。 */
    private fun hideOverlay() {
        controller?.hide()
        overlayVisibility.requestHide()
    }

    private fun createOverlayView(): ComposeView {
        val controllerRef = controller ?: error("controller 未初始化")
        return ComposeView(this).apply {
            // 挂窗口前补 ViewTree 宿主：Compose 1.11 的 resolveComposeViewContext 缺
            // LifecycleOwner / SavedStateRegistryOwner 会直接抛（挂窗口即崩）。
            // 必须在 controller.show() → addView 之前设置。
            viewTreeOwner.attachTo(this)
            // 悬浮窗由 Service 经 WindowManager 添加，没有 Activity/Fragment 宿主，
            // ComposeView 默认会去找 ViewTreeLifecycleOwner，找不到就直接抛
            // IllegalStateException("ViewTreeLifecycleOwner not found")——悬浮窗永远出不来。
            // 这里改成把 Service 自己的 Recomposer 作为父合成上下文（帧时钟见
            // OverlayFrameClock），绕开对宿主的依赖。
            setParentCompositionContext(overlayRecomposer)
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
            )
            setContent {
                val settings by settingsRepository.settings.collectAsState(
                    initial = MonitorSettings(),
                )
                val snapshot by repository.snapshot.collectAsState()
                val state by controllerRef.uiState.collectAsState()
                // 帧率走**全局帧率**（前台应用/游戏的真实帧率，需 Shizuku 或 root，
                // 见 GlobalFpsCollector）。未授权时 Metric 带 NeedsPermission 三态，
                // 悬浮窗卡片行留引导小字（1.0.26 起 FPS 正式进悬浮窗）。
                val data = OverlayData.from(
                    fps = snapshot.display.globalFps,
                    snapshot = snapshot,
                )
                val callbacks = rememberOverlayCallbacks(controllerRef)
                val health = FocusedHealth.evaluate(
                    memoryRatio = data.memoryRatio.value,
                    deviceTempC = data.deviceTemp.value,
                    refreshHz = data.refreshHz.value,
                    fps = data.fps.value,
                )
                AlertWatcher(
                    data = data,
                    thresholdC = settings.tempAlertThresholdC,
                    controller = controllerRef,
                )

                DevicePerfTheme(settings.themeMode) {
                    // 1.0.26 悬浮窗玻璃化：窗口是独立的，跨窗口捕获背后应用没有意义，
                    // 玻璃采样的是「窗口内画的壁纸背景层」——结构照搬 AppNav：
                    // 背景层（layerBackdrop 只录壁纸+罩层）与玻璃内容互为兄弟，
                    // 玻璃元素绝不进录制子树（1.0.13 自指采样崩溃教训）。
                    // 壁纸与主界面同一份设置（1.0.26 起用户可自由切换）。
                    val overlayBackdrop = rememberAppBackdrop()
                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .appLayerBackdrop(overlayBackdrop),
                        ) {
                            val wallpaper = rememberWallpaperImage(settings)
                            Canvas(modifier = Modifier.matchParentSize()) {
                                drawOverlayWallpaper(wallpaper)
                            }
                        }
                        CompositionLocalProvider(LocalBackdrop provides overlayBackdrop) {
                            OverlayContent(
                                state = state,
                                data = data,
                                health = health,
                                intervalMs = settings.refreshMode.tickMs,
                                callbacks = callbacks,
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun rememberOverlayCallbacks(
        controllerRef: OverlayController,
    ): OverlayGestureCallbacks = remember(controllerRef) {
        OverlayGestureCallbacks(
            onDragStart = { controllerRef.onDragStart() },
            onDrag = { controllerRef.onDrag(it) },
            onDragEnd = { controllerRef.onDragEnd() },
            onScale = { controllerRef.onScale(it) },
            onSingleTap = { controllerRef.onSingleTap() },
            onDoubleTap = { controllerRef.onDoubleTap() },
            onTouchLocal = { controllerRef.onTouchLocal(it) },
        )
    }

    /** 告警态观察：机身/CPU/GPU/电池温度任一超过阈值 → 悬浮窗整体变红（1.0.26 扩展热源）。 */
    @Composable
    private fun AlertWatcher(
        data: OverlayData,
        thresholdC: Float,
        controller: OverlayController,
    ) {
        val temps = listOfNotNull(
            data.deviceTemp.value,
            data.cpuTemp.value,
            data.gpuTemp.value,
            data.batteryTemp.value,
        )
        val alert = temps.any { it >= thresholdC }
        LaunchedEffect(alert) { controller.setAlert(alert) }
    }

    // ---------------------------------------------------------------------
    // 设置联动
    // ---------------------------------------------------------------------

    private fun observeSettings() {
        serviceScope.launch {
            settingsRepository.settings
                .distinctUntilChanged { a, b ->
                    a.overlayEnabled == b.overlayEnabled &&
                        a.overlayMiniMode == b.overlayMiniMode &&
                        a.overlayOpacity == b.overlayOpacity &&
                        a.overlayScale == b.overlayScale
                }
                .collect { s ->
                    if (!s.overlayEnabled) {
                        stopSelf()
                    } else {
                        // 窗口透明度（设置页滑杆 0.4~1.0，1.0.8 起真正接线）
                        controller?.setOpacity(s.overlayOpacity)
                        // 默认形态：迷你开关 = 默认纯数字；否则默认完整卡片
                        controller?.setDefaultMode(
                            if (s.overlayMiniMode) {
                                OverlayController.Mode.NUMBERS
                            } else {
                                OverlayController.Mode.CARD
                            }
                        )
                        // 默认缩放：仅在设置值变化时应用（不覆盖用户双指缩放的临时值）
                        if (s.overlayScale != lastAppliedScale) {
                            lastAppliedScale = s.overlayScale
                            controller?.setScaleFromSettings(s.overlayScale)
                        }
                    }
                }
        }
    }

    // ---------------------------------------------------------------------
    // 通知
    // ---------------------------------------------------------------------

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val showIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).setAction(ACTION_SHOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hideIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, OverlayService::class.java).setAction(ACTION_HIDE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val closeIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, OverlayService::class.java).setAction(ACTION_CLOSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_OVERLAY)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.overlay_action_show), showIntent)
            .addAction(0, getString(R.string.overlay_action_hide), hideIntent)
            .addAction(0, getString(R.string.overlay_action_stop), closeIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun startForegroundSafely(notification: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            Log.w(TAG, "startForeground 失败，降级为普通通知", it)
            runCatching {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_OVERLAY,
                getString(R.string.channel_overlay_name),
                android.app.NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_overlay_desc)
                setShowBadge(false)
            }
            getSystemService(android.app.NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "OverlayService"
        const val NOTIFICATION_ID = 1002
        const val CHANNEL_OVERLAY = "overlay"

        const val ACTION_SHOW = "com.devperf.monitor.action.SHOW_OVERLAY"
        const val ACTION_HIDE = "com.devperf.monitor.action.HIDE_OVERLAY"
        const val ACTION_CLOSE = "com.devperf.monitor.action.CLOSE_OVERLAY"
        const val ACTION_ENSURE = "com.devperf.monitor.action.ENSURE_OVERLAY"

        /**
         * 显示悬浮窗（需前台上下文；被系统拒绝时降级为不显示）。
         *
         * 服务已在运行时这只是条命令，不受 Android 12+「后台启动前台服务」限制。
         */
        fun start(context: Context) {
            // 强制更新没走完：悬浮窗是独立 Window，主界面弹窗盖不到它，
            // 这里在拉起入口拦住，否则会出现「App 锁死但悬浮窗照常看数据」
            if (!AppLock.overlayAllowed()) {
                Log.i(TAG, "强制更新未完成，悬浮窗保持停用")
                return
            }
            runCatching {
                context.startForegroundService(
                    Intent(context, OverlayService::class.java).setAction(ACTION_SHOW)
                )
            }.onFailure { Log.w(TAG, "前台服务启动被系统拒绝，悬浮窗不显示（降级）", it) }
        }

        /**
         * 只保证服务在跑，不显示窗口。
         *
         * 必须在**应用前台**调用（Android 12+ 禁止后台新建前台服务）。这样退到
         * 后台时 [start] 只是给已运行的 FGS 发命令，不会被系统拒绝——
         * 这是「退出应用自动挂出」在 Android 12+ 上能真正生效的前提。
         */
        fun ensure(context: Context) {
            // 同上：强制更新期间不预拉起服务（预拉起只为「退后台自动挂出」服务）
            if (!AppLock.overlayAllowed()) {
                Log.i(TAG, "强制更新未完成，不预拉起悬浮窗服务")
                return
            }
            runCatching {
                context.startForegroundService(
                    Intent(context, OverlayService::class.java).setAction(ACTION_ENSURE)
                )
            }.onFailure { Log.w(TAG, "前台服务启动被系统拒绝（降级）", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, OverlayService::class.java)) }
        }

        /** 只隐藏窗口，服务保留（通知里还能再叫出来）。回前台时用这个。 */
        fun hide(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, OverlayService::class.java).setAction(ACTION_HIDE),
                )
            }.onFailure { Log.w(TAG, "隐藏悬浮窗失败", it) }
        }
    }
}
