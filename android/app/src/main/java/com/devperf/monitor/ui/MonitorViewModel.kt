package com.devperf.monitor.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.devperf.monitor.MonitorApp
import com.devperf.monitor.analysis.AlertManager
import com.devperf.monitor.analysis.FocusedHealth
import com.devperf.monitor.core.CoreMetricId
import com.devperf.monitor.core.DeepSnapshot
import com.devperf.monitor.core.Metric
import com.devperf.monitor.core.MetricId
import com.devperf.monitor.core.MetricState
import com.devperf.monitor.core.MetricViewMode
import com.devperf.monitor.data.MetricsRepository
import com.devperf.monitor.data.MonitorSettings
import com.devperf.monitor.data.RefreshMode
import com.devperf.monitor.BuildConfig
import com.devperf.monitor.data.AppRelease
import com.devperf.monitor.data.FeedbackRepository
import com.devperf.monitor.data.SettingsRepository
import com.devperf.monitor.data.AppAnnouncement
import com.devperf.monitor.data.UpdateRepository
import com.devperf.monitor.data.defaultMetricViewModes
import com.devperf.monitor.data.isNewer
import com.devperf.monitor.data.ThemeMode
import com.devperf.monitor.data.WallpaperId
import com.devperf.monitor.ui.AnnouncementUiState
import java.io.File
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
 * - 综合评判在快照变化时重算（纯本地算法，成本可忽略）；
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

    /** 深度监测快照（CPU 调度器 / PMU / 中断 / 电源轨 / UFS / 网络细分 / 进程扩展等）。 */
    val deep: StateFlow<DeepSnapshot> = repository.deep

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
                // 管理端禁用「告警提醒」时不再评估（不弹通知）
                if (!app.syncManager.isFeatureDisabled("alert")) {
                    alertManager.evaluate(s, settings.value)
                }
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
        /** 有值时也可能有的补充说明（如"当前为电池温度"），UI 以小字展示。 */
        val note: String? = null,
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
            // 温度：zone 不可读（部分 ROM 限制）降级为电池温度时透明标注，
            // 不冒充 zone 读数（电池温度通常低 5~15℃）
            readingOf(CoreMetricId.DEVICE_TEMP, s.thermal.deviceTempC).let { r ->
                if (r.available && s.thermal.source == com.devperf.monitor.data.collectors.ThermalCollector.Source.BATTERY) {
                    r.copy(note = "机身温区不可读，当前为电池温度")
                } else r
            },
            readingOf(CoreMetricId.REFRESH_RATE, s.display.refreshRateHz),
            fpsReading(s),
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

    /**
     * 帧率读数：**优先全局帧率**（前台应用/游戏的真实帧率，需 Shizuku 或 root），
     * 未授权时回落本 App 帧率（前台打开本应用时有效，悬浮窗场景本 App 无绘制、
     * 采不到，此时带上 Shizuku 三态引导用户授权）。
     *
     * 1.0.9 及以前这里直接用本 App 帧率：用户退出应用后悬浮窗永远没有帧率
     * （本 App 不绘制），看起来像"帧率根本没做"。
     */
    private fun fpsReading(s: DeviceSnapshot): CoreReading {
        val global = s.display.globalFps
        if (global.value != null) return readingOf(CoreMetricId.FPS, global)
        val own = s.display.fps
        if (own.value != null) return readingOf(CoreMetricId.FPS, own)
        // 都采不到：沿用 globalFps 的三态（NeedsPermission("Shizuku 授权") /
        // Unavailable），让 UI 显示引导而不是静默消失
        return readingOf(CoreMetricId.FPS, global)
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
    // 历史
    // ---------------------------------------------------------------------

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

    /** 切换某个核心指标详情页的回看窗口（秒）。 */
    fun setMetricWindow(id: CoreMetricId, windowSec: Int) = viewModelScope.launch {
        settingsRepository.setMetricWindow(id, windowSec)
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

    /** 切换内置壁纸（1.0.26）。 */
    fun setWallpaper(id: WallpaperId) = viewModelScope.launch {
        settingsRepository.setWallpaper(id)
    }

    /** 应用相册选图作为自定义壁纸（1.0.26；path 为已复制到 filesDir 的文件）。 */
    fun setWallpaperCustom(path: String) = viewModelScope.launch {
        settingsRepository.setWallpaperCustom(path)
    }

    // ---------------------------------------------------------------------
    // 意见反馈（1.0.31 改回邮箱直达）：用户拍板不要独立反馈页/提交表单，
    // 设置页「意见反馈」行点击后直接拉起邮件 App——收件人、主题（版本+机型）、
    // 正文（待填模板+设备信息）全部预填，用户补两句话点发送即达。
    // 纯本地拼装，无网络请求。
    // ---------------------------------------------------------------------

    private val feedbackRepo = FeedbackRepository()

    /** 反馈收件邮箱（构建注入，缺省内置）。 */
    val feedbackEmail: String get() = feedbackRepo.targetEmail

    /** 拼一条正文预填好的 mailto 链接（设置页意见反馈入口用）。 */
    fun feedbackMailto(): String =
        feedbackRepo.buildMailto(FEEDBACK_CONTENT_PROMPT, "")

    /** 当前采集间隔（数值动画打断策略用）。 */
    fun tickMs(): Long = settings.value.refreshMode.tickMs

    /** 管理端是否已禁用某功能（悬浮窗 / 同步 / 公告 / 反馈 / 自动更新等，实时生效）。 */
    fun isFeatureDisabled(feature: String): Boolean = app.syncManager.isFeatureDisabled(feature)

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

    // ---------------------------------------------------------------------
    // 应用内版本检查与更新（1.0.15 新增，唯一的联网路径）
    // ---------------------------------------------------------------------

    private val updateRepo = UpdateRepository(getApplication())
    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState = _updateState.asStateFlow()
    /** 本次进程生命周期内已处理过（展示/忽略/稍后），不再重复弹。 */
    private var updateSessionHandled = false

    /**
     * 打开 App 时调用：查 GitHub Releases 最新版。**强制更新**——只要发现比当前
     * 更新的版本，就弹「不可关闭」的更新提示，旧版本任何功能都无法使用，必须更新。
     * 仅当「能连上 GitHub 且确认有更新」时才拦截；网络失败（查不到）按无更新处理，
     * 绝不锁死离线用户。每次进程启动都重新检查，因此「忽略版本」不再持久化。
     */
    fun checkForUpdate() {
        // 管理端禁用「自动更新」时不检查，用户停留在当前版本
        if (isFeatureDisabled("auto_update")) return
        if (updateSessionHandled) return
        when (_updateState.value) {
            // 已经确认有更新 / 正在下载 / 已下载完等待安装：都不重查。
            // 特别是不能漏掉 Ready——用户点「安装」跳去系统授权页/安装器再返回时
            // 会走 onResume，这里如果重查会把「下载完成」打回「必须更新」，逼用户
            // 重新下载（1.0.17 真机踩到的死循环，1.0.18 修复）。
            is UpdateUiState.Available,
            is UpdateUiState.Downloading,
            is UpdateUiState.Ready,
            -> return
            else -> {}
        }
        viewModelScope.launch {
            _updateState.value = UpdateUiState.Checking
            val current = BuildConfig.VERSION_NAME
            val release = runCatching { updateRepo.latestRelease() }.getOrNull()
            when {
                release == null -> _updateState.value = UpdateUiState.Error
                isNewer(release.version, current) -> {
                    // 有更新：弹不可关闭的强制更新框，不写忽略、不留「稍后」。
                    // 同时上应用级软锁并停掉悬浮窗——它跑在独立 Window 上，
                    // Activity 的弹窗盖不到，不锁会出现「App 锁死但悬浮窗还能看」。
                    com.devperf.monitor.AppLock.updatePending = true
                    runCatching { OverlayService.stop(getApplication()) }
                    _updateState.value = UpdateUiState.Available(release)
                }
                else -> {
                    updateSessionHandled = true
                    _updateState.value = UpdateUiState.NoUpdate
                }
            }
        }
    }

    /**
     * 检查更新连不上服务器时调用（[Error] 态的「关闭」）。仅把本次提示关闭、允许
     * 暂时继续使用，但**不**置 updateSessionHandled，下次打开 App 仍会重新检查——
     * 这样既能让离线用户用旧版，又不会让「其实有更新但刚好断网」的用户永久绕过。
     */
    fun dismissForOffline() {
        _updateState.value = UpdateUiState.NoUpdate
    }

    /** 开始下载 APK（对应 flavor）。下载完成进入 Ready 态，由 UI 触发安装。 */
    fun startUpdateDownload() {
        val release = (_updateState.value as? UpdateUiState.Available)?.release ?: return
        val apkUrl = release.apkUrl ?: run {
            _updateState.value = UpdateUiState.Error
            return
        }
        viewModelScope.launch {
            _updateState.value = UpdateUiState.Downloading(0)
            val file = updateRepo.updatesDir().resolve("update-${release.version}.apk")
            val ok = runCatching {
                updateRepo.downloadApk(apkUrl, file) { p ->
                    _updateState.value = UpdateUiState.Downloading(p)
                }
                file.exists() && file.length() > 0
            }.getOrElse { false }
            _updateState.value = if (ok) UpdateUiState.Ready(file) else UpdateUiState.Error
        }
    }

    // ---------------------------------------------------------------------
    // 公告：来自服务端（管理端单独/全局公告），随 SyncManager 回拉实时更新
    // ---------------------------------------------------------------------

    /** 服务端下发的管控状态（状态/开关/公告），供 UI 层（受限横幅等）消费。 */
    val remoteState get() = app.syncManager.remoteState

    private val _announcementState =
        MutableStateFlow<AnnouncementUiState>(AnnouncementUiState.Idle)
    val announcementState = _announcementState.asStateFlow()

    init {
        // 服务端状态每次回拉到达后检查公告：管理端一发公告，30 秒内弹窗
        viewModelScope.launch {
            app.syncManager.remoteState.collect { state ->
                if (state != null) checkAnnouncement()
            }
        }
    }

    /**
     * 检查服务端最新公告（onResume 时也会调用）。若 id 与本地已读不同则弹「必须读完」。
     * 管理端禁用「公告展示」时不弹。
     */
    fun checkAnnouncement() {
        // 管理端禁用「公告展示」时不弹窗
        if (isFeatureDisabled("announcement")) return
        if (_announcementState.value is AnnouncementUiState.Show) return
        val latest = app.syncManager.remoteState.value?.announcements
            ?.maxByOrNull { it.createdAt } ?: return
        if (latest.id.isNotEmpty() && latest.id != lastSeenAnnouncementId()) {
            _announcementState.value = AnnouncementUiState.Show(
                AppAnnouncement(id = latest.id, title = latest.title, content = latest.content),
            )
        }
    }

    /** 用户读完点确认：记已读 id 并收起。 */
    fun markAnnouncementRead(id: String) {
        saveLastSeenAnnouncementId(id)
        _announcementState.value = AnnouncementUiState.Idle
    }

    private fun announcementPrefs() =
        getApplication<Application>().getSharedPreferences("announcement", Context.MODE_PRIVATE)

    private fun lastSeenAnnouncementId(): String =
        announcementPrefs().getString("last_seen_id", "") ?: ""

    private fun saveLastSeenAnnouncementId(id: String) {
        announcementPrefs().edit().putString("last_seen_id", id).apply()
    }

    companion object {
        /** Shizuku 授权请求码（onRequestPermissionsResult 回调用）。 */
        const val REQUEST_CODE_SHIZUKU = 0x5A1

        /** 意见反馈正文的待填提示（mailto 预填，用户在邮件里补写）。 */
        const val FEEDBACK_CONTENT_PROMPT = "（请在这里描述你遇到的问题或建议）"
    }
}
