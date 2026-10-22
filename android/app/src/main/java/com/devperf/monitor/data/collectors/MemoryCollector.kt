package com.devperf.monitor.data.collectors

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.devperf.monitor.core.Metric
import com.devperf.monitor.core.MetricId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 内存采集器。
 *
 * 数据来源与三态：
 * - 总/已用/可用 RAM：ActivityManager.MemoryInfo（公开 API，始终可用）；
 * - 已用 = total - avail（avail 含可回收缓存，与系统"内存"页口径一致）；
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
        val info = ActivityManager.MemoryInfo()
        // getMemoryInfo 返回 Unit，runCatching 结果为 Result<Unit>；
        // 用 isSuccess 判定（getOrDefault(false) 会因公共超类型推断失败）
        val ok = runCatching { activityManager.getMemoryInfo(info) }.isSuccess

        val totalMb = if (ok && info.totalMem > 0L) info.totalMem / (1024.0 * 1024.0) else null
        val availMb = if (ok && info.availMem >= 0L) info.availMem / (1024.0 * 1024.0) else null
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
