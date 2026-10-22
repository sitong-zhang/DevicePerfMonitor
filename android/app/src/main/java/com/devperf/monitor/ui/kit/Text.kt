package com.devperf.monitor.ui.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devperf.monitor.core.Metric
import com.devperf.monitor.core.MetricId
import com.devperf.monitor.core.MetricState
import com.devperf.monitor.core.PermissionAction
import com.devperf.monitor.ui.theme.DesignTokens
import com.devperf.monitor.ui.theme.LocalTokenColors
import com.devperf.monitor.ui.theme.LocalAppMotion
import com.devperf.monitor.util.Format
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 数值文本与三态展示。
 *
 * 1. 所有数值一律使用 JetBrains Mono 等宽字体（随工程打包，OFL）：
 *    数字位宽一致，刷新时不产生横向抖动；
 * 2. 数值动画打断策略（需求硬性规定）：
 *    单次补间时长 = min(300ms, 采集间隔 - 50ms)；
 *    新值到达时从「当前动画值」继续补间（打断不跳变）；
 * 3. 三态展示：AVAILABLE 显示数值 / UNAVAILABLE 显示 "—" + "不可用" /
 *    NEEDS_PERMISSION 显示 "需授权" + 权限名，可点击跳转。
 */

// ---------------------------------------------------------------------------
// 等宽数值
// ---------------------------------------------------------------------------

