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
 * 数据同步：管控状态实时回拉（30 秒）+ 用户数据按小时上传 + 关键操作实时上报。
 *
 * 三个职责：
 * - [start]：登录后启动常驻循环——每 30 秒拉一次 /api/me（管理端改状态/开关/发公告
 *   最迟 30 秒生效），每小时的 /api/sync 上报设备快照；
 * - [reportEvent]：任何账号变动（购买、功能激活、设置修改等）实时上报 /api/event；
 * - [refreshMe]：从 /api/me 拉管理端下发的状态（账号状态 / 禁用功能 / 公告），
 *   UI 层据此禁用对应功能、展示公告与受限横幅；账号被禁用时自动登出。
 */
class SyncManager(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val accountManager: AccountManager,
) {

    /** 服务端下发的用户侧状态（禁用功能 + 公告），未登录或未拉取时为 null。 */
    data class RemoteState(
        val status: String,
        val restrictionNote: String,
        val disabledFeatures: List<String>,
        val announcements: List<Announcement>,
    ) {
        data class Announcement(
            val id: String,
            val title: String,
            val content: String,
            val createdAt: Long,
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _remoteState = MutableStateFlow<RemoteState?>(null)
    val remoteState: StateFlow<RemoteState?> = _remoteState.asStateFlow()

    private var pollJob: Job? = null
    private var snapshotJob: Job? = null
    private var settingsWatcherJob: Job? = null

    /** 登录成功后调用：启动管控状态轮询 + 快照上报。幂等。 */
    fun start() {
        if (pollJob?.isActive == true) return
        // 管控回拉：每 30 秒拉一次 /api/me（轻量 GET），管理端改状态/发公告秒级到达
        pollJob = scope.launch {
            refreshMe()
            while (isActive) {
                delay(STATE_POLL_MS)
                refreshMe()
            }
        }
        // 设备快照上报：保持按小时
        snapshotJob = scope.launch {
            while (isActive) {
                syncNow()
                delay(SYNC_INTERVAL_MS)
            }
        }
        watchSettingsChanges()
    }

    /** 登出后调用：停循环、清远端状态。 */
    fun stop() {
        pollJob?.cancel(); pollJob = null
        snapshotJob?.cancel(); snapshotJob = null
        settingsWatcherJob?.cancel(); settingsWatcherJob = null
        _remoteState.value = null
    }

    // ------------------------------------------------------------------
    // 每小时快照同步
    // ------------------------------------------------------------------

    /** 立即上报一次设备快照（也在前台恢复等场景可手动触发）。 */
    suspend fun syncNow() {
        // 管理端禁用「数据同步」：不上报（后端也会 403 兜底）
        if (isFeatureDisabled("sync")) return
        val session = accountManager.session.value ?: return
        withContext(Dispatchers.IO) {
            val settings = runCatching { settingsRepository.settings.first() }.getOrNull()
            val res = ApiClientHolder.api.post(
                "/api/sync",
                JSONObject().put("payload", buildPayload(settings)),
                mapOf("Authorization" to "Bearer ${session.token}"),
            )
            if (!res.ok) {
                Log.w(TAG, "每小时同步失败：${res.msg}")
                // 403 = 管理端刚禁用了账号或同步功能：立即回拉最新状态并执行
                if (res.code == 403) refreshMe()
            } else {
                // 同步响应本身携带最新管控状态，顺带回拉一次
                applyState(res.data)
            }
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
        // 管理端禁用「数据同步」：事件也不上报
        if (isFeatureDisabled("sync")) return
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

    /** 从 /api/me 拉取管理端下发的状态；账号被禁用时登出。 */
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
            applyState(data)
            // 账号被禁用：立即登出（登录接口同样会拒绝，这里兜底已登录场景）
            if (data.optString("status") == "disabled") {
                Log.w(TAG, "账号已被管理员禁用，自动登出")
                accountManager.logout()
            }
        }
    }

    /** 把后端返回的状态/开关/公告写进 [_remoteState]。 */
    private fun applyState(data: JSONObject?) {
        if (data == null) return
        val anns = data.optJSONArray("announcements")
        _remoteState.value = RemoteState(
            status = data.optString("status", "normal"),
            restrictionNote = data.optString("restriction_note", ""),
            disabledFeatures = List(data.optJSONArray("disabled_features")?.length() ?: 0) { i ->
                data.optJSONArray("disabled_features")?.optString(i).orEmpty()
            },
            announcements = List(anns?.length() ?: 0) { i ->
                val o = anns?.optJSONObject(i) ?: JSONObject()
                RemoteState.Announcement(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    content = o.optString("content"),
                    createdAt = o.optLong("created_at"),
                )
            },
        )
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

        /** 设备快照自动上传周期（需求：按小时自动上传）。 */
        const val SYNC_INTERVAL_MS: Long = 60 * 60 * 1000L

        /** 管控状态轮询周期：管理端改状态/发公告后最迟 30 秒生效。 */
        const val STATE_POLL_MS: Long = 30 * 1000L
    }
}
