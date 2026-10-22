package com.devperf.monitor.analysis

import com.devperf.monitor.ui.theme.DesignTokens

/**
 * 综合评判：基于四个核心指标算一个 0~100 的总分 + 评级 + 一句话结论。
 *
 * 三个维度：
 * - 流畅度（权重 0.40）：实际帧率 / 屏幕刷新率 的达成率。刷新率是这块屏幕的理论上限，
 *   帧率越接近它越流畅，这比单看 FPS 绝对值靠谱——30fps 在 30Hz 屏上是满帧，
 *   在 120Hz 屏上就是严重掉帧。
 * - 温度（权重 0.35）：设备温度，分段点来自 design-tokens.json（双端唯一真源）。
 * - 内存（权重 0.25）：占用率，50% 以下满分，100% 归零。
 *
 * 规则：
 * - 某个维度采不到就整体不参与，权重按比例分给其他维度，并标注"部分指标不可用"；
 * - 三个维度全不可用时不给分（null），不编造；
 * - 刷新率不可用时无法算达成率，流畅度维度直接缺席，不拿 60Hz 硬套。
 *
 * 注：权重没有走 design-tokens 的 health.weights，因为那里是四维度（含电池），
 * 而这里收敛成了三个维度；分段点和评级文案仍走 token，保证双端一致。
 */
object FocusedHealth {

    private const val W_SMOOTHNESS = 0.40f
    private const val W_TEMPERATURE = 0.35f
    private const val W_MEMORY = 0.25f

    data class Result(
        val total: Float?,
        val grade: HealthScorer.Grade?,
        val summary: String,
        val smoothnessScore: Float?,
        val temperatureScore: Float?,
        val memoryScore: Float?,
        /** 最短板维度（用于给出具体建议文案）。 */
        val weakest: Dimension?,
        /** 是否有维度缺席（剩余维度按比例吃下权重）。 */
        val partial: Boolean,
    )

    enum class Dimension(val label: String) {
        SMOOTHNESS("流畅度"),
        TEMPERATURE("温度"),
        MEMORY("内存"),
    }

    /**
     * @param memoryRatio 内存占用率百分比（0~100）
     * @param deviceTempC 设备温度 ℃
     * @param refreshHz   屏幕刷新率 Hz
     * @param fps         实时帧率
     */
    fun evaluate(
        memoryRatio: Float?,
        deviceTempC: Float?,
        refreshHz: Float?,
        fps: Float?,
    ): Result {
        val smoothness = smoothnessScore(fps, refreshHz)
        val temperature = deviceTempC?.let { HealthScorer.temperatureScore(it) }
        val memory = memoryRatio?.let { memoryScore(it) }

        // Triple(维度, 分数, 权重)
        val parts = buildList<Triple<Dimension, Float, Float>> {
            smoothness?.let { add(Triple(Dimension.SMOOTHNESS, it, W_SMOOTHNESS)) }
            temperature?.let { add(Triple(Dimension.TEMPERATURE, it, W_TEMPERATURE)) }
            memory?.let { add(Triple(Dimension.MEMORY, it, W_MEMORY)) }
        }

        if (parts.isEmpty()) {
            return Result(
                total = null,
                grade = null,
                summary = "数据不足，无法评判",
                smoothnessScore = null,
                temperatureScore = null,
                memoryScore = null,
                weakest = null,
                partial = true,
            )
        }

        val weightSum = parts.sumOf { it.third.toDouble() }.toFloat()
        val total = parts.sumOf { (it.second * it.third).toDouble() }.toFloat() / weightSum
        val clamped = total.coerceIn(0f, 100f)

        val weakest = parts.minByOrNull { it.second }?.first
        val summary = buildSummary(clamped, weakest, parts.size < 3)

        return Result(
            total = clamped,
            grade = HealthScorer.gradeOf(clamped),
            summary = summary,
            smoothnessScore = smoothness,
            temperatureScore = temperature,
            memoryScore = memory,
            weakest = weakest,
            partial = parts.size < 3,
        )
    }

    /**
     * 流畅度分：帧率 / 刷新率的达成率分段映射。
     * ≥95% 满分；80%~95% 线性降到 70；50%~80% 降到 30；低于 50% 线性归零。
     */
    fun smoothnessScore(fps: Float?, refreshHz: Float?): Float? {
        if (fps == null || refreshHz == null || refreshHz <= 0f) return null
        val r = (fps / refreshHz).coerceIn(0f, 1.2f)
        val score = when {
            r >= 0.95f -> 100f
            r >= 0.80f -> 70f + (r - 0.80f) / 0.15f * 30f
            r >= 0.50f -> 30f + (r - 0.50f) / 0.30f * 40f
            else -> (r / 0.50f) * 30f
        }
        return score.coerceIn(0f, 100f)
    }

    /** 内存分：50% 及以下满分，100% 归零，中间线性。 */
    fun memoryScore(ratioPercent: Float): Float {
        val r = (ratioPercent / 100f).coerceIn(0f, 1f)
        return (100f - (r - 0.5f) * 200f).coerceIn(0f, 100f)
    }

    private fun buildSummary(total: Float, weakest: Dimension?, partial: Boolean): String {
        val head = when {
            total >= 90f -> "状态很好"
            total >= 75f -> "状态良好"
            total >= 60f -> "能用，但有点吃力"
            total >= 40f -> "压力偏大"
            else -> "压力较大"
        }
        val tail = when (weakest) {
            Dimension.SMOOTHNESS -> "短板是流畅度，帧率没跑满屏幕刷新率"
            Dimension.TEMPERATURE -> "短板是温度"
            Dimension.MEMORY -> "短板是内存占用"
            null -> ""
        }
        val note = if (partial) "（部分指标不可用，分数按可用项折算）" else ""
        return listOf(head, tail).filter { it.isNotBlank() }.joinToString("，") + note
    }

    /** 评级文案走 token，避免双端文案漂移。 */
    fun gradeLabel(total: Float): String =
        DesignTokens.Health.GRADES.getOrNull(
            HealthScorer.gradeOf(total).ordinal,
        )?.label ?: ""
}
