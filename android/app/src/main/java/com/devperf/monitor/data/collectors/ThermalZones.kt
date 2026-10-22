package com.devperf.monitor.data.collectors

import android.util.Log
import java.io.File

/**
 * 温区（thermal zone）读取器：双端采集共用的底层数据源。
 *
 * 输出 [(type, tempC)]：
 * - 来源：/sys/class/thermal/thermal_zoneN/{type,temp}；
 * - temp 原始单位随内核/厂商而异（毫℃ / ℃ / 0.1℃），按量级归一化到 ℃；
 * - 完全读不到时返回空列表，调用方按 UNAVAILABLE 处理。
 */
object ThermalZones {

    fun read(): List<Pair<String, Float>> {
        val dir = File("/sys/class/thermal")
        val zoneDirs = runCatching {
            dir.listFiles { f -> f.name.startsWith("thermal_zone") }?.sortedBy { it.name }
        }.getOrNull() ?: return emptyList()

        return zoneDirs.mapNotNull { zone ->
            val type = runCatching { File(zone, "type").readText().trim() }.getOrNull()
                ?: return@mapNotNull null
            val raw = runCatching { File(zone, "temp").readText().trim().toLongOrNull() }.getOrNull()
                ?: return@mapNotNull null
            val celsius = normalize(raw) ?: return@mapNotNull null
            type to celsius
        }
    }

    /** 按量级将原始读数归一化到 ℃。 */
    fun normalize(raw: Long): Float? = when {
        raw > 100_000L -> raw / 1000f   // 微℃（个别内核）
        raw > 1_000L -> raw / 1000f     // 毫℃（最常见）
        raw > 0L -> raw.toFloat()       // ℃ / 0.1℃
        else -> null
    }?.takeIf { it in -20f..200f }

    /** 诊断用：温区数量（0 表示完全不可读）。 */
    fun debugCount(): Int = runCatching { read().size }
        .onFailure { Log.w("ThermalZones", "读取温区失败", it) }
        .getOrDefault(0)
}
