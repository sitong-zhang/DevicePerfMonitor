package com.devperf.monitor.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devperf.monitor.core.Metric
import com.devperf.monitor.core.MetricId
import com.devperf.monitor.core.MetricState
import com.devperf.monitor.ui.kit.AnimatedNumber
import com.devperf.monitor.ui.kit.formatMetricValue
import com.devperf.monitor.ui.kit.metricColor
import com.devperf.monitor.ui.theme.DesignTokens
import com.devperf.monitor.ui.theme.LocalAppMotion
import com.devperf.monitor.ui.theme.LocalTokenColors
import com.devperf.monitor.ui.theme.JetBrainsMonoFamily

/**
 * 悬浮窗 Compose 内容。
 *
 * 形态：
 * - 迷你模式：单行三项核心数据（FPS / CPU 使用率 / 机身温度）；
 * - 标准模式：6 项指标面板（2 行 × 3 列）；
 * - 收窄态：贴边后的窄条（accent 竖条 + 圆点），点击或拖出恢复。
 *
 * 玻璃材质：走 [com.devperf.monitor.ui.kit.LiquidGlassSurface] 的降级路径
 * （系统悬浮窗无法采样窗口背后画面，详见 README「降级说明」）。
 *
 * 告警态：机身温度超阈值时整体变红（边框/高光/数值）。
 */
data class OverlayData(
    val fps: Metric<Float>,
    val cpuUsage: Metric<Float>,
    val cpuTemp: Metric<Float>,
    val batteryTemp: Metric<Float>,
    val batteryLevel: Metric<Float>,
    val memoryRatio: Metric<Float>,
) {
    companion object {
        fun from(
            fps: Metric<Float>,
            snapshot: com.devperf.monitor.data.collectors.DeviceSnapshot,
        ): OverlayData = OverlayData(
            fps = fps,
            cpuUsage = snapshot.cpu.usagePercent,
            cpuTemp = snapshot.cpu.temperatureC,
            batteryTemp = snapshot.battery.temperatureC,
            batteryLevel = snapshot.battery.levelPercent,
            memoryRatio = snapshot.memory.usageRatio,
        )
    }
}

