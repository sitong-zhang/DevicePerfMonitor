package com.devperf.monitor.core

/**
 * 全部采集指标的定义表。
 * label/unit/decimals 用于 UI 与悬浮窗；colorKey 指向 design-tokens.json 中的语义色
 * （accent / accentSecondary / success / warning / danger），保证双端配色唯一真源。
 */
enum class MetricCategory(val label: String) {
    DISPLAY("显示"),
    CPU("处理器"),
    GPU("图形"),
    BATTERY("电池"),
    MEMORY("内存"),
    THERMAL("温度"),
}

enum class MetricId(
    val label: String,
    val unit: String,
    val category: MetricCategory,
    val decimals: Int,
    val colorKey: String,
    /** 是否为首页四分卡片关注的核心指标。 */
    val featured: Boolean = false,
) {
    // ---- 显示系统 ----
    FPS("实时 FPS", "fps", MetricCategory.DISPLAY, 0, "accent", featured = true),
    GLOBAL_FPS("全局 FPS", "fps", MetricCategory.DISPLAY, 0, "accent"),
    FRAME_TIME("帧时间", "ms", MetricCategory.DISPLAY, 1, "accentSecondary"),
    FRAME_STDEV("帧时间标准差", "ms", MetricCategory.DISPLAY, 1, "accentSecondary"),
    JANK_FRAMES("掉帧计数", "帧", MetricCategory.DISPLAY, 0, "warning"),
    JANK_RATIO("掉帧率", "%", MetricCategory.DISPLAY, 1, "warning"),
    REFRESH_RATE("屏幕刷新率", "Hz", MetricCategory.DISPLAY, 0, "accent"),

    // ---- CPU ----
    CPU_USAGE("CPU 使用率", "%", MetricCategory.CPU, 0, "accent", featured = true),
    CPU_CORE_USAGE("各核心使用率", "%", MetricCategory.CPU, 0, "accentSecondary"),
    CPU_CORE_FREQ("各核心频率", "MHz", MetricCategory.CPU, 0, "accentSecondary"),
    CPU_TEMP("CPU 温度", "℃", MetricCategory.THERMAL, 1, "danger", featured = true),

    // ---- GPU ----
    GPU_LOAD("GPU 负载", "%", MetricCategory.GPU, 0, "accent"),
    GPU_FREQ("GPU 频率", "MHz", MetricCategory.GPU, 0, "accentSecondary"),
    GPU_TEMP("GPU 温度", "℃", MetricCategory.THERMAL, 1, "danger"),

    // ---- 电池 ----
    BATTERY_TEMP("电池温度", "℃", MetricCategory.BATTERY, 1, "danger", featured = true),
    BATTERY_CURRENT("电池电流", "mA", MetricCategory.BATTERY, 0, "warning"),
    BATTERY_VOLTAGE("电池电压", "V", MetricCategory.BATTERY, 2, "accentSecondary"),
    BATTERY_LEVEL("电量", "%", MetricCategory.BATTERY, 0, "success", featured = true),
    BATTERY_HEALTH("电池健康", "%", MetricCategory.BATTERY, 0, "success"),

    // ---- 内存 ----
    MEMORY_TOTAL("总内存", "MB", MetricCategory.MEMORY, 0, "accent"),
    MEMORY_USED("已用内存", "MB", MetricCategory.MEMORY, 0, "warning"),
    MEMORY_AVAILABLE("可用内存", "MB", MetricCategory.MEMORY, 0, "success"),
    MEMORY_USAGE_RATIO("内存占用率", "%", MetricCategory.MEMORY, 0, "warning", featured = true),
    PER_APP_MEMORY("后台应用内存", "MB", MetricCategory.MEMORY, 0, "accentSecondary"),

    // ---- 设备温度 ----
    DEVICE_TEMP("机身温度", "℃", MetricCategory.THERMAL, 1, "danger"),
    ;

    companion object {
        val historyTracked: List<MetricId> = listOf(
            FPS, GLOBAL_FPS, JANK_RATIO, CPU_USAGE, CPU_TEMP, GPU_LOAD, GPU_TEMP,
            BATTERY_TEMP, BATTERY_CURRENT, BATTERY_LEVEL,
            MEMORY_USED, MEMORY_USAGE_RATIO, DEVICE_TEMP,
        )
    }
}
