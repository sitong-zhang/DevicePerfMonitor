package com.devperf.monitor.analysis

import com.devperf.monitor.data.collectors.DeviceSnapshot
import com.devperf.monitor.ui.theme.DesignTokens
import kotlin.math.max
import kotlin.math.min

/**
 * 综合健康分引擎（本地纯算法，不联网、不调用大模型）。
 *
 * 公式（参数全部来自 design-tokens.json 的 health 段，双端唯一真源，
 * 由 generateDesignTokens 任务生成为 [DesignTokens.Health]）：
 *   总分 = 温度分×0.4 + 帧率稳定性分×0.3 + 电池分×0.2 + 内存分×0.1
 *
 * - 温度分：取 CPU 温度与电池温度的较大值 T，按
 *     [DesignTokens.Health.TEMPERATURE_BREAKPOINTS] 分段线性映射；
 * - 帧率稳定性分：全局 FPS 可用时取最近 30s 掉帧率 D，S = max(0, 100 - D×500)；
 *     不可用时用本 App 帧时间标准差 σ，S = max(0, 100 - σ×10)，并标记为"参考值"；
 * - 电池分：基础 100，按温度/电流/电量/健康度扣减，clamp 0~100；
 * - 内存分：M = 已用/总量，S = max(0, 100 - (M-0.5)×200)；
 * - 任一维度不可用：其权重按比例分配给其余可用维度，UI 标注"部分指标不可用"；
 * - 全维度不可用：总分 null，UI 显示"数据不足，无法分析"，禁止编造分数。
 */
object HealthScorer {

    enum class DimensionId(val label: String) {
        TEMPERATURE("温度"),
        FRAME_STABILITY("帧率稳定性"),
        BATTERY("电池"),
        MEMORY("内存"),
    }

    /**
     * 评级档位。序与 [DesignTokens.Health.GRADES] 一致（按 minScore 降序），
     * 因此可直接按下标互转；文案由 token 提供，避免双端文案漂移。
     */
    enum class Grade {
        EXCELLENT, GOOD, FAIR, WARNING;

        /** 文案来自 design-tokens.json 的 health.grades。 */
        val label: String
            get() = DesignTokens.Health.GRADES.getOrNull(ordinal)?.label ?: name
    }

    data class DimensionScore(
        val id: DimensionId,
        val score: Float?,
        val weight: Float,
        /** 帧率稳定性降级为帧时间标准差时标记为参考值。 */
        val isReference: Boolean = false,
    )

    data class HealthResult(
        val total: Float?,
        val grade: Grade?,
        val dimensions: List<DimensionScore>,
        val partial: Boolean,
    )

    const val FRAME_WINDOW_SEC = 30

    // ------------------------------------------------------------------
    // 各维度原始评分
    // ------------------------------------------------------------------

    /**
     * 温度分：按 token 分段线性映射。
     * 例如 token 为 [(40,100,100),(50,100,70),(60,70,35),(70,35,10),(null,0,0)] 时：
     * T≤40 → 100；(40,50] → 100→70；(50,60] → 70→35；(60,70] → 35→10；>70 → 0。
     */
    fun temperatureScore(t: Float): Float {
        val bps = DesignTokens.Health.TEMPERATURE_BREAKPOINTS
        for (i in bps.indices) {
            val bp = bps[i]
            val upper = bp.maxTempC ?: return bp.toScore.coerceIn(0f, 100f)
            if (t <= upper) {
                if (bp.fromScore == bp.toScore) return bp.fromScore.coerceIn(0f, 100f)
                val lower = bps.getOrNull(i - 1)?.maxTempC ?: Float.NEGATIVE_INFINITY
                val span = upper - lower
                if (!span.isFinite() || span <= 0f) return bp.toScore.coerceIn(0f, 100f)
                val ratio = ((t - lower) / span).coerceIn(0f, 1f)
                return (bp.fromScore + (bp.toScore - bp.fromScore) * ratio).coerceIn(0f, 100f)
            }
        }
        return 0f
    }

    /**
     * 帧率稳定性分。
     * @param jankRatioPercent 全局 FPS 可用时的 30s 掉帧率（百分比，0~100）
     * @param frameStdevMs     降级路径：本 App 帧时间标准差（ms）
     * @return (分数, 是否为参考值)；两者都不可用时返回 (null, false)
     */
    fun frameStabilityScore(
        jankRatioPercent: Float?,
        frameStdevMs: Float?,
    ): Pair<Float?, Boolean> = when {
        jankRatioPercent != null -> {
            // D 为掉帧率（0~1）：S = max(0, 100 - D×500)，即 0% 得 100、20% 得 0
            val d = (jankRatioPercent / 100f).coerceIn(0f, 1f)
            max(0f, 100f - d * 500f) to false
        }
        frameStdevMs != null -> {
            // S = max(0, 100 - σ×10)，σ 单位为 ms
            max(0f, 100f - frameStdevMs * 10f) to true
        }
        else -> null to false
    }

