package com.devperf.monitor.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.devperf.monitor.core.CoreMetricId
import com.devperf.monitor.core.MetricViewMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 采集频率档位：FAST=500ms / NORMAL=1s / MINIMAL=1s。
 *
 * **不变量：任何档位的采样周期都不慢于 1 秒**（用户要求「每个数据都要做到每秒
 * 刷新」），所以极简档不再提供 2s 慢采样，只保留「关动画 + 停历史落盘」的省电
 * 特性。若将来新增 > 1s 的档位，[MetricsRepository] 会把 tickMs 兜底钳到 1s。
 */
enum class RefreshMode(val label: String, val tickMs: Long) {
    FAST("高频（500ms）", 500L),
    NORMAL("标准（1s）", 1000L),
    MINIMAL("极简（1s·省电）", 1000L),
}

enum class ThemeMode(val label: String) {
    DARK("深色"),
    LIGHT("浅色"),
    AUTO("跟随系统"),
}

/**
 * 悬浮窗显示样式（1.0.43 新增）：用户可定制悬浮窗外观。
 * - CARD：默认完整卡片，带综合健康小环；
 * - NUMBERS：纯数字横排，无卡片容器与阴影；
 * - COMPACT：极简条，小字号纯数字，最省屏幕空间。
 */
enum class OverlayStyle(val label: String) {
    CARD("卡片（带健康环）"),
    NUMBERS("纯数字"),
    COMPACT("极简条"),
}

/** 悬浮窗默认显示项（有序）：内存占用率 / 设备温度 / 屏幕刷新率（经典三项）。 */
fun defaultOverlayItems(): List<String> = listOf(
    "core:MEMORY_RATIO",
    "core:DEVICE_TEMP",
    "core:REFRESH_RATE",
)

/**
 * 悬浮窗显示项的 key 规范（1.0.44 定制器起）：
 * - `core:<CoreMetricId 名>`：核心指标（内存占用率 / 温度 / 刷新率 / 帧率）；
 * - `deep:<深度行 key>`：深度监测里**这台设备真实读得到**的任意数值行
 *   （实时 FPS / 平均 FPS / 1% Low / CPU / GPU / 电压 / 电流 / 功率 / 电池 / 网络…）。
 * 候选池由「悬浮窗定制器」页动态列出，用户自由增删 + 拖拽排序。
 */

/**
 * 1.0.26 壁纸选择：内置三张（浅色基调，适配深色主题罩层）+ 用户相册自定义。
 *
 * 自定义图不在 DataStore 里存位图——只存「选了自定义」这个事实，
 * 图片字节由设置页复制到 `filesDir/wallpaper_custom.jpg`（见
 * [com.devperf.monitor.ui.wallpaper.Wallpapers]），路径随设置一起持久化。
 * 文件缺失（被清理）时读取侧回落到 [LIGHT_TEAL]，不白屏。
 */
enum class WallpaperId(val label: String) {
    LIGHT_TEAL("青蓝"),
    AURORA("极光"),
    MINT("薄荷"),
    CUSTOM("自定义"),
}

/** 每个核心指标默认的可视化模式（占用率用环形、温度和帧率看波动、刷新率看数字）。 */
fun defaultMetricViewModes(): Map<CoreMetricId, MetricViewMode> = mapOf(
    CoreMetricId.MEMORY_RATIO to MetricViewMode.RING,
    CoreMetricId.DEVICE_TEMP to MetricViewMode.WAVE,
    CoreMetricId.REFRESH_RATE to MetricViewMode.NUMBER,
    CoreMetricId.FPS to MetricViewMode.WAVE,
)

/** 每个核心指标默认的回看窗口（秒）：1 分钟，与详情页 WINDOWS 首项一致。 */
fun defaultMetricWindows(): Map<CoreMetricId, Int> =
    CoreMetricId.values().associateWith { DEFAULT_WINDOW_SEC }

/** 回看窗口默认值（秒）。 */
const val DEFAULT_WINDOW_SEC: Int = 60