@Composable
fun MonoNumber(
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
        fontFamily = com.devperf.monitor.ui.theme.JetBrainsMonoFamily,
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
 * @param value 目标值（null = 不可用，显示 "—"）
 * @param intervalMs 采集间隔（500 / 1000 / 2000ms），用于计算补间时长
 * @param format 格式化函数（固定小数位）
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
    if (value == null) {
        MonoNumber(
            text = "—",
            modifier = modifier,
            color = LocalTokenColors.current.textSecondary,
            style = style,
        )
        return
    }
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

// ---------------------------------------------------------------------------
// 指标值格式化（按指标类型选格式，双端一致）
// ---------------------------------------------------------------------------

/** 按 [MetricId] 格式化数值（含单位）。 */
fun formatMetricValue(id: MetricId, v: Float): String = when (id) {
    MetricId.FPS, MetricId.GLOBAL_FPS -> "${v.roundToInt()}fps"
    MetricId.REFRESH_RATE -> "${v.roundToInt()}Hz"
    MetricId.FRAME_TIME, MetricId.FRAME_STDEV -> Format.value(v, 1) + "ms"
    MetricId.JANK_FRAMES -> "${v.roundToInt()}帧"
    MetricId.JANK_RATIO -> Format.value(v, 1) + "%"
    MetricId.CPU_USAGE, MetricId.CPU_CORE_USAGE -> Format.percent(v)
    MetricId.CPU_CORE_FREQ, MetricId.GPU_FREQ -> "${v.roundToInt()}MHz"
    MetricId.GPU_LOAD -> Format.percent(v)
    MetricId.CPU_TEMP, MetricId.GPU_TEMP, MetricId.BATTERY_TEMP, MetricId.DEVICE_TEMP ->
        Format.temp(v)
    MetricId.BATTERY_CURRENT -> Format.current(v)
    MetricId.BATTERY_VOLTAGE -> Format.value(v, 2) + "V"
    MetricId.BATTERY_LEVEL -> Format.percent(v)
    MetricId.BATTERY_HEALTH -> Format.percent(v)
    MetricId.MEMORY_TOTAL, MetricId.MEMORY_USED, MetricId.MEMORY_AVAILABLE, MetricId.PER_APP_MEMORY ->
        Format.memory(v)
    MetricId.MEMORY_USAGE_RATIO -> Format.percent(v)
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

// ---------------------------------------------------------------------------
// 三态指标展示
// ---------------------------------------------------------------------------

/**
 * 单个指标的三态数值展示（大数字 + 状态小字）。
 *
 * - AVAILABLE：动画数值；
 * - UNAVAILABLE："—" + "不可用"；
 * - NEEDS_PERMISSION："需授权" + 权限名（点击走 [PermissionAction] 对应跳转，由调用方提供）。
 *
 * @param onPermissionAction 点击「需授权」的回调（携带权限动作）
 */
@Composable
fun MetricValueText(
    metricId: MetricId,
    metric: Metric<Float>,
    modifier: Modifier = Modifier,
    intervalMs: Long = 1000L,
    showStateHint: Boolean = true,
    onPermissionAction: ((PermissionAction) -> Unit)? = null,
) {
    val colors = LocalTokenColors.current
    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        when (val state = metric.state) {
            is MetricState.Available -> {
                AnimatedNumber(
                    value = state.value,
                    intervalMs = intervalMs,
                    decimals = metricId.decimals,
                    color = metricColor(metricId.colorKey),
                    style = MaterialTheme.typography.headlineMedium,
                )
                if (showStateHint) {
                    Text(
                        text = metricId.unit,
                        color = colors.textSecondary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            is MetricState.Unavailable -> {
                MonoNumber(
                    text = "—",
                    color = colors.textSecondary,
                    style = MaterialTheme.typography.headlineMedium,
                )
                if (showStateHint) {
                    Text(
                        text = "不可用",
                        color = colors.textSecondary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            is MetricState.NeedsPermission -> {
                MonoNumber(
                    text = "需授权",
                    color = colors.warning,
                    style = MaterialTheme.typography.titleLarge,
                )
                if (showStateHint) {
                    Text(
                        text = state.permissionLabel,
                        color = colors.warning,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = if (onPermissionAction != null) {
                            Modifier.clickable { onPermissionAction(state.action) }
                        } else Modifier,
                    )
                }
            }
        }
    }
}

/**
 * 指标行：标签 + 三态数值（列表场景，如详情页/设置页）。
 */
@Composable
fun MetricRow(
    metricId: MetricId,
    metric: Metric<Float>,
    modifier: Modifier = Modifier,
    intervalMs: Long = 1000L,
    onPermissionAction: ((PermissionAction) -> Unit)? = null,
) {
    val colors = LocalTokenColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = metricId.label,
                color = colors.textPrimary,
                style = MaterialTheme.typography.bodyLarge,
            )
            when (val state = metric.state) {
                is MetricState.Unavailable ->
                    Text(
                        text = "不可用",
                        color = colors.textSecondary,
                        style = MaterialTheme.typography.labelMedium,
                    )

                is MetricState.NeedsPermission ->
                    Text(
                        text = "需授权：${state.permissionLabel}",
                        color = colors.warning,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = if (onPermissionAction != null) {
                            Modifier.clickable { onPermissionAction(state.action) }
                        } else Modifier,
                    )

                is MetricState.Available -> Spacer(modifier = Modifier.height(2.dp))
            }
        }
        Spacer(modifier = Modifier.width(DesignTokens.Spacing.S8.dp))
        MetricValueText(
            metricId = metricId,
            metric = metric,
            intervalMs = intervalMs,
            showStateHint = false,
            onPermissionAction = onPermissionAction,
        )
    }
}

/** 三态简短文案（供图表空态、汇总行复用）。 */
fun stateShortText(state: MetricState<*>): String = when (state) {
    is MetricState.Available -> ""
    is MetricState.Unavailable -> "不可用"
    is MetricState.NeedsPermission -> "需授权"
}

/** 差值文本（如与峰值对比）：带符号、固定小数。 */
fun deltaText(delta: Float, decimals: Int = 1): String {
    val sign = if (delta > 0f) "+" else ""
    return sign + Format.value(delta, decimals)
}

/** 温度是否超阈值（用于告警态染色，阈值由设置页提供）。 */
fun isTempAlert(tempC: Float, thresholdC: Float): Boolean = tempC >= thresholdC

/** 绝对值比较辅助（历史峰值查看）。 */
fun peakOf(values: List<Float>): Float? = values.maxOrNull()

/** 稳定性判断辅助：σ 越小越稳。 */
fun stabilityLabel(stdevMs: Float?): String = when {
    stdevMs == null -> "数据不足"
    stdevMs < 4f -> "非常稳定"
    stdevMs < 8f -> "稳定"
    stdevMs < 14f -> "轻微波动"
    else -> "波动明显"
}

/** 电流方向文案（充放电判断依赖电流符号，Android 规范：正=充电）。 */
fun currentDirection(currentMa: Float): String =
    if (currentMa > 50f) "充电" else if (currentMa < -50f) "放电" else "静置"

/** 供图表 tooltip：时间戳 + 数值。 */
fun pointLabel(timestampMs: Long, value: Float, decimals: Int): String =
    "${Format.clock(timestampMs)}  ${Format.value(value, decimals)}"

/** abs 便捷导出（悬浮窗温度判断用）。 */
internal fun absF(v: Float): Float = abs(v)
