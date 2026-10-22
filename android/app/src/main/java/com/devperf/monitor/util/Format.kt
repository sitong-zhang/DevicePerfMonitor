package com.devperf.monitor.util

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** 数值格式化工具：统一双端展示风格，数字一律保留固定小数位（配合等宽字体不晃动）。 */
object Format {

    fun value(v: Float, decimals: Int): String =
        String.format(Locale.US, "%.${decimals}f", v)

    /** 温度：1 位小数。 */
    fun temp(v: Float): String = String.format(Locale.US, "%.1f℃", v)

    /** 百分比：0 位小数。 */
    fun percent(v: Float): String = "${v.roundToInt()}%"

    /** 电流：0 位小数，正=充电、负=放电，展示时带方向。 */
    fun current(v: Float): String = "${v.roundToInt()}mA"

    /** 内存容量 MB：≥1024MB 显示为 GB。 */
    fun memory(mb: Float): String =
        if (mb >= 1024f) String.format(Locale.US, "%.1fGB", mb / 1024f)
        else "${mb.roundToInt()}MB"

    /** 相对时间：图表 X 轴使用（-30s / -1min 等）。 */
    fun relativeSeconds(seconds: Int): String = when {
        seconds < 60 -> "-${seconds}s"
        seconds % 60 == 0 -> "-${seconds / 60}min"
        else -> "-${seconds / 60}min${seconds % 60}s"
    }

    /** 预计降温时长（分钟）。 */
    fun coolingMinutes(minutes: Float): String =
        if (minutes < 1f) "不到 1 分钟"
        else if (minutes < 60f) "${minutes.roundToInt()} 分钟"
        else String.format(Locale.US, "%.1f 小时", minutes / 60f)

    /** 时间戳 → HH:mm:ss。 */
    fun clock(ms: Long): String {
        val total = ms / 1000
        val s = total % 60
        val m = (total / 60) % 60
        val h = (total / 3600) % 24
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    /** 安全除法：分母为 0 时返回 null（调用方按"数据不足"处理，禁止编造）。 */
    fun ratioOrNull(numerator: Float, denominator: Float): Float? =
        if (denominator <= 0f || numerator < 0f) null else numerator / denominator

    fun absOrNull(v: Float): Float? = if (v.isFinite()) abs(v) else null
}