/** 全部用户设置（设置页唯一数据源）。 */
data class MonitorSettings(
    /** 每个核心指标的可视化模式；缺省项回落到 [defaultMetricViewModes]。 */
    val metricViewModes: Map<CoreMetricId, MetricViewMode> = defaultMetricViewModes(),
    /**
     * 每个核心指标详情页的「回看」时间窗口（秒）；缺省项回落到
     * [defaultMetricWindows]。1.0.14 修复：窗口选择以前只活在页面 Composition
     * 里，退出详情页再进就被打回默认的 1 分钟——与显示方式同一套持久化机制。
     */
    val metricWindows: Map<CoreMetricId, Int> = defaultMetricWindows(),
    /**
     * 悬浮窗总开关。1.0.8 起默认 true：用户要求「一退出应用悬浮窗就出现，
     * 不需要手动打开」。老用户升级后保留其已有选择（DataStore 里存过值）。
     */
    val overlayEnabled: Boolean = true,
    val collectInBackground: Boolean = true,
    /**
     * 悬浮窗窗口透明度（0.4~1.0，WindowManager 层 alpha）。
     * 1.0.8 起真正接线到 params.alpha；默认 1.0 = 稳定完整显示。
     */
    val overlayOpacity: Float = 1f,
    /** 双指缩放 / 默认缩放（0.8~1.6）。 */
    val overlayScale: Float = 1f,
    /**
     * 默认形态开关：true = 启动悬浮窗时默认纯数字（只要数字不要卡片），
     * false = 默认完整卡片（用户主路径：「等它显示那张卡片」）。
     */
    val overlayMiniMode: Boolean = false,
    /**
     * 悬浮窗显示哪些指标（用户自选 + 拖拽排序，1.0.44 定制器）。
     * 有序列表，元素 key 规范见 [defaultOverlayItems] 上方说明。
     * 候选池 = 核心指标 + 这台设备真实读得到的全部深度数值行（最大自由）。
     */
    val overlayItems: List<String> = defaultOverlayItems(),
    /**
     * 悬浮窗显示样式（1.0.43 新增），取代原「默认纯数字模式」单一开关。
     */
    val overlayStyle: OverlayStyle = OverlayStyle.CARD,
    /**
     * 深度指标详情页每个指标的可视化模式（1.0.44，key = 深度行 key）。
     * 与核心指标 [metricViewModes] 同一套交互：数字 / 波形 / 柱状。
     */
    val deepViewModes: Map<String, MetricViewMode> = emptyMap(),
    /**
     * 深度指标详情页「回看」时间窗口（秒，key = 深度行 key）。
     * 与核心指标 [metricWindows] 同一套持久化，退出再进保留选择。
     */
    val deepWindows: Map<String, Int> = emptyMap(),
    val snapToEdge: Boolean = true,
    val refreshMode: RefreshMode = RefreshMode.NORMAL,
    val tempAlertThresholdC: Float = 45f,
    val alertsEnabled: Boolean = true,
    val alertByNotification: Boolean = true,
    val alertByOverlayPopup: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.DARK,
    /** 当前壁纸（1.0.26 新增）；CUSTOM 表示用 [wallpaperCustomPath] 的图片。 */
    val wallpaper: WallpaperId = WallpaperId.LIGHT_TEAL,
    /** 自定义壁纸文件绝对路径（wallpaper = CUSTOM 时有效）。 */
    val wallpaperCustomPath: String? = null,
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 设置仓库：DataStore Preferences，全部本地存储。
 */
class SettingsRepository(private val context: Context) {

    val settings: Flow<MonitorSettings> = context.settingsDataStore.data.map { prefs ->
        MonitorSettings(
            metricViewModes = prefs[KEY_METRIC_MODES]
                ?.let { parseMetricModes(it) }
                ?.takeIf { it.isNotEmpty() }
                ?: defaultMetricViewModes(),
            metricWindows = prefs[KEY_METRIC_WINDOWS]
                ?.let { parseMetricWindows(it) }
                ?.takeIf { it.isNotEmpty() }
                ?: defaultMetricWindows(),
            overlayEnabled = prefs[KEY_OVERLAY_ENABLED] ?: true,
            collectInBackground = prefs[KEY_COLLECT_BACKGROUND] ?: true,
            overlayOpacity = prefs[KEY_OVERLAY_OPACITY] ?: 1f,
            overlayScale = prefs[KEY_OVERLAY_SCALE] ?: 1f,
            overlayMiniMode = prefs[KEY_OVERLAY_MINI] ?: false,
            overlayItems = prefs[KEY_OVERLAY_ITEMS]?.let { parseOverlayItems(it) }
                ?.takeIf { it.isNotEmpty() }
                // 1.0.43 旧值迁移：老版本只存核心指标名 → 转成 core: 前缀
                ?: prefs[KEY_OVERLAY_METRICS]?.let { parseOverlayMetrics(it) }
                    ?.takeIf { it.isNotEmpty() }
                    ?.map { "core:$it" }
                ?: defaultOverlayItems(),
            overlayStyle = prefs[KEY_OVERLAY_STYLE]?.let { name ->
                runCatching { OverlayStyle.valueOf(name) }.getOrNull()
            } ?: OverlayStyle.CARD,
            deepViewModes = prefs[KEY_DEEP_VIEW_MODES]
                ?.let { parseDeepViewModes(it) }
                ?: emptyMap(),
            deepWindows = prefs[KEY_DEEP_WINDOWS]
                ?.let { parseDeepWindows(it) }
                ?: emptyMap(),
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
            wallpaper = prefs[KEY_WALLPAPER]?.let { name ->
                runCatching { WallpaperId.valueOf(name) }.getOrNull()
            } ?: WallpaperId.LIGHT_TEAL,
            wallpaperCustomPath = prefs[KEY_WALLPAPER_CUSTOM],
        )
    }

    suspend fun setOverlayEnabled(value: Boolean) = put(KEY_OVERLAY_ENABLED, value)

    /** 切换某个核心指标的可视化模式（数字 / 波形 / 环形 / 柱状）。 */
    suspend fun setMetricViewMode(id: CoreMetricId, mode: MetricViewMode) {
        context.settingsDataStore.edit { prefs ->
            val current = (
                prefs[KEY_METRIC_MODES]?.let { parseMetricModes(it) }
                    ?: defaultMetricViewModes()
                ).toMutableMap()
            current[id] = mode
            prefs[KEY_METRIC_MODES] =
                current.entries.joinToString("|") { "${it.key.name}:${it.value.name}" }
        }
    }

    /** 切换某个核心指标详情页的回看窗口（秒，1.0.14 新增持久化）。 */
    suspend fun setMetricWindow(id: CoreMetricId, windowSec: Int) {
        context.settingsDataStore.edit { prefs ->
            val current = (
                prefs[KEY_METRIC_WINDOWS]?.let { parseMetricWindows(it) }
                    ?: defaultMetricWindows()
                ).toMutableMap()
            current[id] = windowSec
            prefs[KEY_METRIC_WINDOWS] =
                current.entries.joinToString("|") { "${it.key.name}:${it.value}" }
        }
    }
    suspend fun setCollectInBackground(value: Boolean) = put(KEY_COLLECT_BACKGROUND, value)
    suspend fun setOverlayOpacity(value: Float) = put(KEY_OVERLAY_OPACITY, value)
    suspend fun setOverlayScale(value: Float) = put(KEY_OVERLAY_SCALE, value)
    suspend fun setOverlayMiniMode(value: Boolean) = put(KEY_OVERLAY_MINI, value)
    /** 设置悬浮窗显示项（有序列表，1.0.44 定制器）。 */
    suspend fun setOverlayItems(items: List<String>) =
        put(KEY_OVERLAY_ITEMS, items.joinToString("|"))
    /** 设置悬浮窗显示样式（1.0.43）。 */
    suspend fun setOverlayStyle(style: OverlayStyle) = put(KEY_OVERLAY_STYLE, style.name)
    /** 切换某个深度指标详情页的可视化模式（1.0.44）。 */
    suspend fun setDeepViewMode(key: String, mode: MetricViewMode) {
        context.settingsDataStore.edit { prefs ->
            val current = (
                prefs[KEY_DEEP_VIEW_MODES]?.let { parseDeepViewModes(it) }
                    ?: emptyMap()
                ).toMutableMap()
            current[key] = mode
            prefs[KEY_DEEP_VIEW_MODES] =
                current.entries.joinToString("|") { "${it.key}:${it.value.name}" }
        }
    }
    /** 切换某个深度指标详情页的回看窗口（秒，1.0.44）。 */
    suspend fun setDeepWindow(key: String, seconds: Int) {
        context.settingsDataStore.edit { prefs ->
            val current = (
                prefs[KEY_DEEP_WINDOWS]?.let { parseDeepWindows(it) }
                    ?: emptyMap()
                ).toMutableMap()
            current[key] = seconds
            prefs[KEY_DEEP_WINDOWS] =
                current.entries.joinToString("|") { "${it.key}:${it.value}" }
        }
    }
    suspend fun setSnapToEdge(value: Boolean) = put(KEY_SNAP_EDGE, value)
    suspend fun setRefreshMode(mode: RefreshMode) = put(KEY_REFRESH_MODE, mode.name)
    suspend fun setTempAlertThreshold(value: Float) = put(KEY_TEMP_THRESHOLD, value)
    suspend fun setAlertsEnabled(value: Boolean) = put(KEY_ALERTS_ENABLED, value)
    suspend fun setAlertByNotification(value: Boolean) = put(KEY_ALERT_NOTIFICATION, value)
    suspend fun setAlertByOverlayPopup(value: Boolean) = put(KEY_ALERT_POPUP, value)
    suspend fun setThemeMode(mode: ThemeMode) = put(KEY_THEME, mode.name)

    /** 切换壁纸（内置三选一；自定义走 [setWallpaperCustom]）。 */
    suspend fun setWallpaper(id: WallpaperId) = put(KEY_WALLPAPER, id.name)

    /**
     * 设置自定义壁纸：写入文件路径并把当前壁纸切到 CUSTOM。
     * 1.0.26：图片字节由设置页先复制到 filesDir 再调这里，只存路径。
     */
    suspend fun setWallpaperCustom(path: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_WALLPAPER_CUSTOM] = path
            prefs[KEY_WALLPAPER] = WallpaperId.CUSTOM.name
        }
    }

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.settingsDataStore.edit { it[key] = value }
    }

    private companion object {
        /** 序列化格式：FPS:WAVE|MEMORY_RATIO:RING|... 缺省项回落到默认模式。 */
        fun parseMetricModes(raw: String): Map<CoreMetricId, MetricViewMode> =
            raw.split("|").mapNotNull { part ->
                val kv = part.split(":")
                if (kv.size != 2) return@mapNotNull null
                val id = runCatching { CoreMetricId.valueOf(kv[0]) }.getOrNull() ?: return@mapNotNull null
                val mode = runCatching { MetricViewMode.valueOf(kv[1]) }.getOrNull() ?: return@mapNotNull null
                id to mode
            }.toMap()

        /** 序列化格式：FPS:300|DEVICE_TEMP:60|... 缺省项回落到默认窗口。 */
        fun parseMetricWindows(raw: String): Map<CoreMetricId, Int> =
            raw.split("|").mapNotNull { part ->
                val kv = part.split(":")
                if (kv.size != 2) return@mapNotNull null
                val id = runCatching { CoreMetricId.valueOf(kv[0]) }.getOrNull() ?: return@mapNotNull null
                val sec = kv[1].toIntOrNull() ?: return@mapNotNull null
                id to sec
            }.toMap()

        val KEY_METRIC_MODES = stringPreferencesKey("metric_view_modes")
        val KEY_METRIC_WINDOWS = stringPreferencesKey("metric_windows")
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
        val KEY_WALLPAPER = stringPreferencesKey("wallpaper")
        val KEY_WALLPAPER_CUSTOM = stringPreferencesKey("wallpaper_custom_path")

        /** 悬浮窗显示指标（1.0.43 旧格式）：CoreMetricId 名以 `|` 分隔。 */
        fun parseOverlayMetrics(raw: String): Set<CoreMetricId> =
            raw.split("|").mapNotNull { part ->
                runCatching { CoreMetricId.valueOf(part) }.getOrNull()
            }.toSet()

        /** 悬浮窗显示项（1.0.44）：有序 key 列表（core:XXX / deep:YYY）以 `|` 分隔。 */
        fun parseOverlayItems(raw: String): List<String> =
            raw.split("|").map { it.trim() }.filter { it.isNotEmpty() }

        /** 深度指标显示方式：`key:MODE` 以 `|` 分隔。 */
        fun parseDeepViewModes(raw: String): Map<String, MetricViewMode> =
            raw.split("|").mapNotNull { part ->
                val kv = part.split(":")
                if (kv.size != 2) return@mapNotNull null
                val mode = runCatching { MetricViewMode.valueOf(kv[1]) }.getOrNull()
                    ?: return@mapNotNull null
                kv[0] to mode
            }.toMap()

        /** 深度指标回看窗口：`key:秒数` 以 `|` 分隔。 */
        fun parseDeepWindows(raw: String): Map<String, Int> =
            raw.split("|").mapNotNull { part ->
                val kv = part.split(":")
                if (kv.size != 2) return@mapNotNull null
                val sec = kv[1].toIntOrNull() ?: return@mapNotNull null
                kv[0] to sec
            }.toMap()

        val KEY_OVERLAY_METRICS = stringPreferencesKey("overlay_metrics")
        val KEY_OVERLAY_ITEMS = stringPreferencesKey("overlay_items")
        val KEY_OVERLAY_STYLE = stringPreferencesKey("overlay_style")
        val KEY_DEEP_VIEW_MODES = stringPreferencesKey("deep_view_modes")
        val KEY_DEEP_WINDOWS = stringPreferencesKey("deep_windows")
    }
}
