package com.devperf.monitor.account

import android.content.Context
import android.os.Build
import android.util.Log
import com.devperf.monitor.BuildConfig
import com.devperf.monitor.data.MonitorSettings
import com.devperf.monitor.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 数据同步：用户数据按小时自动上传 + 关键操作实时上报 + 服务端状态回拉。
 *
 * 三个职责：
 * - [start]：登录后启动常驻循环，每小时向 /api/sync 上报一次设备快照
 *   （机型 / 系统 / App 版本 / 悬浮窗 / 壁纸 / 主题等当前状态）；
 * - [reportEvent]：任何账号变动（购买、功能激活、设置修改等）实时上报 /api/event；
 * - [refreshMe]：从 /api/me 拉管理端下发的状态（禁用功能 / 单独 / 全局公告），
 *   UI 层据此禁用对应功能并展示公告。
 */
class SyncManager(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val accountManager: AccountManager,
) {

    /** 服务端下发的用户侧状态（禁用功能 + 公告），未登录或未拉取时为 null。 */
    data class RemoteState(
        val status: String,
        val disabledFeatures: List<String>,
        val announcements: List<Announcement>,
    ) {
        data class Announcement(val title: String, val content: String, val createdAt: Long)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _remoteState = MutableStateFlow<RemoteState?>(null)
    val remoteState: StateFlow<RemoteState?> = _remoteState.asStateFlow()

    private var hourlyJob: Job? = null
    private var settingsWatcherJob: Job? = null

    /** 登录成功后调用：启动每小时同步 + 设置变更实时上报。幂等。 */
    fun start() {
        if (hourlyJob?.isActive == true) return
        hourlyJob = scope.launch {
            refreshMe()
            while (isActive) {
                syncNow()
                delay(SYNC_INTERVAL_MS)
            }
        }
        watchSettingsChanges()
    }

    /** 登出后调用：停循环、清远端状态。 */
    fun stop() {
        hourlyJob?.cancel(); hourlyJob = null
        settingsWatcherJob?.cancel(); settingsWatcherJob = null
        _remoteState.value = null
    }

    // ------------------------------------------------------------------
    // 每小时快照同步
    // ------------------------------------------------------------------

    /** 立即上报一次设备快照（也在前台恢复等场景可手动触发）。 */
    suspend fun syncNow() {
        val session = accountManager.session.value ?: return
        withContext(Dispatchers.IO) {
            val settings = runCatching { settingsRepository.settings.first() }.getOrNull()
            val res = ApiClientHolder.api.post(
                "/api/sync",
                JSONObject().put("payload", buildPayload(settings)),
                mapOf("Authorization" to "Bearer ${session.token}"),
            )
            if (!res.ok) Log.w(TAG, "每小时同步失败：${res.msg}")
        }
    }

    private fun buildPayload(settings: MonitorSettings?): JSONObject {
        return JSONObject()
            .put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("os_version", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("overlay_enabled", settings?.overlayEnabled)
            .put("theme", settings?.themeMode?.name)
            .put("wallpaper", settings?.wallpaper?.name)
            .put("collect_in_background", settings?.collectInBackground)
            .put("alerts_enabled", settings?.alertsEnabled)
            .put("refresh_mode", settings?.refreshMode?.name)
            .put("synced_at", System.currentTimeMillis())
    }

    // ------------------------------------------------------------------
    // 实时事件上报（购买 / 功能激活 / 设置修改等任何账号变动）
    // ------------------------------------------------------------------

    /** 实时上报一次用户操作；失败静默（下次每小时同步会补齐状态）。 */
    fun reportEvent(type: String, detail: String) {
        val session = accountManager.session.value ?: return
        scope.launch {
            val res = ApiClientHolder.api.post(
                "/api/event",
                JSONObject().put("type", type).put("detail", detail),
                mapOf("Authorization" to "Bearer ${session.token}"),
            )
            if (!res.ok) Log.w(TAG, "实时事件上报失败 $type：${res.msg}")
        }
    }

    // ------------------------------------------------------------------
    // 服务端状态回拉（禁用功能 / 公告）
    // ------------------------------------------------------------------

    /** 从 /api/me 拉取管理端下发的状态；token 失效时回登录页。 */
    suspend fun refreshMe() {
        val session = accountManager.session.value ?: return
        withContext(Dispatchers.IO) {
            val res = ApiClientHolder.api.get(
                "/api/me?token=${session.token}",
            )
            val data = res.data
            if (!res.ok || data == null) {
                // 401 / token 失效：清会话回登录页
                if (res.msg?.contains("登录", ignoreCase = true) == true ||
                    res.msg?.contains("token", ignoreCase = true) == true
                ) {
                    accountManager.logout()
                }
                return@withContext
            }
            val anns = data.optJSONArray("announcements")
            _remoteState.value = RemoteState(
                status = data.optString("status", "active"),
                disabledFeatures = List(data.optJSONArray("disabled_features")?.length() ?: 0) { i ->
                    data.optJSONArray("disabled_features")?.optString(i).orEmpty()
                },
                announcements = List(anns?.length() ?: 0) { i ->
                    val o = anns?.optJSONObject(i) ?: JSONObject()
                    RemoteState.Announcement(
                        title = o.optString("title"),
                        content = o.optString("content"),
                        createdAt = o.optLong("created_at"),
                    )
                },
            )
        }
    }

    /** 某功能是否被管理端禁用。feature 名与后端 disable_feature 传入一致。 */
    fun isFeatureDisabled(feature: String): Boolean =
        _remoteState.value?.disabledFeatures?.contains(feature) == true

    // ------------------------------------------------------------------
    // 设置变更 → 实时上报（账号变动实时同步的自动接线）
    // ------------------------------------------------------------------

    private fun watchSettingsChanges() {
        if (settingsWatcherJob?.isActive == true) return
        settingsWatcherJob = scope.launch {
            runCatching {
                settingsRepository.settings
                    .map { it to mapOf(
                        "overlay" to it.overlayEnabled,
                        "theme" to it.themeMode.name,
                        "wallpaper" to it.wallpaper.name,
                        "background" to it.collectInBackground,
                        "alerts" to it.alertsEnabled,
                        "refresh" to it.refreshMode.name,
                    ) }
                    .distinctUntilChanged()
                    .collect { (settings, snapshot) ->
                        val prev = lastSnapshot
                        if (prev != null && snapshot != prev) {
                            val diff = snapshot.filterKeys { k -> snapshot[k] != prev[k] }
                            reportEvent(
                                "setting_change",
                                diff.entries.joinToString("；") { (k, v) ->
                                    "$k → ${readable(k, settings)}"
                                },
                            )
                        }
                        lastSnapshot = snapshot
                    }
            }.onFailure { Log.w(TAG, "设置监听失败", it) }
        }
    }

    private var lastSnapshot: Map<String, Any?>? = null

    private fun readable(key: String, s: MonitorSettings): String = when (key) {
        "overlay" -> if (s.overlayEnabled) "开" else "关"
        "theme" -> s.themeMode.name
        "wallpaper" -> s.wallpaper.name
        "background" -> if (s.collectInBackground) "开" else "关"
        "alerts" -> if (s.alertsEnabled) "开" else "关"
        "refresh" -> s.refreshMode.name
        else -> ""
    }

    companion object {
        private const val TAG = "SyncManager"

        /** 每小时自动同步一次（需求：按小时自动上传）。 */
        const val SYNC_INTERVAL_MS: Long = 60 * 60 * 1000L
    }
}
