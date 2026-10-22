package com.devperf.monitor.data

import android.content.Context
import android.util.Log
import com.devperf.monitor.core.Metric
import com.devperf.monitor.core.MetricId
import com.devperf.monitor.data.collectors.BatteryCollector
import com.devperf.monitor.data.collectors.CpuCollector
import com.devperf.monitor.data.collectors.DeviceSnapshot
import com.devperf.monitor.data.collectors.DisplayCollector
import com.devperf.monitor.data.collectors.DisplayMetrics
import com.devperf.monitor.data.collectors.GlobalFpsCollector
import com.devperf.monitor.data.collectors.GpuCollector
import com.devperf.monitor.data.collectors.MemoryCollector
import com.devperf.monitor.data.collectors.MetricCollector
import com.devperf.monitor.data.collectors.PrivilegedShell
import com.devperf.monitor.data.collectors.ThermalCollector
import com.devperf.monitor.data.storage.HistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 采集仓库：统一调度全部采集器，输出 [DeviceSnapshot] 状态流，并维护 30 分钟历史。
 *
 * 设计要点：
 * - 采集 tick 由 [RefreshMode] 决定（500ms / 1s / 2s）；FPS 由 DisplayCollector 内部
 *   固定 500ms 窗口计算，与 tick 解耦，保证需求中的刷新频率语义；
 * - 极简模式：tick 2s + 停历史落盘（UI 层同时关闭动画）；
 * - 单个采集器抛异常不影响其他采集器（runCatching 隔离），异常指标沿用上一次快照值；
 * - 历史落盘每 30s 一次，原子替换，极简模式跳过。
 */
class MetricsRepository(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) {

    val history = HistoryStore(context)

    /** 特权通道（root / Shizuku），状态变化后调用 [refreshPrivilegedState]。 */
    val shell = PrivilegedShell(context)

    private val displayCollector = DisplayCollector(context)
    private val globalFpsCollector = GlobalFpsCollector(context, shell)
    private val cpuCollector = CpuCollector(shell)
    private val gpuCollector = GpuCollector(shell)
    private val batteryCollector = BatteryCollector(context)
    private val memoryCollector = MemoryCollector(context, shell)
    private val thermalCollector = ThermalCollector(context)

    private val collectors: List<MetricCollector> = listOf(
        displayCollector, globalFpsCollector, cpuCollector, gpuCollector,
        batteryCollector, memoryCollector, thermalCollector,
    )

    private val _snapshot = MutableStateFlow(DeviceSnapshot.empty())
    val snapshot: StateFlow<DeviceSnapshot> = _snapshot.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = mutableListOf<Job>()

    @Volatile
    private var tickMs = 1000L

    @Volatile
    var minimalMode: Boolean = false
        private set

    @Volatile
    private var running = false

    /** 启动采集（幂等）。 */
    fun start() {
        if (running) return
        running = true
        runCatching { history.load() }
        collectors.forEach { collector ->
            runCatching { collector.start(scope) }
                .onFailure { Log.w(TAG, "${collector.javaClass.simpleName} 启动失败（降级处理）", it) }
        }
        jobs += scope.launch {
            settingsRepository.settings.collect { s ->
                tickMs = s.refreshMode.tickMs
                minimalMode = s.refreshMode == RefreshMode.MINIMAL
                history.persistEnabled = !minimalMode
            }
        }
        jobs += scope.launch {
            while (isActive) {
                val startedAt = System.currentTimeMillis()
                runCatching { sampleAll() }
                    .onFailure { Log.w(TAG, "采样周期异常（已保护）", it) }
                val elapsed = System.currentTimeMillis() - startedAt
                delay((tickMs - elapsed).coerceAtLeast(150L))
            }
        }
        jobs += scope.launch {
            while (isActive) {
                delay(PERSIST_INTERVAL_MS)
                if (!minimalMode) history.persist()
            }
        }
    }

    fun stop() {
        running = false
        jobs.forEach { it.cancel() }
        jobs.clear()
        collectors.forEach { runCatching { it.stop() } }
        runCatching { history.persist() }
    }

    /** Shizuku/root 授权状态变化后调用，刷新依赖特权通道的指标三态。 */
    fun refreshPrivilegedState() {
        shell.refresh()
    }

    /**
     * 悬浮窗专用：500ms 粒度 FPS 直读（需求规定悬浮窗 FPS 500ms 刷新，
     * 而采集 tick 可能是 1s/2s，因此悬浮窗绕开 tick 直接读采集器当前值）。
     */
    fun currentFps(): Float? = displayCollector.currentFps()

    private suspend fun sampleAll() = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val merged = HashMap<MetricId, Metric<*>>()
        collectors.forEach { collector ->
            val result = runCatching { collector.sample() }.getOrNull()
            if (result != null) merged.putAll(result)
            else Log.w(TAG, "${collector.javaClass.simpleName} 采样失败（沿用上次值）")
        }

        // 写入秒级历史（仅 Float 型指标；三态不可用的指标不入历史，避免曲线断裂）
        MetricId.historyTracked.forEach { id ->
            val value = merged[id]?.value
            if (value is Float && value.isFinite()) history.put(id, now, value)
        }

