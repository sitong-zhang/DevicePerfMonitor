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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.devperf.monitor.MainActivity
import com.devperf.monitor.MonitorApp
import com.devperf.monitor.R
import com.devperf.monitor.core.Metric
import com.devperf.monitor.data.MetricsRepository
import com.devperf.monitor.data.MonitorSettings
import com.devperf.monitor.data.SettingsRepository
import com.devperf.monitor.ui.theme.LiquidGlassTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
 * 注意：ComposeView 由 Service 托管，没有 ViewTreeLifecycleOwner，
 * 因此统一使用 runtime 的 collectAsState（不用 collectAsStateWithLifecycle）。
 */
class OverlayService : android.app.Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var repository: MetricsRepository
    private lateinit var settingsRepository: SettingsRepository

    private var controller: OverlayController? = null
    private var overlayVisible = false

    override fun onCreate() {
        super.onCreate()
        repository = (application as MonitorApp).metricsRepository
        settingsRepository = (application as MonitorApp).settingsRepository
        createChannel()
        startForegroundSafely(buildNotification())
        // 确保采集在跑（悬浮窗数据源）；幂等
        runCatching { repository.start() }
        controller = OverlayController(this, getSystemService(WindowManager::class.java)) {
            // 垃圾桶关闭：更新设置并停止服务
            serviceScope.launch {
                runCatching { settingsRepository.setOverlayEnabled(false) }
                stopSelf()
            }
        }
        observeSettings()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showOverlay()
            ACTION_HIDE -> controller?.hide()
            ACTION_CLOSE -> {
                serviceScope.launch {
                    runCatching { settingsRepository.setOverlayEnabled(false) }
                    stopSelf()
                }
            }
            else -> showOverlay()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        controller?.detach()
        controller = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------------

    private fun showOverlay() {
        if (!OverlayPermissions.canDrawOverlays(this)) {
            // 降级：无悬浮窗权限时不添加窗口，记录日志后停止（设置页负责引导授权）
            Log.w(TAG, "无悬浮窗权限，悬浮窗不显示（降级）")
            stopSelf()
            return
        }
        if (overlayVisible) return
        controller?.show(createOverlayView())
        overlayVisible = true
    }

    private fun createOverlayView(): ComposeView {
        val controllerRef = controller ?: error("controller 未初始化")
        return ComposeView(this).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
            )
            setContent {
                val settings by settingsRepository.settings.collectAsState(
                    initial = MonitorSettings(),
                )
                val snapshot by repository.snapshot.collectAsState()
                // FPS 500ms 粒度直读（需求规定）；其余指标跟随采集 tick
                val fps by produceState(initialValue = repository.currentFps(), controllerRef) {
                    while (true) {
                        value = repository.currentFps()
                        delay(500L)
                    }
                }
                val state by controllerRef.uiState.collectAsState()
                val data = OverlayData.from(
                    fps = fps?.let { Metric.available(it) } ?: Metric.unavailable("等待采样"),
                    snapshot = snapshot,
                )
                val callbacks = rememberOverlayCallbacks(controllerRef)
                AlertWatcher(
                    data = data,
                    thresholdC = settings.tempAlertThresholdC,
                    controller = controllerRef,
                )

                LiquidGlassTheme(settings.themeMode) {
                    OverlayContent(
                        state = state,
                        data = data,
                        intervalMs = settings.refreshMode.tickMs,
                        callbacks = callbacks,
                    )
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

    /** 告警态观察：机身/CPU/电池温度任一超过阈值 → 悬浮窗整体变红。 */
    @Composable
    private fun AlertWatcher(
        data: OverlayData,
        thresholdC: Float,
        controller: OverlayController,
    ) {
        val temps = listOfNotNull(data.cpuTemp.value, data.batteryTemp.value)
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
                        a.overlayMiniMode == b.overlayMiniMode
                }
                .collect { s ->
                    if (!s.overlayEnabled) {
                        stopSelf()
                    } else {
                        controller?.setDefaultMiniMode(s.overlayMiniMode)
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

        /** 启动悬浮窗（需前台上下文；被系统拒绝时降级为不显示）。 */
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(
                    Intent(context, OverlayService::class.java).setAction(ACTION_SHOW)
                )
            }.onFailure { Log.w(TAG, "前台服务启动被系统拒绝，悬浮窗不显示（降级）", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, OverlayService::class.java)) }
        }
    }
}
