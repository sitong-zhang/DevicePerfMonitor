package com.devperf.monitor.data.collectors

import com.devperf.monitor.core.Metric
import com.devperf.monitor.core.MetricId
import kotlinx.coroutines.CoroutineScope

/**
 * 采集器统一接口。
 *
 * 硬性约定（对应 README「API-权限-可用性-降级」对照表）：
 * 1. [sample] 必须是轻量读取（单次 < 50ms），由 Repository 在 IO 协程中调度；
 * 2. 任何读取失败（文件不存在 / EACCES / 权限撤销）必须降级为
 *    [com.devperf.monitor.core.MetricState.Unavailable] 或 NeedsPermission，
 *    禁止抛异常、禁止返回写死值或估算值冒充真实数据；
 * 3. 需要持续回调的采集器（如 Choreographer 帧回调）在 [start] 中注册、[stop] 中注销。
 */
interface MetricCollector {
    fun start(scope: CoroutineScope) {}
    fun stop() {}
    suspend fun sample(): Map<MetricId, Metric<*>>
}

// ---------------------------------------------------------------------------
// 设备快照模型：每个类别一组指标，指标一律为三态 Metric<T>
// ---------------------------------------------------------------------------

data class CoreUsage(val index: Int, val percent: Float)

data class CoreFreq(val index: Int, val mhz: Float)

data class AppMemory(val packageName: String, val mb: Float)

enum class ChargingState {
    CHARGING, DISCHARGING, FULL, NOT_CHARGING, UNKNOWN;

    val label: String
        get() = when (this) {
            CHARGING -> "充电中"
            DISCHARGING -> "放电中"
            FULL -> "已充满"
            NOT_CHARGING -> "未充电"
            UNKNOWN -> "未知"
        }
}

/** 电池健康粗粒度状态（来自系统广播 EXTRA_HEALTH，公开 API 仅此粒度）。 */
enum class BatteryHealthCoarse(val label: String) {
    GOOD("良好"),
    OVERHEAT("过热"),
    DEAD("无电"),
    OVER_VOLTAGE("过压"),
    COLD("过冷"),
    UNSPECIFIED_FAILURE("未知故障"),
    UNKNOWN("未知"),
}

/** 系统热状态（PowerManager.getCurrentThermalStatus，API 29+）。 */
enum class ThermalStatus(val label: String) {
    NONE("无"),
    LIGHT("轻微"),
    MODERATE("中度"),
    SEVERE("严重"),
    CRITICAL("危险"),
    EMERGENCY("紧急"),
    UNSUPPORTED("不支持"),
}

data class DisplayMetrics(
    val fps: Metric<Float>,
    val globalFps: Metric<Float>,
    val frameTimeMs: Metric<Float>,
    val frameStdevMs: Metric<Float>,
    val jankFrames: Metric<Int>,
    /** 最近 30s 掉帧率（百分比）：Σ丢帧 / Σ总帧。 */
    val jankRatio30s: Metric<Float>,
    val refreshRateHz: Metric<Float>,
)

data class CpuMetrics(
    val usagePercent: Metric<Float>,
    val perCoreUsage: Metric<List<CoreUsage>>,
    val perCoreFreqMHz: Metric<List<CoreFreq>>,
    val temperatureC: Metric<Float>,
)

data class GpuMetrics(
    val loadPercent: Metric<Float>,
    val freqMHz: Metric<Float>,
    val temperatureC: Metric<Float>,
)

data class BatteryMetrics(
    val temperatureC: Metric<Float>,
    val currentMa: Metric<Float>,
    val voltageV: Metric<Float>,
    val levelPercent: Metric<Float>,
    val chargingState: ChargingState,
    val healthCoarse: BatteryHealthCoarse,
    /** 百分比电池健康：Android 无公开 API，常态为 Unavailable（禁止用循环次数估算冒充）。 */
    val healthPercent: Metric<Float>,
)

data class MemoryMetrics(
    val totalMb: Metric<Float>,
    val usedMb: Metric<Float>,
    val availableMb: Metric<Float>,
    val usageRatio: Metric<Float>,
    /** 后台应用内存明细：需 root/Shizuku（dumpsys meminfo），否则 Unavailable。 */
    val perApp: Metric<List<AppMemory>>,
)

data class ThermalMetrics(
    /** 机身整体温度：优先取可读温区最大值；zone 不可读（部分 ROM 限制）时
     *  为电池温度（[source] 记录真实来源，UI 透明标注，不冒充）。 */
    val deviceTempC: Metric<Float>,
    val status: ThermalStatus,
    /** 热余量：0.0~1.0 比值（1.0 = SEVERE 节流阈值），API 30+，非摄氏度。 */
    val headroom: Metric<Float>,
    /** 温度读数来源（ZONE 真实温区 / BATTERY 电池温度降级 / NONE 无）。 */
    val source: ThermalCollector.Source = ThermalCollector.Source.NONE,
)

data class DeviceSnapshot(
    val timestampMs: Long,
    val display: DisplayMetrics,
    val cpu: CpuMetrics,
    val gpu: GpuMetrics,
    val battery: BatteryMetrics,
    val memory: MemoryMetrics,
    val thermal: ThermalMetrics,
) {
    companion object {
        /** 全部指标置为不可用的初始快照（UI 显示"—"占位，绝不显示假数据）。 */
        fun empty(at: Long = 0L): DeviceSnapshot {
            val u = { reason: String -> Metric.unavailable<Float>(reason, at) }
            return DeviceSnapshot(
                timestampMs = at,
                display = DisplayMetrics(
                    fps = u("等待采样"),
                    globalFps = Metric.needsPermission("root 或 Shizuku", com.devperf.monitor.core.PermissionAction.SHIZUKU, at),
                    frameTimeMs = u("等待采样"),
                    frameStdevMs = u("等待采样"),
                    jankFrames = Metric.unavailable("等待采样", at),
                    jankRatio30s = u("等待采样"),
                    refreshRateHz = u("等待采样"),
                ),
                cpu = CpuMetrics(
                    usagePercent = u("等待采样"),
                    perCoreUsage = Metric.unavailable("等待采样", at),
                    perCoreFreqMHz = Metric.unavailable("等待采样", at),
                    temperatureC = u("等待采样"),
                ),
                gpu = GpuMetrics(
                    loadPercent = u("等待采样"),
                    freqMHz = u("等待采样"),
                    temperatureC = u("等待采样"),
                ),
                battery = BatteryMetrics(
                    temperatureC = u("等待采样"),
                    currentMa = u("等待采样"),
                    voltageV = u("等待采样"),
                    levelPercent = u("等待采样"),
                    chargingState = ChargingState.UNKNOWN,
                    healthCoarse = BatteryHealthCoarse.UNKNOWN,
                    healthPercent = Metric.unavailable("Android 无公开的电池健康百分比接口", at),
                ),
                memory = MemoryMetrics(
                    totalMb = u("等待采样"),
                    usedMb = u("等待采样"),
                    availableMb = u("等待采样"),
                    usageRatio = u("等待采样"),
                    perApp = Metric.unavailable("需要 root 或 Shizuku", at),
                ),
                thermal = ThermalMetrics(
                    deviceTempC = u("等待采样"),
                    status = ThermalStatus.UNSUPPORTED,
                    headroom = u("需要 API 30+"),
                ),
            )
        }
    }
}