        val previous = _snapshot.value
        _snapshot.value = buildSnapshot(merged, now, previous)
    }

    private fun buildSnapshot(
        m: Map<MetricId, Metric<*>>,
        now: Long,
        previous: DeviceSnapshot,
    ): DeviceSnapshot {
        @Suppress("UNCHECKED_CAST")
        fun <T> metric(id: MetricId): Metric<T> =
            (m[id] as? Metric<T>) ?: Metric.unavailable("本次采样未返回", now)

        // 注意：m 为 Map<MetricId, Metric<*>>，泛型擦除后 as? Metric<Float> 对持有 Double 的
        // 指标也会成功（运行时都是 Metric），导致下游强转 Float 时崩溃。
        // 因此这里额外校验"实际值类型"才是 Float/Int，否则回退到上一次快照值。
        val floatOr = { id: MetricId, fallback: Metric<Float> ->
            val candidate = m[id]
            if (candidate != null && candidate.value is Float) {
                @Suppress("UNCHECKED_CAST") candidate as Metric<Float>
            } else fallback
        }
        val intOr = { id: MetricId, fallback: Metric<Int> ->
            val candidate = m[id]
            if (candidate != null && candidate.value is Int) {
                @Suppress("UNCHECKED_CAST") candidate as Metric<Int>
            } else fallback
        }

        return DeviceSnapshot(
            timestampMs = now,
            display = DisplayMetrics(
                fps = floatOr(MetricId.FPS, previous.display.fps),
                globalFps = floatOr(MetricId.GLOBAL_FPS, previous.display.globalFps),
                frameTimeMs = floatOr(MetricId.FRAME_TIME, previous.display.frameTimeMs),
                frameStdevMs = floatOr(MetricId.FRAME_STDEV, previous.display.frameStdevMs),
                jankFrames = intOr(MetricId.JANK_FRAMES, previous.display.jankFrames),
                jankRatio30s = floatOr(MetricId.JANK_RATIO, previous.display.jankRatio30s),
                refreshRateHz = floatOr(MetricId.REFRESH_RATE, previous.display.refreshRateHz),
            ),
            cpu = com.devperf.monitor.data.collectors.CpuMetrics(
                usagePercent = floatOr(MetricId.CPU_USAGE, previous.cpu.usagePercent),
                perCoreUsage = (m[MetricId.CPU_CORE_USAGE] as? Metric<List<com.devperf.monitor.data.collectors.CoreUsage>>)
                    ?: previous.cpu.perCoreUsage,
                perCoreFreqMHz = (m[MetricId.CPU_CORE_FREQ] as? Metric<List<com.devperf.monitor.data.collectors.CoreFreq>>)
                    ?: previous.cpu.perCoreFreqMHz,
                temperatureC = floatOr(MetricId.CPU_TEMP, previous.cpu.temperatureC),
            ),
            gpu = com.devperf.monitor.data.collectors.GpuMetrics(
                loadPercent = floatOr(MetricId.GPU_LOAD, previous.gpu.loadPercent),
                freqMHz = floatOr(MetricId.GPU_FREQ, previous.gpu.freqMHz),
                temperatureC = floatOr(MetricId.GPU_TEMP, previous.gpu.temperatureC),
            ),
            battery = com.devperf.monitor.data.collectors.BatteryMetrics(
                temperatureC = floatOr(MetricId.BATTERY_TEMP, previous.battery.temperatureC),
                currentMa = floatOr(MetricId.BATTERY_CURRENT, previous.battery.currentMa),
                voltageV = floatOr(MetricId.BATTERY_VOLTAGE, previous.battery.voltageV),
                levelPercent = floatOr(MetricId.BATTERY_LEVEL, previous.battery.levelPercent),
                chargingState = batteryCollector.chargingState,
                healthCoarse = batteryCollector.healthCoarse,
                healthPercent = previous.battery.healthPercent,
            ),
            memory = com.devperf.monitor.data.collectors.MemoryMetrics(
                totalMb = floatOr(MetricId.MEMORY_TOTAL, previous.memory.totalMb),
                usedMb = floatOr(MetricId.MEMORY_USED, previous.memory.usedMb),
                availableMb = floatOr(MetricId.MEMORY_AVAILABLE, previous.memory.availableMb),
                usageRatio = floatOr(MetricId.MEMORY_USAGE_RATIO, previous.memory.usageRatio),
                perApp = (m[MetricId.PER_APP_MEMORY] as? Metric<List<com.devperf.monitor.data.collectors.AppMemory>>)
                    ?: previous.memory.perApp,
            ),
            thermal = com.devperf.monitor.data.collectors.ThermalMetrics(
                deviceTempC = floatOr(MetricId.DEVICE_TEMP, previous.thermal.deviceTempC),
                status = thermalCollector.lastStatus,
                // 本次读到有效值则更新；否则沿用上一次可用值（不是假数据，
                // 而是"最后一次真实测量"，UI 侧由时间戳体现新鲜度）
                headroom = thermalCollector.lastHeadroom
                    ?.let { Metric.available(it, now) } ?: previous.thermal.headroom,
            ),
        )
    }

    private companion object {
        const val TAG = "MetricsRepository"
        const val PERSIST_INTERVAL_MS = 30_000L
    }
}
