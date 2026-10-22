package com.devperf.monitor.data.storage

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 异常值滤波：滑动窗口中位数 + 3σ 判据。
 *
 * 计算逻辑：
 * 1. 新样本 x 进入容量为 [windowSize] 的滑动窗口 W；
 * 2. 计算 W 的中位数 m 与总体标准差 σ；
 * 3. 若 σ > [EPSILON] 且 |x - m| > 3σ，判定 x 为跳变异常值，输出中位数 m（抑制尖刺）；
 *    否则输出 x（保持响应性，避免引入滞后）。
 *
 * 用途：温度 / 帧率 / 电流等易跳变指标在写入历史与 UI 前统一经过本滤波，
 * 防止偶发的 sysfs 读取错误或系统抖动导致曲线失真。
 */
class SignalFilter(private val windowSize: Int = 5) {

    private val window = ArrayDeque<Float>(windowSize)

    /** 输入原始样本，返回滤波后的值。 */
    fun filter(x: Float): Float {
        if (!x.isFinite()) return lastMedianOr(x)
        window.addLast(x)
        while (window.size > windowSize) window.removeFirst()

        val sorted = window.sorted()
        val median = sorted[sorted.size / 2]
        if (window.size < 3) return x

        val mean = window.average().toFloat()
        val variance = window.map { (it - mean) * (it - mean) }.average().toFloat()
        val stdev = sqrt(variance.toDouble()).toFloat()

        return if (stdev > EPSILON && abs(x - median) > 3f * stdev) {
            median
        } else {
            x
        }
    }

    private fun lastMedianOr(fallback: Float): Float =
        if (window.isEmpty()) fallback
        else window.sorted()[window.size / 2]

    fun reset() = window.clear()

    private companion object {
        const val EPSILON = 1e-4f
    }
}
