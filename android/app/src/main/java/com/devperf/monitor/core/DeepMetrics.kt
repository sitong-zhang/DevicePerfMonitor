package com.devperf.monitor.core

/**
 * 深度监测数据模型（用户端全量监测项）。
 *
 * 与核心指标（[Metric] 三态）并行存在，专门承载「细粒度、多来源、量大」的监测项：
 * CPU 调度器 / PMU / 调频 / 中断、GPU 驱动计数器、显示 HWC、内存回收、电源轨、
 * UFS、网络细分、音频 DSP、风扇、进程系统扩展等。
 *
 * 取值约定（严格执行，禁止假数据冒充真实数据）：
 * - [DeepRow.value] 为空 → 该项不可读，[DeepRow.reason] 必须给出真实原因；
 * - [DeepRow.estimated] 为 true → 该值由其他可读数据**推算**得出，UI 必须标注「推算」，
 *   并且 [DeepRow.reason] 写明推算依据（公式 / 系数来源）。
 */
enum class DeepCategory(val label: String) {
    CPU("CPU"),
    SCHED("调度器"),
    PMU("硬件计数器"),
    IRQ("中断"),
    GOVERNOR("调频"),
    GPU("GPU"),
    DISPLAY("显示"),
    FPS("帧率"),
    MEMORY("内存"),
    POWER("功耗"),
    STORAGE("存储"),
    NETWORK("网络"),
    AUDIO("音频"),
    THERMAL("温度"),
    PROCESS("进程"),
    DDR("DDR"),
}

/** 取值来源：真实读取 / 推算 / 不可用。 */
enum class DeepOrigin { MEASURED, ESTIMATED, UNAVAILABLE }

data class DeepRow(
    val key: String,
    val label: String,
    val value: String? = null,
    val unit: String = "",
    val origin: DeepOrigin = DeepOrigin.UNAVAILABLE,
    /** 不可读原因，或推算依据（公式 / 系数）。 */
    val note: String? = null,
) {
    val isEstimated: Boolean get() = origin == DeepOrigin.ESTIMATED
}

data class DeepSection(
    val category: DeepCategory,
    val rows: List<DeepRow>,
)

data class DeepSnapshot(
    val timestampMs: Long,
    val sections: List<DeepSection>,
) {
    companion object {
        fun empty(at: Long = 0L): DeepSnapshot = DeepSnapshot(at, emptyList())
    }
}

/** 采集过程中的行构造器：负责「读不到就如实标注」的统一收口。 */
class DeepBuilder(private val category: DeepCategory) {

    private val rows = ArrayList<DeepRow>()

    /** 真实读取成功。 */
    fun real(key: String, label: String, value: Any?, unit: String = "", note: String? = null) {
        rows += DeepRow(key, label, format(value), unit, DeepOrigin.MEASURED, note)
    }

    /** 由其他可读数据推算，note 必须写清推算依据。 */
    fun estimate(key: String, label: String, value: Any?, unit: String = "", note: String) {
        rows += DeepRow(key, label, format(value), unit, DeepOrigin.ESTIMATED, note)
    }

    /** 无法读取且不臆造：给出真实原因。 */
    fun missing(key: String, label: String, reason: String, unit: String = "") {
        rows += DeepRow(key, label, null, unit, DeepOrigin.UNAVAILABLE, reason)
    }

    /** 仅在 value 非空时记为真实值，否则记为不可用。 */
    fun realOrMissing(
        key: String,
        label: String,
        value: Any?,
        unit: String = "",
        reason: String = "系统未开放该节点",
        note: String? = null,
    ) {
        if (value == null) missing(key, label, reason, unit) else real(key, label, value, unit, note)
    }

    fun build(): DeepSection = DeepSection(category, rows)

    private fun format(value: Any?): String = when (value) {
        null -> "—"
        is Float -> if (value.isNaN() || value.isInfinite()) "—" else trim(value.toDouble())
        is Double -> if (value.isNaN() || value.isInfinite()) "—" else trim(value)
        else -> value.toString()
    }

    private fun trim(v: Double): String {
        val r = Math.round(v * 100.0) / 100.0
        return if (r == Math.floor(r) && Math.abs(r) < 1.0E9) r.toLong().toString() else r.toString()
    }
}
