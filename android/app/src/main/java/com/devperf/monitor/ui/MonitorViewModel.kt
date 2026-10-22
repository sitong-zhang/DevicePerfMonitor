package com.devperf.monitor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.devperf.monitor.MonitorApp
import com.devperf.monitor.analysis.AdviceEngine
import com.devperf.monitor.analysis.AlertManager
import com.devperf.monitor.analysis.FocusedHealth
import com.devperf.monitor.analysis.HealthScorer
import com.devperf.monitor.core.CoreMetricId
import com.devperf.monitor.core.Metric
import com.devperf.monitor.core.MetricId
import com.devperf.monitor.core.MetricState
import com.devperf.monitor.core.MetricViewMode
import com.devperf.monitor.data.MetricsRepository
import com.devperf.monitor.data.MonitorSettings
import com.devperf.monitor.data.RefreshMode
import com.devperf.monitor.data.SettingsRepository
import com.devperf.monitor.data.defaultMetricViewModes
import com.devperf.monitor.data.ThemeMode
import com.devperf.monitor.data.collectors.DeviceSnapshot
import com.devperf.monitor.data.storage.HistoryPoint
import com.devperf.monitor.overlay.OverlayPermissions
import com.devperf.monitor.overlay.OverlayService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
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

    /**
     * 悬浮窗权限状态。
     *
     * 这个权限只能在系统设置页里手动开，从设置页返回后必须重新问一次系统——
     * 之前是进页面时取一次就完事，所以出现"系统里明明开了，应用里还显示未授权"。
     */
    private val _overlayGranted = MutableStateFlow(
        OverlayPermissions.canDrawOverlays(getApplication()),
    )
    val overlayGranted: StateFlow<Boolean> = _overlayGranted.asStateFlow()

    /** 从系统设置页返回时调用，重新问一次系统。 */
    fun refreshOverlayPermission() {
        _overlayGranted.value = OverlayPermissions.canDrawOverlays(getApplication())
    }

    init {
        viewModelScope.launch {
            repository.snapshot.collect { s ->
                alertManager.evaluate(s, settings.value)
            }
        }
    }

    // ---------------------------------------------------------------------
    // 四个核心指标 + 综合评判
    // ---------------------------------------------------------------------

    /** 单个核心指标的当前读数。value 为 null 表示这台机器采不到，UI 不展示它。 */
    data class CoreReading(
        val id: CoreMetricId,
        val value: Float?,
        /** 采不到时的内部原因（不直接展示给用户）。 */
        val reason: String?,
    ) {
        val available: Boolean get() = value != null
    }

    data class FocusedState(
        val readings: List<CoreReading>,
        val health: FocusedHealth.Result,
        val updatedAtMs: Long,
    )

    /** 四个核心指标 + 综合评判，随快照实时更新。整个界面只认这一份数据。 */
    val focused: StateFlow<FocusedState> = repository.snapshot
        .map { buildFocused(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, buildFocused(repository.snapshot.value))

    private fun buildFocused(s: DeviceSnapshot): FocusedState {
        val readings = listOf(
            readingOf(CoreMetricId.MEMORY_RATIO, s.memory.usageRatio),
            readingOf(CoreMetricId.DEVICE_TEMP, s.thermal.deviceTempC),
            readingOf(CoreMetricId.REFRESH_RATE, s.display.refreshRateHz),
            readingOf(CoreMetricId.FPS, s.display.fps),
        )
        val byId = readings.associate { it.id to it.value }
        return FocusedState(
            readings = readings,
            health = FocusedHealth.evaluate(
                memoryRatio = byId[CoreMetricId.MEMORY_RATIO],
                deviceTempC = byId[CoreMetricId.DEVICE_TEMP],
                refreshHz = byId[CoreMetricId.REFRESH_RATE],
                fps = byId[CoreMetricId.FPS],
            ),
            updatedAtMs = s.timestampMs,
        )
    }

    private fun readingOf(id: CoreMetricId, metric: Metric<Float>): CoreReading {
        val value = metric.value
        val reason = when (val state = metric.state) {
            is MetricState.Available -> null
            is MetricState.Unavailable -> state.reason
            is MetricState.NeedsPermission -> state.permissionLabel
        }
        return CoreReading(id, value, if (value == null) reason else null)
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

    /**
     * 悬浮窗开关：改设置的同时真的去起/停服务。
     *
     * 之前只改了 DataStore 里的布尔值，没有任何地方去拉起 OverlayService，
     * 所以开关拨到"开"界面上却什么都没出现——这就是"为什么不显示"的直接原因。
     */
    fun setOverlayEnabled(value: Boolean) = viewModelScope.launch {
        settingsRepository.setOverlayEnabled(value)
        val app = getApplication<android.app.Application>()
        if (value) {
            if (OverlayPermissions.canDrawOverlays(app)) {
                OverlayService.start(app)
            }
        } else {
            OverlayService.stop(app)
        }
    }

    /** 切换某个核心指标的可视化模式。 */
    fun setMetricViewMode(id: CoreMetricId, mode: MetricViewMode) = viewModelScope.launch {
        settingsRepository.setMetricViewMode(id, mode)
    }

    /** 某个指标当前的展示模式（缺省回落到默认）。 */
    fun viewModeOf(id: CoreMetricId): MetricViewMode =
        settings.value.metricViewModes[id] ?: defaultMetricViewModes()[id] ?: MetricViewMode.NUMBER

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
