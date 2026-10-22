package com.devperf.monitor.data.collectors

import android.util.Log
import com.devperf.monitor.core.Metric
import com.devperf.monitor.core.MetricId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * GPU 采集器。
 *
 * 数据来源与三态（详见 README 对照表）：
 * - Adreno（高通）：/sys/class/kgsl/kgsl-3d0/gpubusy（累计 busy/total，按差值算负载）、
 *   devfreq/cur_freq（频率）、temp（温度）；
 * - Mali（ARM）：/sys/class/misc/mali0/utilisation；
 * - 以上 sysfs 节点在绝大多数机型上对普通 App 不可读（SELinux/权限），
 *   读不到时一律 UNAVAILABLE，禁止用 CPU 负载或固定值估算冒充。
 */
class GpuCollector(
    private val shell: PrivilegedShell? = null,
) : MetricCollector {

    private data class BusySample(val busy: Long, val total: Long)

    private var previousBusy: BusySample? = null

    override fun start(scope: CoroutineScope) = Unit
    override fun stop() = Unit

    override suspend fun sample(): Map<MetricId, Metric<*>> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val load = readLoadPercent()
        val freq = readFreqMHz()
        val temp = readTemperatureC()

        mapOf(
            MetricId.GPU_LOAD to (load?.let { Metric.available(it, now) }
                ?: Metric.unavailable("GPU 负载节点不可读（需 root/Shizuku 或机型不支持）", now)),
            MetricId.GPU_FREQ to (freq?.let { Metric.available(it, now) }
                ?: Metric.unavailable("GPU 频率节点不可读", now)),
            MetricId.GPU_TEMP to (temp?.let { Metric.available(it, now) }
                ?: Metric.unavailable("GPU 温度节点不可读", now)),
        )
    }

    /** Adreno gpubusy："<busy> <total>" 累计计数，负载 = Δbusy / Δtotal。 */
    private suspend fun readLoadPercent(): Float? {
        val raw = readFile("/sys/class/kgsl/kgsl-3d0/gpubusy") ?: return null
        val parts = raw.trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
        if (parts.size < 2 || parts[1] <= 0L) return null
        val current = BusySample(parts[0], parts[1])
        val previous = previousBusy
        previousBusy = current
        if (previous == null) return null // 首次采样只建立基线
        val busyDelta = current.busy - previous.busy
        val totalDelta = current.total - previous.total
        if (totalDelta <= 0L) return null
        return (busyDelta.toFloat() / totalDelta.toFloat() * 100f).coerceIn(0f, 100f)
    }

    private suspend fun readFreqMHz(): Float? {
        // Adreno devfreq（Hz）；部分机型为 kHz，按量级判断
        val raw = readFile("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq") ?: return null
        val value = raw.trim().toLongOrNull() ?: return null
        if (value <= 0L) return null
        return if (value > 1_000_000L) value / 1_000_000f else value / 1000f
    }

    private suspend fun readTemperatureC(): Float? {
        // Adreno 独立温度节点
        readFile("/sys/class/kgsl/kgsl-3d0/temp")?.trim()?.toLongOrNull()?.let { raw ->
            if (raw > 0L) return normalizeCelsius(raw)
        }
        // 回退：thermal zone 中类型含 gpu 的温区
        ThermalZones.read().firstOrNull { (type, _) -> type.lowercase().contains("gpu") }
            ?.let { return it.second }
        return null
    }

    /** 直读文件；失败时若具备特权再尝试 shell 读取。 */
    private suspend fun readFile(path: String): String? {
        val direct = runCatching { File(path).readText() }.getOrNull()
        if (direct != null) return direct
        if (shell != null && shell.backend != PrivilegedShell.Backend.NONE) {
            return shell.run("cat $path")
        }
        return null
    }

    private fun normalizeCelsius(raw: Long): Float? = when {
        raw > 100_000L -> raw / 1000f
        raw > 1_000L -> raw / 1000f
        raw > 0L -> raw.toFloat()
        else -> null
    }?.takeIf { it in 0.1f..200f }

    init {
        Log.d(TAG, "GpuCollector 初始化（Adreno/Mali sysfs，多机型需特权）")
    }

    private companion object {
        const val TAG = "GpuCollector"
    }
}
