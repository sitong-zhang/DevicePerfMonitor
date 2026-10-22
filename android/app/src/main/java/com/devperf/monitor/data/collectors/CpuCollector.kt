package com.devperf.monitor.data.collectors

import android.util.Log
import com.devperf.monitor.core.Metric
import com.devperf.monitor.core.MetricId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * CPU 采集器。
 *
 * 数据来源与三态（详见 README 对照表）：
 * - 整体/各核心使用率：/proc/stat 两次采样差值（无需权限；个别 ROM 的 SELinux 策略
 *   会拒绝读取，此时降级 UNAVAILABLE）；
 * - 各核心频率：/sys/devices/system/cpu/cpuN/cpufreq/scaling_cur_freq，
 *   多数机型对普通 App 不可读（EACCES）→ UNAVAILABLE，root/Shizuku 下可用；
 * - CPU 温度：/sys/class/thermal/thermal_zoneN/{type,temp}，按温区类型匹配
 *   （cpu/tsens/soc/silver/gold/prime 等），取匹配温区最大值；
 *   注意：不同 ROM 的温区命名不统一，匹配不到 CPU 温区时显示不可用，不用电池温度冒充。
 */
class CpuCollector(
    private val shell: PrivilegedShell? = null,
) : MetricCollector {

    private data class CpuTicks(val idle: Long, val total: Long)

    private var previous: Map<String, CpuTicks> = emptyMap()

    override fun start(scope: CoroutineScope) = Unit
    override fun stop() = Unit

    override suspend fun sample(): Map<MetricId, Metric<*>> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val stat = readProcStat()
        val usage = computeUsage(stat)
        val perCore = usage.filterKeys { it != "cpu" }.map { (key, percent) ->
            CoreUsage(index = key.removePrefix("cpu").toIntOrNull() ?: -1, percent = percent)
        }.sortedBy { it.index }

        val freqs = readCoreFrequencies()
        val temp = readCpuTemperature()

        val usageMetric = usage["cpu"]?.let { Metric.available(it, now) }
            ?: Metric.unavailable("/proc/stat 不可读（ROM 限制）", now)

        val perCoreMetric = if (perCore.isNotEmpty()) Metric.available(perCore, now)
        else Metric.unavailable("/proc/stat 不可读（ROM 限制）", now)

        val freqMetric = if (freqs.isNotEmpty()) Metric.available(freqs, now)
        else Metric.unavailable("各核心频率需 root/Shizuku，或该机型不开放", now)

        mapOf(
            MetricId.CPU_USAGE to usageMetric,
            MetricId.CPU_CORE_USAGE to perCoreMetric,
            MetricId.CPU_CORE_FREQ to freqMetric,
            MetricId.CPU_TEMP to temp,
        )
    }

    /** 读取 /proc/stat，返回 cpu / cpu0..cpuN 的 idle 与 total ticks。 */
    private fun readProcStat(): Map<String, CpuTicks> {
        val result = LinkedHashMap<String, CpuTicks>()
        runCatching {
            File("/proc/stat").forEachLine { line ->
                if (!line.startsWith("cpu")) return@forEachLine
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 5) return@forEachLine
                val name = parts[0]
                val values = parts.drop(1).mapNotNull { it.toLongOrNull() }
                if (values.isEmpty()) return@forEachLine
                val idle = values.getOrElse(3) { 0L } + values.getOrElse(4) { 0L } // idle + iowait
                val total = values.sum()
                result[name] = CpuTicks(idle, total)
            }
        }.onFailure { Log.w(TAG, "读取 /proc/stat 失败", it) }
        return result
    }

    /** 与上一次采样比较，计算各核心使用率百分比。 */
    private fun computeUsage(current: Map<String, CpuTicks>): Map<String, Float> {
        if (current.isEmpty()) return emptyMap()
        val previousLocal = previous
        previous = current
        if (previousLocal.isEmpty()) return emptyMap()

        return current.mapNotNull { (name, now) ->
            val before = previousLocal[name] ?: return@mapNotNull null
            val totalDelta = now.total - before.total
            val idleDelta = now.idle - before.idle
            if (totalDelta <= 0L) return@mapNotNull null
            val usage = (1f - idleDelta.toFloat() / totalDelta.toFloat()) * 100f
            name to usage.coerceIn(0f, 100f)
        }.toMap()
    }

    /**
     * 各核心频率：优先直读 sysfs；不可读且具备特权时通过 shell 读取。
     * 注意：cpuinfo_max_freq（上限频率）不用于冒充实时频率。
     */
    private suspend fun readCoreFrequencies(): List<CoreFreq> {
        val cores = Runtime.getRuntime().availableProcessors()
        val freqs = ArrayList<CoreFreq>(cores)
        for (i in 0 until cores) {
            val mhz = readCurrentFreqKhz(i)?.let { it / 1000f }
            if (mhz != null && mhz > 0f) freqs += CoreFreq(i, mhz)
        }
        if (freqs.isEmpty() && shell != null && shell.backend != PrivilegedShell.Backend.NONE) {
            val output = shell.run("for i in $(seq 0 $(( $(getprop ro.board.cpu.corecount 2>/dev/null || echo $cores) - 1 ))); do cat /sys/devices/system/cpu/cpu\$i/cpufreq/scaling_cur_freq 2>/dev/null; done")
            output?.lineSequence()?.mapNotNull { it.trim().toFloatOrNull() }?.forEachIndexed { i, khz ->
                if (khz > 0f) freqs += CoreFreq(i, khz / 1000f)
            }
        }
        return freqs
    }

    private fun readCurrentFreqKhz(core: Int): Long? {
        val candidates = listOf(
            "/sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq",
            "/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_cur_freq",
        )
        for (path in candidates) {
            val value = runCatching { File(path).readText().trim().toLongOrNull() }.getOrNull()
            if (value != null && value > 0L) return value
        }
        return null
    }

    /**
     * CPU 温度：遍历 thermal zones，优先类型名包含 CPU/SoC 关键词的温区，取最大值。
     * 完全匹配不到时返回 Unavailable（不用 GPU 或电池温度冒充）。
     */
    private suspend fun readCpuTemperature(): Metric<Float> {
        val now = System.currentTimeMillis()
        val zones = ThermalZones.read()
        if (zones.isEmpty()) {
            return Metric.unavailable("thermal zone 不可读", now)
        }
        val cpuZones = zones.filter { (type, _) ->
            val t = type.lowercase()
            CPU_TYPE_KEYWORDS.any { t.contains(it) }
        }
        if (cpuZones.isEmpty()) {
            // 温区可读但没有 CPU 命名温区：不猜测，显示不可用
            return Metric.unavailable("未找到 CPU 温区（温区命名不统一）", now)
        }
        val maxC = cpuZones.maxOf { it.second }
        return Metric.available(maxC, now)
    }

    private companion object {
        const val TAG = "CpuCollector"
        val CPU_TYPE_KEYWORDS = listOf(
            "cpu", "tsens", "soc", "silver", "gold", "prime", "little", "big",
            "apc", "mtktscpu", "exynos", "cpu-0", "cpu-1",
        )
    }
}