@Composable
fun OverlayContent(
    state: OverlayController.UiState,
    data: OverlayData,
    intervalMs: Long,
    callbacks: OverlayGestureCallbacks,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTokenColors.current
    val motion = LocalAppMotion.current
    val cardShape = RoundedCornerShape(DesignTokens.Radius.CARD.dp)
    val alertTint = if (state.alert) colors.danger else colors.surface

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(OverlayShadowPaddingDp.dp)
            .overlayGestures(callbacks),
        contentAlignment = Alignment.Center,
    ) {
        // 阴影 + 半透明底由 LiquidGlassSurface 降级路径绘制（真实半透明，可透视后方应用）
        com.devperf.monitor.ui.kit.LiquidGlassSurface(
            modifier = Modifier.fillMaxSize(),
            backdrop = null, // 悬浮窗无 Backdrop 采样源 → 降级路径
            shape = cardShape,
            tint = alertTint,
            style = com.devperf.monitor.ui.kit.GlassStyle(
                blurRadiusDp = DesignTokens.Blur.OVERLAY_MIN,
                // 告警态提高底衬不透明度（MIN→MAX），强化"变红"的视觉权重
                surfaceAlpha = if (state.alert) {
                    DesignTokens.Glass.BACKGROUND_ALPHA.MAX
                } else {
                    DesignTokens.Glass.BACKGROUND_ALPHA.MIN
                },
            ),
        ) {
            when {
                state.collapsed -> CollapsedStrip(state)
                state.mode == OverlayController.Mode.MINI -> MiniRow(data, intervalMs, state)
                else -> StandardGrid(data, intervalMs, state)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 迷你模式：单行三项
// ---------------------------------------------------------------------------

@Composable
private fun MiniRow(data: OverlayData, intervalMs: Long, state: OverlayController.UiState) {
    val colors = LocalTokenColors.current
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        MiniCell("FPS", MetricId.FPS, data.fps, intervalMs, Modifier.weight(1f))
        MiniDivider()
        MiniCell("CPU", MetricId.CPU_USAGE, data.cpuUsage, intervalMs, Modifier.weight(1f))
        MiniDivider()
        MiniCell("温度", MetricId.DEVICE_TEMP, data.cpuTemp, intervalMs, Modifier.weight(1f))
    }
}

@Composable
private fun MiniCell(
    label: String,
    metricId: MetricId,
    metric: Metric<Float>,
    intervalMs: Long,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTokenColors.current
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            color = colors.textSecondary,
            style = MaterialTheme.typography.labelSmall,
        )
        when (val s = metric.state) {
            is MetricState.Available -> AnimatedNumber(
                value = s.value,
                intervalMs = intervalMs,
                decimals = metricId.decimals,
                color = metricColor(metricId.colorKey),
                style = MaterialTheme.typography.titleLarge,
            )

            is MetricState.Unavailable -> Text(
                text = "—",
                color = colors.textSecondary,
                style = MaterialTheme.typography.titleLarge,
            )

            is MetricState.NeedsPermission -> Text(
                text = "需授权",
                color = colors.warning,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun MiniDivider() {
    val colors = LocalTokenColors.current
    Box(
        modifier = Modifier
            .size(width = 1.dp, height = 26.dp)
            .drawBehind {
                drawRect(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.5f to colors.textSecondary.copy(alpha = 0.35f),
                        1f to Color.Transparent,
                    )
                )
            }
    )
}

// ---------------------------------------------------------------------------
// 标准模式：6 项面板（2 行 × 3 列）
// ---------------------------------------------------------------------------

@Composable
private fun StandardGrid(data: OverlayData, intervalMs: Long, state: OverlayController.UiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            GridCell("FPS", MetricId.FPS, data.fps, intervalMs, Modifier.weight(1f))
            GridCell("CPU", MetricId.CPU_USAGE, data.cpuUsage, intervalMs, Modifier.weight(1f))
            GridCell("CPU 温度", MetricId.CPU_TEMP, data.cpuTemp, intervalMs, Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            GridCell("电池温度", MetricId.BATTERY_TEMP, data.batteryTemp, intervalMs, Modifier.weight(1f))
            GridCell("电量", MetricId.BATTERY_LEVEL, data.batteryLevel, intervalMs, Modifier.weight(1f))
            GridCell("内存", MetricId.MEMORY_USAGE_RATIO, data.memoryRatio, intervalMs, Modifier.weight(1f))
        }
    }
}

@Composable
private fun GridCell(
    label: String,
    metricId: MetricId,
    metric: Metric<Float>,
    intervalMs: Long,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTokenColors.current
    Column(
        modifier = modifier.padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            color = colors.textSecondary,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
        )
        when (val s = metric.state) {
            is MetricState.Available -> AnimatedNumber(
                value = s.value,
                intervalMs = intervalMs,
                decimals = metricId.decimals,
                color = metricColor(metricId.colorKey),
                style = MaterialTheme.typography.titleMedium,
            )

            is MetricState.Unavailable -> Text(
                text = "—",
                color = colors.textSecondary,
                style = MaterialTheme.typography.titleMedium,
            )

            is MetricState.NeedsPermission -> Text(
                text = "需授权",
                color = colors.warning,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 收窄态：贴边窄条
// ---------------------------------------------------------------------------

@Composable
private fun CollapsedStrip(state: OverlayController.UiState) {
    val colors = LocalTokenColors.current
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 5.dp, height = 34.dp)
                .drawBehind {
                    drawRoundRect(
                        color = if (state.alert) colors.danger else colors.accent,
                        cornerRadius = CornerRadius(size.width / 2f),
                    )
                }
        )
        // 收窄态提示点：点击展开（单击手势在整个窗口上生效）
        Box(
            modifier = Modifier
                .padding(top = 52.dp)
                .size(4.dp)
                .drawBehind {
                    drawCircle(color = colors.accent.copy(alpha = 0.8f))
                }
        )
    }
}

/** 面板切换时的淡入淡出（enter/exit 曲线，design-tokens motion）。 */
@Composable
internal fun OverlayModeTransition(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    val motion = LocalAppMotion.current
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(motion.enterDurationMs, easing = motion.enter)),
        exit = fadeOut(tween(motion.exitDurationMs, easing = motion.exit)),
    ) {
        content()
    }
}
