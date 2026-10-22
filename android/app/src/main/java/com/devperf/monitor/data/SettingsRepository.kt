package com.devperf.monitor.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 采集频率档位：FAST=500ms / NORMAL=1s / MINIMAL=2s（极简模式同时关动画、停历史落盘）。 */
enum class RefreshMode(val label: String, val tickMs: Long) {
    FAST("高频（500ms）", 500L),
    NORMAL("标准（1s）", 1000L),
    MINIMAL("极简（2s）", 2000L),
}

enum class ThemeMode(val label: String) {
    DARK("深色"),
    LIGHT("浅色"),
    AUTO("跟随系统"),
}

/** 全部用户设置（设置页唯一数据源）。 */
data class MonitorSettings(
    val overlayEnabled: Boolean = false,
    val collectInBackground: Boolean = true,
    val overlayOpacity: Float = 0.9f,
    val overlayScale: Float = 1f,
    val overlayMiniMode: Boolean = false,
    val snapToEdge: Boolean = true,
    val refreshMode: RefreshMode = RefreshMode.NORMAL,
    val tempAlertThresholdC: Float = 45f,
    val alertsEnabled: Boolean = true,
    val alertByNotification: Boolean = true,
    val alertByOverlayPopup: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.DARK,
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 设置仓库：DataStore Preferences，全部本地存储。
 */
class SettingsRepository(private val context: Context) {

    val settings: Flow<MonitorSettings> = context.settingsDataStore.data.map { prefs ->
        MonitorSettings(
            overlayEnabled = prefs[KEY_OVERLAY_ENABLED] ?: false,
            collectInBackground = prefs[KEY_COLLECT_BACKGROUND] ?: true,
            overlayOpacity = prefs[KEY_OVERLAY_OPACITY] ?: 0.9f,
            overlayScale = prefs[KEY_OVERLAY_SCALE] ?: 1f,
            overlayMiniMode = prefs[KEY_OVERLAY_MINI] ?: false,
            snapToEdge = prefs[KEY_SNAP_EDGE] ?: true,
            refreshMode = prefs[KEY_REFRESH_MODE]?.let { name ->
                runCatching { RefreshMode.valueOf(name) }.getOrNull()
            } ?: RefreshMode.NORMAL,
            tempAlertThresholdC = prefs[KEY_TEMP_THRESHOLD] ?: 45f,
            alertsEnabled = prefs[KEY_ALERTS_ENABLED] ?: true,
            alertByNotification = prefs[KEY_ALERT_NOTIFICATION] ?: true,
            alertByOverlayPopup = prefs[KEY_ALERT_POPUP] ?: true,
            themeMode = prefs[KEY_THEME]?.let { name ->
                runCatching { ThemeMode.valueOf(name) }.getOrNull()
            } ?: ThemeMode.DARK,
        )
    }

    suspend fun setOverlayEnabled(value: Boolean) = put(KEY_OVERLAY_ENABLED, value)
    suspend fun setCollectInBackground(value: Boolean) = put(KEY_COLLECT_BACKGROUND, value)
    suspend fun setOverlayOpacity(value: Float) = put(KEY_OVERLAY_OPACITY, value)
    suspend fun setOverlayScale(value: Float) = put(KEY_OVERLAY_SCALE, value)
    suspend fun setOverlayMiniMode(value: Boolean) = put(KEY_OVERLAY_MINI, value)
    suspend fun setSnapToEdge(value: Boolean) = put(KEY_SNAP_EDGE, value)
    suspend fun setRefreshMode(mode: RefreshMode) = put(KEY_REFRESH_MODE, mode.name)
    suspend fun setTempAlertThreshold(value: Float) = put(KEY_TEMP_THRESHOLD, value)
    suspend fun setAlertsEnabled(value: Boolean) = put(KEY_ALERTS_ENABLED, value)
    suspend fun setAlertByNotification(value: Boolean) = put(KEY_ALERT_NOTIFICATION, value)
    suspend fun setAlertByOverlayPopup(value: Boolean) = put(KEY_ALERT_POPUP, value)
    suspend fun setThemeMode(mode: ThemeMode) = put(KEY_THEME, mode.name)

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.settingsDataStore.edit { it[key] = value }
    }

    private companion object {
        val KEY_OVERLAY_ENABLED = booleanPreferencesKey("overlay_enabled")
        val KEY_COLLECT_BACKGROUND = booleanPreferencesKey("collect_background")
        val KEY_OVERLAY_OPACITY = floatPreferencesKey("overlay_opacity")
        val KEY_OVERLAY_SCALE = floatPreferencesKey("overlay_scale")
        val KEY_OVERLAY_MINI = booleanPreferencesKey("overlay_mini")
        val KEY_SNAP_EDGE = booleanPreferencesKey("snap_edge")
        val KEY_REFRESH_MODE = stringPreferencesKey("refresh_mode")
        val KEY_TEMP_THRESHOLD = floatPreferencesKey("temp_threshold_c")
        val KEY_ALERTS_ENABLED = booleanPreferencesKey("alerts_enabled")
        val KEY_ALERT_NOTIFICATION = booleanPreferencesKey("alert_notification")
        val KEY_ALERT_POPUP = booleanPreferencesKey("alert_popup")
        val KEY_THEME = stringPreferencesKey("theme_mode")
    }
}