    /**
     * 电池分：基础 100 分，按以下规则扣减后 clamp 0~100：
     * - 电池温度 > 40℃：每超 1℃ 扣 5 分（最多扣 40）；
     * - 充电电流 > 3000mA：扣 15；
     * - 放电电流 > 1500mA：扣 10；
     * - 电量 < 20%：扣 10；
     * - 电池健康 < 80%（若可读）：扣 15。
     */
    fun batteryScore(
        temperatureC: Float?,
        currentMa: Float?,
        charging: Boolean,
        levelPercent: Float?,
        healthPercent: Float?,
    ): Float? {
        if (temperatureC == null && levelPercent == null) return null
        var score = 100f
        if (temperatureC != null && temperatureC > 40f) {
            score -= min(40f, (temperatureC - 40f) * 5f)
        }
        if (charging && currentMa != null && currentMa > 3000f) score -= 15f
        if (!charging && currentMa != null && currentMa < -1500f) score -= 10f
        if (levelPercent != null && levelPercent < 20f) score -= 10f
        if (healthPercent != null && healthPercent < 80f) score -= 15f
        return score.coerceIn(0f, 100f)
    }

    /** 内存分：M = 已用/总量，S = max(0, 100 - (M-0.5)×200)，即 50% 得 100、100% 得 0。 */
    fun memoryScore(usedMb: Float?, totalMb: Float?): Float? {
        if (usedMb == null || totalMb == null || totalMb <= 0f) return null
        val ratio = (usedMb / totalMb).coerceIn(0f, 1f)
        return max(0f, 100f - (ratio - 0.5f) * 200f).coerceIn(0f, 100f)
    }

    // ------------------------------------------------------------------
    // 综合计算
    // ------------------------------------------------------------------

    fun score(snapshot: DeviceSnapshot): HealthResult {
        // 温度维度：CPU 温度与电池温度取较大值（两者至少一个可用才参与评分）
        val cpuTemp = snapshot.cpu.temperatureC.value
        val battTemp = snapshot.battery.temperatureC.value
        val maxTemp = listOfNotNull(cpuTemp, battTemp).maxOrNull()
        val temperature = maxTemp?.let { temperatureScore(it) }

        // 帧率稳定性：全局 FPS 可用时使用 30s 掉帧率；否则降级为帧时间标准差（参考值）
        val jankRatio = if (snapshot.display.globalFps.isAvailable) {
            snapshot.display.jankRatio30s.value
        } else null
        val (frameScore, frameIsReference) = frameStabilityScore(
            jankRatioPercent = jankRatio,
            frameStdevMs = snapshot.display.frameStdevMs.value,
        )

        val battery = batteryScore(
            temperatureC = battTemp,
            currentMa = snapshot.battery.currentMa.value,
            charging = snapshot.battery.chargingState == com.devperf.monitor.data.collectors.ChargingState.CHARGING,
            levelPercent = snapshot.battery.levelPercent.value,
            healthPercent = snapshot.battery.healthPercent.value,
        )

        val memory = memoryScore(
            usedMb = snapshot.memory.usedMb.value,
            totalMb = snapshot.memory.totalMb.value,
        )

        val dimensions = listOf(
            DimensionScore(DimensionId.TEMPERATURE, temperature, DesignTokens.Health.Weights.TEMPERATURE),
            DimensionScore(DimensionId.FRAME_STABILITY, frameScore, DesignTokens.Health.Weights.FRAME_STABILITY, frameIsReference),
            DimensionScore(DimensionId.BATTERY, battery, DesignTokens.Health.Weights.BATTERY),
            DimensionScore(DimensionId.MEMORY, memory, DesignTokens.Health.Weights.MEMORY),
        )

        val available = dimensions.filter { it.score != null }
        if (available.isEmpty()) {
            return HealthResult(
                total = null,
                grade = null,
                dimensions = dimensions,
                partial = true,
            )
        }
        val weightSum = available.sumOf { it.weight.toDouble() }.toFloat()
        val total = available.sumOf { (it.score!! * it.weight).toDouble() }.toFloat() / weightSum

        return HealthResult(
            total = total.coerceIn(0f, 100f),
            grade = gradeOf(total),
            dimensions = dimensions,
            partial = available.size < dimensions.size,
        )
    }

    /** 从快照中取最近 30s 掉帧率（百分比）。全局 FPS 不可用时返回 null。 */
    fun gradeOf(total: Float): Grade {
        val specs = DesignTokens.Health.GRADES
        val idx = specs.indexOfFirst { total >= it.minScore }
        return if (idx in specs.indices) Grade.entries[idx] else Grade.entries.last()
    }
}
