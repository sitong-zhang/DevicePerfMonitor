package com.devperf.monitor.core

/**
 * 应用聚焦的四个核心指标。
 *
 * 之前把 CPU/GPU/电池/进程等十几个指标全铺在界面上，但绝大多数机型根本读不到
 * （Android 没有公开接口），界面上一片「不可用」，既没用又难看。
 * 现在整个应用只保留这四个每台机器都真实可读的指标，再加一个综合评判。
 *
 * 采集不到的指标不进界面，也不占位——这是硬性规则。
 */
enum class CoreMetricId(
    val label: String,
    val unit: String,
    /** 对应的底层采集指标。 */
    val source: MetricId,
    val decimals: Int,
    /** 该指标是"越高越好"（帧率/刷新率）还是"越低越好"（占用率/温度）。 */
    val higherIsBetter: Boolean,
) {
    MEMORY_RATIO("内存占用率", "%", MetricId.MEMORY_USAGE_RATIO, 0, higherIsBetter = false),
    DEVICE_TEMP("设备温度", "℃", MetricId.DEVICE_TEMP, 1, higherIsBetter = false),
    REFRESH_RATE("屏幕刷新率", "Hz", MetricId.REFRESH_RATE, 0, higherIsBetter = true),
    FPS("帧率", "Hz", MetricId.FPS, 0, higherIsBetter = true),
    ;

    companion object {
        /** 界面展示顺序（与声明顺序一致）。 */
        val ordered: List<CoreMetricId> = entries.toList()

        /** 参与历史轨迹记录的指标（刷新率基本恒定，也记，便于回看切换刷新率档位的时刻）。 */
        val historyTracked: List<MetricId> = ordered.map { it.source }
    }
}

/**
 * 指标的可视化模式。每个指标都能单独切换，选择会被记住。
 *
 * 参考 OpenMonitor / 系统监控类应用常见的几种读法：
 * - 数字：大号等宽数字，看瞬时值最准；
 * - 波形：线条上下波动（sparkline），看趋势和抖动；
 * - 环形：0~100% 有界指标（占用率、达成率）用环形仪表最直观；
 * - 柱状：离散采样条，看分布和突变。
 */
enum class MetricViewMode(val label: String, val needsBoundedRange: Boolean) {
    NUMBER("数字", needsBoundedRange = false),
    WAVE("波形", needsBoundedRange = false),
    RING("环形", needsBoundedRange = true),
    BARS("柱状", needsBoundedRange = false),
    ;

    companion object {
        val ordered: List<MetricViewMode> = entries.toList()
    }
}
