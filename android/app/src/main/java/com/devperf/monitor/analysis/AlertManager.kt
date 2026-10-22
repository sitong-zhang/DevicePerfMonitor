package com.devperf.monitor.analysis

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.devperf.monitor.MainActivity
import com.devperf.monitor.R
import com.devperf.monitor.data.CollectionService
import com.devperf.monitor.data.MonitorSettings
import com.devperf.monitor.data.collectors.DeviceSnapshot
import com.devperf.monitor.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 告警机制：
 * - 温度超过用户设定阈值时：悬浮窗变红（[activeAlert]）+ 提醒；
 * - 两种告警方式：通知栏告警（[alertByNotification]）、悬浮弹窗告警（[alertByOverlayPopup]）；
 * - 迟滞策略：超过阈值触发，低于「阈值 - 2℃」才解除，避免临界温度反复抖动；
 * - 冷却策略：同类告警 60s 内不重复通知，防止刷屏；
 * - 通知权限被拒时静默降级（不影响悬浮窗变红），不崩溃。
 */
class AlertManager(private val context: Context) {

    enum class Severity { WARNING, CRITICAL }

    data class Alert(
        val id: String,
        val severity: Severity,
        val title: String,
        val text: String,
        val atMs: Long,
    )

    private val _activeAlert = MutableStateFlow<Alert?>(null)

    /** 当前生效的温度告警（null = 正常）。悬浮窗据此变红/弹窗。 */
    val activeAlert: StateFlow<Alert?> = _activeAlert.asStateFlow()

    private val lastNotifiedAt = mutableMapOf<String, Long>()

    /**
     * 评估当前快照，更新告警状态并（按设置）发送通知。
     * @return 当前生效告警（可能为 null）
     */
    fun evaluate(snapshot: DeviceSnapshot, settings: MonitorSettings): Alert? {
        if (!settings.alertsEnabled) {
            _activeAlert.value = null
            return null
        }

        val now = System.currentTimeMillis()
        val cpuTemp = snapshot.cpu.temperatureC.value
        val batteryTemp = snapshot.battery.temperatureC.value
        val deviceTemp = snapshot.thermal.deviceTempC.value
        val maxTemp = listOfNotNull(cpuTemp, batteryTemp, deviceTemp).maxOrNull()

        val threshold = settings.tempAlertThresholdC
        val current = _activeAlert.value

        val alert = when {
            maxTemp == null -> null
            maxTemp > threshold + 10f -> Alert(
                id = ALERT_TEMP,
                severity = Severity.CRITICAL,
                title = "设备温度危险（${Format.temp(maxTemp)}）",
                text = "温度已超过 ${Format.temp(threshold + 10f)}，请立即停止高负载操作并静置降温。",
                atMs = now,
            )
            maxTemp > threshold -> Alert(
                id = ALERT_TEMP,
                severity = Severity.WARNING,
                title = "设备温度偏高（${Format.temp(maxTemp)}）",
                text = "温度超过设定阈值 ${Format.temp(threshold)}，建议降低亮度、清理后台。",
                atMs = now,
            )
            // 迟滞解除：低于阈值 - 2℃
            current != null && maxTemp < threshold - 2f -> null
            else -> current
        }

        _activeAlert.value = alert
        if (alert != null && settings.alertByNotification) maybeNotify(alert)
        return alert
    }

    private fun maybeNotify(alert: Alert) {
        val now = System.currentTimeMillis()
        val last = lastNotifiedAt[alert.id] ?: 0L
        if (now - last < COOLDOWN_MS) return
        lastNotifiedAt[alert.id] = now

        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Log.i("AlertManager", "通知权限未开启，降级为仅悬浮窗告警")
            return
        }

        val intent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CollectionService.CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(alert.title)
            .setContentText(alert.text)
            .setPriority(if (alert.severity == Severity.CRITICAL) {
                NotificationCompat.PRIORITY_HIGH
            } else {
                NotificationCompat.PRIORITY_DEFAULT
            })
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_ALERT, notification)
        }.onFailure { Log.w("AlertManager", "告警通知发送失败（已降级）", it) }
    }

    /** 悬浮窗弹窗告警的展示开关由设置决定，由 OverlayService 读取 [MonitorSettings.alertByOverlayPopup]。 */
    fun clear() {
        _activeAlert.value = null
    }

    private companion object {
        const val ALERT_TEMP = "temperature"
        const val COOLDOWN_MS = 60_000L
        // 注意：与 OverlayService.NOTIFICATION_ID(1002) 区分，避免互相覆盖
        const val NOTIFICATION_ID_ALERT = 1003
    }
}
