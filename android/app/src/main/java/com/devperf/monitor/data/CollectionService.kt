package com.devperf.monitor.data

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.devperf.monitor.MainActivity
import com.devperf.monitor.MonitorApp
import com.devperf.monitor.R
import com.devperf.monitor.util.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 采集引擎前台服务。
 *
 * 职责：
 * - 以前台服务方式保活采集循环（Android 14+ 声明 specialUse 类型，Play 上架需在
 *   Console 中申报用途，README 已说明）；
 * - 常驻通知展示核心三项（FPS / CPU 温度 / 电池温度），每 2s 更新；
 * - 悬浮窗开启时由 OverlayService 复用同一仓库，不重复采集。
 *
 * 启动/停止均做 runCatching 保护：系统限制前台服务启动时自动降级为普通后台采集，
 * 不崩溃、不闪退。
 */
class CollectionService : android.app.Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var notificationJob: Job? = null

    private lateinit var repository: MetricsRepository

    override fun onCreate() {
        super.onCreate()
        repository = (application as MonitorApp).metricsRepository
        createChannels()
        startForegroundSafely(buildNotification("初始化…", null, null))
        repository.start()
        observeForNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        notificationJob?.cancel()
        repository.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------------

    private fun observeForNotification() {
        notificationJob?.cancel()
        notificationJob = serviceScope.launch {
            repository.snapshot
                .distinctUntilChanged { a, b -> a.timestampMs / 2000L == b.timestampMs / 2000L }
                .collect { snapshot ->
                    val fps = snapshot.display.fps.value
                    val cpuTemp = snapshot.cpu.temperatureC.value
                    val battTemp = snapshot.battery.temperatureC.value
                    // 采不到的段直接不进通知，不留「—」占位
                    val parts = buildList {
                        fps?.let { add("FPS ${Format.value(it, 0)}") }
                        cpuTemp?.let { add("CPU ${Format.temp(it)}") }
                        battTemp?.let { add("电池 ${Format.temp(it)}") }
                    }
                    val text = parts.ifEmpty { listOf("采集中") }.joinToString(" · ")
                    runCatching {
                        NotificationManagerCompat.from(this@CollectionService)
                            .notify(NOTIFICATION_ID, buildNotification("性能监控运行中", text, snapshot.timestampMs))
                    }.onFailure { Log.w(TAG, "通知更新失败（不影响采集）", it) }
                }
        }
    }

    private fun buildNotification(title: String, text: String?, ts: Long?): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_COLLECTOR)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setContentTitle(title)
            .setContentText(text ?: getString(R.string.overlay_notification_text))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(ts != null)
            .setWhen(ts ?: System.currentTimeMillis())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun startForegroundSafely(notification: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            // 极少数系统限制场景：退化为非前台通知，采集继续，不崩溃
            Log.w(TAG, "startForeground 失败，降级为普通通知", it)
            runCatching {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val collector = android.app.NotificationChannel(
                CHANNEL_COLLECTOR,
                getString(R.string.channel_collector_name),
                android.app.NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_collector_desc)
                setShowBadge(false)
            }
            val alert = android.app.NotificationChannel(
                CHANNEL_ALERT,
                getString(R.string.channel_alert_name),
                android.app.NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.channel_alert_desc)
                enableVibration(true)
            }
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannels(listOf(collector, alert))
        }
    }

    companion object {
        private const val TAG = "CollectionService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_COLLECTOR = "collector"
        const val CHANNEL_ALERT = "alert"

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, CollectionService::class.java))
            }.onFailure { Log.w(TAG, "前台服务启动被系统拒绝，改为后台采集", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CollectionService::class.java)) }
        }
    }
}
