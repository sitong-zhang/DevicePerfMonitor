package com.devperf.monitor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.devperf.monitor.MonitorApp
import com.devperf.monitor.analysis.AdviceEngine
import com.devperf.monitor.analysis.AlertManager
import com.devperf.monitor.analysis.HealthScorer
import com.devperf.monitor.core.MetricId
import com.devperf.monitor.data.MetricsRepository
import com.devperf.monitor.data.MonitorSettings
import com.devperf.monitor.data.RefreshMode
import com.devperf.monitor.data.SettingsRepository
import com.devperf.monitor.data.ThemeMode
import com.devperf.monitor.data.collectors.DeviceSnapshot
import com.devperf.monitor.data.storage.HistoryPoint
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 主界面 ViewModel：采集快照 / 设置 / 分析结果的唯一数据源。
 *
 * - 健康分与建议在快照变化时重算（纯本地算法，成本可忽略）；
 * - 告警评估随快照推进（AlertManager 内部有迟滞与冷却）；
 * - 历史读取直连 [MetricsRepository.history]（内存环形缓冲，无 IO）。
 */
class MonitorViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MonitorApp
    private val settingsRepository: SettingsRepository = app.settingsRepository
    private val repository: MetricsRepository = app.metricsRepository

    /** 告警管理器（通知/悬浮变红双通道，权限拒降级）。 */
    val alertManager = AlertManager(application)

    val settings: StateFlow<MonitorSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, MonitorSettings())

    val snapshot: StateFlow<DeviceSnapshot> = repository.snapshot

    val alert: StateFlow<AlertManager.Alert?> = alertManager.activeAlert

    init {
        viewModelScope.launch {
            repository.snapshot.collect { s ->
                alertManager.evaluate(s, settings.value)
            }
        }
    }

    // ---------------------------------------------------------------------
    // 分析
    // ---------------------------------------------------------------------

    fun health(): HealthScorer.HealthResult =
        HealthScorer.score(snapshot.value)

    fun advice(): List<AdviceEngine.Advice> =
        AdviceEngine.analyze(snapshot.value, settings.value, repository.history)

    /** 图表历史（窗口秒）。 */
    fun history(id: MetricId, windowSeconds: Int): List<HistoryPoint> =
        repository.history.series(id, windowSeconds)

    /** 窗口内峰值统计（数据不足时返回 null，由 UI 显示"数据不足"）。 */
    fun peakStats(id: MetricId, windowSeconds: Int): PeakStats? {
        val points = history(id, windowSeconds)
        if (points.size < 2) return null
        return PeakStats(
            max = points.maxOf { it.value },
            min = points.minOf { it.value },
            avg = points.map { it.value }.average().toFloat(),
            latest = points.last().value,
            samples = points.size,
        )
    }

    data class PeakStats(
        val max: Float,
        val min: Float,
        val avg: Float,
        val latest: Float,
        val samples: Int,
    )

    // ---------------------------------------------------------------------
    // 设置操作
    // ---------------------------------------------------------------------

    fun setOverlayEnabled(value: Boolean) = viewModelScope.launch {
        settingsRepository.setOverlayEnabled(value)
    }

    fun setCollectInBackground(value: Boolean) = viewModelScope.launch {
        settingsRepository.setCollectInBackground(value)
    }

    fun setOverlayMiniMode(value: Boolean) = viewModelScope.launch {
        settingsRepository.setOverlayMiniMode(value)
    }

    fun setSnapToEdge(value: Boolean) = viewModelScope.launch {
        settingsRepository.setSnapToEdge(value)
    }

    fun setOverlayOpacity(value: Float) = viewModelScope.launch {
        settingsRepository.setOverlayOpacity(value)
    }

    fun setOverlayScale(value: Float) = viewModelScope.launch {
        settingsRepository.setOverlayScale(value)
    }

    fun setRefreshMode(mode: RefreshMode) = viewModelScope.launch {
        settingsRepository.setRefreshMode(mode)
    }

    fun setTempAlertThreshold(value: Float) = viewModelScope.launch {
        settingsRepository.setTempAlertThreshold(value)
    }

    fun setAlertsEnabled(value: Boolean) = viewModelScope.launch {
        settingsRepository.setAlertsEnabled(value)
    }

    fun setAlertByNotification(value: Boolean) = viewModelScope.launch {
        settingsRepository.setAlertByNotification(value)
    }

    fun setAlertByOverlayPopup(value: Boolean) = viewModelScope.launch {
        settingsRepository.setAlertByOverlayPopup(value)
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch {
        settingsRepository.setThemeMode(mode)
    }

    /** 当前采集间隔（数值动画打断策略用）。 */
    fun tickMs(): Long = settings.value.refreshMode.tickMs

    /** 当前特权通道（设置页展示：root / Shizuku / 无）。 */
    fun snapshotShellBackend(): com.devperf.monitor.data.collectors.PrivilegedShell.Backend =
        repository.shell.backend

    /** 请求 Shizuku/root 提权（授权成功后刷新三态）。 */
    fun requestPrivilege() {
        val shell = repository.shell
        if (shell.backend == com.devperf.monitor.data.collectors.PrivilegedShell.Backend.NONE) {
            shell.requestShizukuPermission(REQUEST_CODE_SHIZUKU)
        }
        repository.refreshPrivilegedState()
    }

    companion object {
        /** Shizuku 授权请求码（onRequestPermissionsResult 回调用）。 */
        const val REQUEST_CODE_SHIZUKU = 0x5A1
    }
}
