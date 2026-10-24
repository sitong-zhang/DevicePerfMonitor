package com.devperf.monitor.data.collectors

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.devperf.monitor.core.Metric
import com.devperf.monitor.core.MetricId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 内存采集器。
 *
 * 数据来源与三态：
 * - 总/可用 RAM：优先 /proc/meminfo（MemTotal + MemAvailable，普通应用可读）；
 * - **口径说明（1.0.10 修正）**：MemAvailable 是内核估算的"真正可用"内存
 *   （MemFree + 可回收 Buffers/Cached/SReclaimable - Shmem），与系统
 *   「设置 - 内存」页口径一致。1.0.9 及以前用 ActivityManager.availMem，
 *   它只算真正空闲、不含可回收缓存，同样一台机器占用率会偏高 5~15%
 *   （用户反馈"内存数据偏上"）；
 * - /proc/meminfo 读不到的极端情况回退 ActivityManager.MemoryInfo（旧口径）；
 * - 后台应用内存明细：需 root/Shizuku 解析 `dumpsys meminfo`，
 *   普通 App 自 Android 8 起无法读取其他进程内存 → UNAVAILABLE / NeedsPermission。
 */
class MemoryCollector(
    private val context: Context,
    private val shell: PrivilegedShell? = null,
) : MetricCollector {

    private val activityManager = context.getSystemService(ActivityManager::class.java)

    override fun start(scope: CoroutineScope) = Unit
    override fun stop() = Unit

    override suspend fun sample(): Map<MetricId, Metric<*>> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()

        // 首选 /proc/meminfo（MemAvailable 口径 = 系统内存页口径）；
        // 读不到再回退 ActivityManager（availMem 口径偏高，仅作兜底）
        val meminfo = readMeminfo()
        val amInfo = if (meminfo == null) readActivityManager() else null
        val totalMb = meminfo?.first ?: amInfo?.first
        val availMb = meminfo?.second ?: amInfo?.second

        val usedMb = if (totalMb != null && availMb != null) totalMb - availMb else null
        val ratio = if (totalMb != null && usedMb != null && totalMb > 0.0)
            (usedMb / totalMb * 100.0).toFloat() else null

        val perApp = readPerAppMemory()

        mapOf(
            MetricId.MEMORY_TOTAL to (totalMb?.toFloat()?.let { Metric.available(it, now) }
                ?: Metric.unavailable("无法读取内存信息", now)),
            MetricId.MEMORY_USED to (usedMb?.toFloat()?.let { Metric.available(it, now) }
                ?: Metric.unavailable("无法读取内存信息", now)),
            MetricId.MEMORY_AVAILABLE to (availMb?.toFloat()?.let { Metric.available(it, now) }
                ?: Metric.unavailable("无法读取内存信息", now)),
            MetricId.MEMORY_USAGE_RATIO to (ratio?.let { Metric.available(it, now) }
                ?: Metric.unavailable("无法读取内存信息", now)),
            MetricId.PER_APP_MEMORY to (perApp?.let { Metric.available(it, now) }
                ?: Metric.unavailable("后台应用内存需 root 或 Shizuku", now)),
        )
    }

    /**
     * /proc/meminfo 口径：返回 (总 MB, 可用 MB)。
     *
     * 可用优先取 MemAvailable（Linux 3.14+/Android API 19+ 内核均有）；
     * 字段缺失时按内核定义手算：MemFree + Buffers + Cached + SReclaimable - Shmem。
     * 该口径把可回收缓存算进"可用"，与系统「设置 - 内存」页一致。
     */
    private fun readMeminfo(): Pair<Double, Double>? {
        val text = runCatching { File("/proc/meminfo").readText() }.getOrNull() ?: return null
        var totalKb = 0L
        var availKb = -1L
        var freeKb = 0L
        var buffersKb = 0L
        var cachedKb = 0L
        var sReclaimableKb = 0L
        var shmemKb = 0L
        text.lineSequence().forEach { line ->
            val sep = line.indexOf(':')
            if (sep <= 0) return@forEach
            val key = line.substring(0, sep).trim()
            val value = line.substring(sep + 1).trim().split(' ').firstOrNull()?.toLongOrNull()
                ?: return@forEach
            when (key) {
                "MemTotal" -> totalKb = value
                "MemAvailable" -> availKb = value
                "MemFree" -> freeKb = value
                "Buffers" -> buffersKb = value
                "Cached" -> cachedKb = value
                "SReclaimable" -> sReclaimableKb = value
                "Shmem" -> shmemKb = value
            }
        }
        if (totalKb <= 0L) return null
        val availKbFinal = if (availKb >= 0L) {
            availKb
        } else {
            (freeKb + buffersKb + cachedKb + sReclaimableKb - shmemKb).coerceAtLeast(0L)
        }
        return (totalKb / 1024.0) to (availKbFinal / 1024.0)
    }

    /** ActivityManager 回退口径（availMem 不含可回收缓存，仅 /proc 读不到时用）。 */
    private fun readActivityManager(): Pair<Double, Double>? {
        val info = ActivityManager.MemoryInfo()
        // getMemoryInfo 返回 Unit，runCatching 结果为 Result<Unit>；
        // 用 isSuccess 判定（getOrDefault(false) 会因公共超类型推断失败）
        val ok = runCatching { activityManager.getMemoryInfo(info) }.isSuccess
        if (!ok || info.totalMem <= 0L || info.availMem < 0L) return null
        return (info.totalMem / (1024.0 * 1024.0)) to (info.availMem / (1024.0 * 1024.0))
    }

    /**
     * 解析 `dumpsys meminfo` 的 "Total PSS by process" 段，取 PSS Top 20 应用。
     * 行格式（随 Android 版本略有差异）：
     *   "  199234 kB: pid 2896 (/system/bin/surfaceflinger)"
     *   "   12345 kB: com.example.app (pid 1234)"
     * 解析不到任何条目时返回 null（→ UNAVAILABLE），不用 0 或估算值。
     */
    private suspend fun readPerAppMemory(): List<AppMemory>? {
        val backend = shell?.backend ?: return null
        if (backend == PrivilegedShell.Backend.NONE) return null
        val output = shell.run("dumpsys meminfo") ?: return null
        val result = LinkedHashMap<String, Float>()
        var inSection = false
        output.lineSequence().forEach { line ->
            when {
                line.contains("Total PSS by process", ignoreCase = true) -> inSection = true
                inSection && line.isBlank() && result.isNotEmpty() -> inSection = false
                inSection -> {
                    val match = PSS_LINE.find(line.trim())
                    if (match != null) {
                        val kb = match.groupValues[1].toFloatOrNull() ?: return@forEach
                        val process = match.groupValues[2].substringAfterLast('/')
                        val pkg = process.substringBefore(':')
                        if (pkg.isNotBlank() && pkg != "pid") {
                            result[pkg] = result.getOrDefault(pkg, 0f) + kb / 1024f
                        }
                    }
                }
            }
        }
        if (result.isEmpty()) {
            Log.w(TAG, "dumpsys meminfo 解析为空（ROM 输出格式差异）")
            return null
        }
        return result.entries
            .sortedByDescending { it.value }
            .take(20)
            .map { AppMemory(it.key, it.value) }
    }

    private companion object {
        const val TAG = "MemoryCollector"

        /** "199234 kB: pid 2896 (/system/bin/surfaceflinger)" 或 "12345 kB: com.example (pid 1234)" */
        val PSS_LINE = Regex("^(\\d+)\\s*kB:\\s*(?:pid\\s+\\d+\\s*)?\\(?([\\w.\\-/]+)")
    }
}
