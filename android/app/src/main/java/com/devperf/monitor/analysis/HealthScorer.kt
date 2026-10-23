package com.devperf.monitor.analysis

import com.devperf.monitor.ui.theme.DesignTokens

/**
 * 共享评分零件：温度分段映射 + 评级档位。
 *
 * 综合评判的公式在 [FocusedHealth]（四个核心指标 → 流畅度 / 温度 / 内存三维度），
 * 这里只保留两个与维度无关的公共零件，分段参数双端唯一真源：
 * - 温度分：按 [DesignTokens.Health.TEMPERATURE_BREAKPOINTS] 分段线性映射；
 * - 评级档位 [Grade]：序与 [DesignTokens.Health.GRADES] 一致（按 minScore 降序），
 *   文案由 token 提供，避免双端文案漂移。
 */
object HealthScorer {

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

    /** 评级：按 minScore 降序找到第一个满足的档位，都不满足给最低档。 */
    fun gradeOf(total: Float): Grade {
        val specs = DesignTokens.Health.GRADES
        val idx = specs.indexOfFirst { total >= it.minScore }
        return if (idx in specs.indices) Grade.entries[idx] else Grade.entries.last()
    }
}
