package com.devperf.monitor.data.storage

import android.content.Context
import android.util.Log
import com.devperf.monitor.core.MetricId
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 单个历史采样点：[timestampMs] 为采样时刻，[value] 为滤波后的真实值。
 */
data class HistoryPoint(val timestampMs: Long, val value: Float)

/**
 * 秒级历史存储：每个指标保留最近 2 小时（7200 点）的环形序列，用于趋势图和回看。
 *
 * - 纯内存环形缓冲 + 可选本地落盘（org.json，无第三方依赖）；
 * - 落盘文件：filesDir/history/history.json，进程重启后自动恢复，可回看；
 * - 极简模式（低端机）下由调用方停止调用 [persist]，停历史落盘；
 * - 全程本地存储，不联网、不上传。
 *
 * 保留 2 小时而不是 30 分钟：既然采了就留得住，用户要能往回翻看刚才发生了什么。
 * 7200 点 × 4 个指标对 JSON 落盘来说是几百 KB 量级，本地读写可接受。
 */
class HistoryStore(private val context: Context) {

    private val series = ConcurrentHashMap<MetricId, ArrayDeque<HistoryPoint>>()
    private val filters = ConcurrentHashMap<MetricId, SignalFilter>()

    @Volatile
    var persistEnabled: Boolean = true

    private val lock = Any()

    /**
     * 从本地文件恢复历史（在 IO 协程中调用；构造时不做磁盘 IO，避免阻塞主线程）。
     */
    fun load() {
        runCatching {
            val file = historyFile()
            if (!file.exists()) return
            val root = JSONObject(file.readText())
            root.keys().forEach { key ->
                val id = runCatching { MetricId.valueOf(key) }.getOrNull() ?: return@forEach
                val arr = root.getJSONArray(key)
                val points = ArrayDeque<HistoryPoint>()
                var i = 0
                while (i + 1 < arr.length()) {
                    points.addLast(HistoryPoint(arr.getLong(i), arr.getDouble(i + 1).toFloat()))
                    i += 2
                }
                series[id] = points
            }
            Log.i(TAG, "历史数据已恢复：${series.size} 个指标")
        }.onFailure { Log.w(TAG, "历史数据加载失败（从空开始）", it) }
    }

    /** 记录一个样本（内部经过 [SignalFilter] 滤波）。 */
    fun put(id: MetricId, timestampMs: Long, rawValue: Float) {
        val filter = filters.getOrPut(id) { SignalFilter() }
        val value = filter.filter(rawValue)
        val points = series.getOrPut(id) { ArrayDeque() }
        synchronized(lock) {
            points.addLast(HistoryPoint(timestampMs, value))
            while (points.size > CAPACITY) points.removeFirst()
        }
    }

    /** 读取最近 [windowSeconds] 秒的序列（按时间升序）。 */
    fun series(id: MetricId, windowSeconds: Int): List<HistoryPoint> {
        val now = System.currentTimeMillis()
        val from = now - windowSeconds * 1000L
        val points = series[id] ?: return emptyList()
        return synchronized(lock) {
            points.filter { it.timestampMs >= from }.sortedBy { it.timestampMs }
        }
    }

    /** 全量序列（图表下载/导出用）。 */
    fun all(id: MetricId): List<HistoryPoint> =
        synchronized(lock) { series[id]?.toList() ?: emptyList() }

    fun clear() = synchronized(lock) {
        series.clear()
        filters.clear()
    }

    // ---------------------------------------------------------------------
    // 本地落盘
    // ---------------------------------------------------------------------

    private fun historyFile(): File {
        val dir = File(context.filesDir, "history")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "history.json")
    }

    /**
     * 将当前内存中的历史写入本地文件（原子替换）。
     * 由采集服务周期性调用；极简模式下不调用。
     */
    fun persist() {
        if (!persistEnabled) return
        runCatching {
            val root = JSONObject()
            series.forEach { (id, points) ->
                val arr = JSONArray()
                synchronized(lock) {
                    points.takeLast(CAPACITY).forEach { p ->
                        arr.put(p.timestampMs)
                        arr.put(p.value.toDouble())
                    }
                }
                root.put(id.name, arr)
            }
            val tmp = File(historyFile().absolutePath + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(historyFile())) {
                historyFile().writeText(root.toString())
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "历史落盘失败（不影响采集）", it) }
    }

    private companion object {
        const val TAG = "HistoryStore"

        /** 2 小时 × 60 秒 = 7200 个秒级采样点。 */
        const val CAPACITY = 2 * 60 * 60
    }
}
