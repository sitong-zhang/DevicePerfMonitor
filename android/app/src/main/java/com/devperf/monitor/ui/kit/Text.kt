package com.devperf.monitor.ui.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import com.devperf.monitor.ui.theme.DesignTokens
import com.devperf.monitor.ui.theme.JetBrainsMonoFamily
import com.devperf.monitor.ui.theme.LocalTokenColors
import com.devperf.monitor.util.Format

/**
 * 数值文本。
 *
 * 1. 所有数值一律使用 JetBrains Mono 等宽字体（随工程打包，OFL）：
 *    数字位宽一致，刷新时不产生横向抖动；
 * 2. 数值动画打断策略（需求硬性规定）：
 *    单次补间时长 = min(300ms, 采集间隔 - 50ms)；
 *    新值到达时从「当前动画值」继续补间（打断不跳变）；
 * 3. 采不到的指标不进界面（调用方过滤），这里不再提供「—」占位分支——
 *    看不到的指标就当它不存在，不用占位符假装有。
 */

// ---------------------------------------------------------------------------
// 等宽数值
// ---------------------------------------------------------------------------

@Composable
private fun MonoNumber(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalTokenColors.current.textPrimary,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.headlineSmall,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        style = style,
        fontFamily = JetBrainsMonoFamily,
        textAlign = TextAlign.End,
        maxLines = 1,
    )
}

// ---------------------------------------------------------------------------
// 数值动画（打断策略：min(300ms, 间隔-50ms)）
// ---------------------------------------------------------------------------

/**
 * 带动画的等宽数值。
 *
 * @param value 目标值（null = 采不到；此时什么都不画，由调用方决定整体是否展示）
 * @param intervalMs 采集间隔（500 / 1000 / 2000ms），用于计算补间时长
 */
@Composable
fun AnimatedNumber(
    value: Float?,
    intervalMs: Long,
    modifier: Modifier = Modifier,
    decimals: Int = 0,
    color: Color = LocalTokenColors.current.textPrimary,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.headlineSmall,
) {
    // 采不到就不占位：不画数字，也不画「—」
    if (value == null) return
    val durationMs = minOf(
        DesignTokens.Number.MAX_DURATION_MS.toLong(),
        (intervalMs - DesignTokens.Number.INTERVAL_MARGIN_MS).coerceAtLeast(50L),
    ).toInt()
    val anim = remember { Animatable(value) }
    LaunchedEffect(value) {
        if (anim.value != value) {
            anim.animateTo(
                targetValue = value,
                animationSpec = tween(durationMillis = durationMs, easing = LinearEasing),
            )
        }
    }
    MonoNumber(
        text = Format.value(anim.value, decimals),
        modifier = modifier,
        color = color,
        style = style,
    )
}

/** 按语义色取指标颜色（colorKey 对应 design-tokens 语义色）。 */
@Composable
fun metricColor(colorKey: String): Color {
    val colors = LocalTokenColors.current
    return when (colorKey) {
        "accent" -> colors.accent
        "accentSecondary" -> colors.accentSecondary
        "success" -> colors.success
        "warning" -> colors.warning
        "danger" -> colors.danger
        else -> colors.textPrimary
    }
}
