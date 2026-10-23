package com.devperf.monitor.overlay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.unit.dp
import com.devperf.monitor.analysis.FocusedHealth
import com.devperf.monitor.core.CoreMetricId
import com.devperf.monitor.core.Metric
import com.devperf.monitor.data.collectors.DeviceSnapshot
import com.devperf.monitor.ui.kit.AnimatedNumber
import com.devperf.monitor.ui.kit.GlassCard
import com.devperf.monitor.ui.kit.ProgressRing
import com.devperf.monitor.ui.kit.metricColor
import com.devperf.monitor.ui.theme.DesignTokens
import com.devperf.monitor.ui.theme.LocalTokenColors

/**
 * 悬浮窗数据：核心指标 + 健康分所需的辅助量。
 *
 * 之前塞了六项（含电量和 CPU），结果一半机型读不到，悬浮窗里一排「—」，
 * 悬浮窗本来就看一眼，占着位置还没用。
 *
 * 1.0.9 起悬浮窗**只显示三项**：内存占用率 / 温度 / 实时帧率（用户要求：
 * 刷新率移到应用内看，悬浮窗和实况窗都不要它）。[refreshHz] 仍然保留在数据里，
 * 因为综合评判的流畅度 = 帧率 ÷ 刷新率 达成率要用它算，**只是不参与悬浮窗显示**
 * ——所以下面的 cells 构建里看不到它，不是漏了。
 */
data class OverlayData(
    val memoryRatio: Metric<Float>,
    val deviceTemp: Metric<Float>,
    val refreshHz: Metric<Float>,
    val fps: Metric<Float>,
) {
    companion object {
        fun from(
            fps: Metric<Float>,
            snapshot: DeviceSnapshot,
        ): OverlayData = OverlayData(
            memoryRatio = snapshot.memory.usageRatio,
            deviceTemp = snapshot.thermal.deviceTempC,
            refreshHz = snapshot.display.refreshRateHz,
            fps = fps,
        )
    }
}

/** 悬浮窗里的一格：指标 + 当前值。 */
private data class GridCell(val id: CoreMetricId, val value: Float)

/**
 * 悬浮窗内容：三态（卡片 / 纯数字 / 收起），单击循环切换。
 *
 * - CARD：默认形态。左侧综合评判小环 + 右侧三项指标竖排（温度 / 实时帧率 /
 *   内存占用率）。一退出应用就稳定显示这张卡片，不自动变形
 *   （用户原话：「等它显示那张卡片，不要动它」）；
 * - NUMBERS：只要卡片上的数字——去掉卡片容器、阴影、小环，三个数字横排；
 * - COLLAPSED：收成一条竖线，点一下恢复完整卡片。
 *
 * 形态切换的形变动效在内容层完成（[AnimatedContent] + Expressive 弹簧），
 * 窗口尺寸本身只瞬切（见 OverlayController.applyLayout）。
 *
 * 采不到的指标不占位——悬浮窗寸土寸金，宁可少一格也不放「—」。
 */
@Composable
fun OverlayContent(
    state: OverlayController.UiState,
    data: OverlayData,
    health: FocusedHealth.Result,
    intervalMs: Long,
    callbacks: OverlayGestureCallbacks,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTokenColors.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(OverlayShadowPaddingDp.dp)
            .overlayGestures(callbacks),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = state.mode,
            transitionSpec = {
                // 形态切换：淡入 + 轻微放大（Expressive 默认空间弹簧 0.8/380）
                val springSpec = spring<Float>(
                    dampingRatio = 0.8f,
                    stiffness = 380f,
                )
                (
                    fadeIn(animationSpec = springSpec) +
                        scaleIn(initialScale = 0.92f, animationSpec = springSpec)
                    ) togetherWith fadeOut(animationSpec = tween(durationMillis = 120))
            },
            label = "overlayMode",
        ) { mode ->
            when (mode) {
                OverlayController.Mode.COLLAPSED -> CollapsedStrip(state)

                OverlayController.Mode.NUMBERS -> NumbersStrip(data, intervalMs)

                OverlayController.Mode.CARD -> CardContent(
                    state = state,
                    data = data,
                    health = health,
                    intervalMs = intervalMs,
                )
            }
        }
    }
}

/** 卡片态：完整内容（小环 + 三项指标竖排）。告警时容器整体转危险色。 */
@Composable
private fun CardContent(
    state: OverlayController.UiState,
    data: OverlayData,
    health: FocusedHealth.Result,
    intervalMs: Long,
) {
    val colors = LocalTokenColors.current
    // 悬浮窗采不到背后画面，容器用主题表面色（真实半透明，能透视后面的应用）；
    // 高温告警时整张卡片转危险色低透明度，数字保持各自的指标色。
    val container = if (state.alert) {
        colors.danger.copy(alpha = 0.22f)
    } else {
        colors.surface
    }
    GlassCard(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(DesignTokens.Radius.CARD.dp),
        containerColor = container,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HealthBadge(health)
            Spacer(modifier = Modifier.width(10.dp))
            MetricGrid(data, intervalMs, Modifier.weight(1f))
        }
    }
}

/** 综合评判小环：分数 + 一圈进度，颜色按档位变。 */
@Composable
private fun HealthBadge(health: FocusedHealth.Result) {
    val colors = LocalTokenColors.current
    val total = health.total
    val tint = when {
        total == null -> colors.textSecondary
        total >= 75f -> colors.success
        total >= 50f -> colors.accent
        else -> colors.danger
    }
    ProgressRing(
        progress = (total ?: 0f) / 100f,
        // 三个维度全采不到就不给分：环留空，不写占位数字
        centerText = total?.let { "${it.toInt()}" } ?: "",
        caption = "",
        color = tint,
        trackColor = tint.copy(alpha = 0.20f),
        modifier = Modifier.size(54.dp),
        strokeWidth = 5.dp,
    )
}

/**
 * 悬浮窗显示的三项：内存占用率 / 温度 / 实时帧率，竖排三行。
 *
 * 1.0.9 起去掉刷新率（用户要求它只留在应用内）。剩三项再排 2×2 会空出一格，
 * 所以改成单列：标签在左、数值右对齐，扫一眼更整齐。
 * 只放采得到的，采不到的直接不进列表（悬浮窗寸土寸金，不放占位符）。
 */
@Composable
private fun MetricGrid(
    data: OverlayData,
    intervalMs: Long,
    modifier: Modifier = Modifier,
) {
    val cells = buildList<GridCell> {
        data.memoryRatio.value?.let { add(GridCell(CoreMetricId.MEMORY_RATIO, it)) }
        data.deviceTemp.value?.let { add(GridCell(CoreMetricId.DEVICE_TEMP, it)) }
        // 刷新率不进悬浮窗（1.0.9）：只用于综合评判的流畅度计算
        data.fps.value?.let { add(GridCell(CoreMetricId.FPS, it)) }
    }

    if (cells.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "读不到指标",
                style = MaterialTheme.typography.labelSmall,
                color = LocalTokenColors.current.textSecondary,
            )
        }
        return
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        cells.forEach { cell -> MetricRow(cell, intervalMs) }
    }
}

/** 一行指标：标签靠左、数值靠右对齐。 */
@Composable
private fun MetricRow(
    cell: GridCell,
    intervalMs: Long,
) {
    val colors = LocalTokenColors.current
    val accent = metricColor(cell.id.source.colorKey)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = cell.id.label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary,
            // 标签不抢数值的位置，超出就截断（悬浮窗宽度有限）
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            AnimatedNumber(
                value = cell.value,
                intervalMs = intervalMs,
                decimals = cell.id.decimals,
                color = accent,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = " ${cell.id.unit}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
            )
        }
    }
}

/**
 * 纯数字态：只要卡片上的数字，不要卡片。
 *
 * 无容器、无阴影、无小环——四个数字直接横排浮在屏幕上，
 * 每个数字保持自己的指标色；告警时统一转危险色（没有卡片可染，染数字）。
 */
@Composable
private fun NumbersStrip(
    data: OverlayData,
    intervalMs: Long,
) {
    val colors = LocalTokenColors.current
    val cells = buildList<GridCell> {
        data.memoryRatio.value?.let { add(GridCell(CoreMetricId.MEMORY_RATIO, it)) }
        data.deviceTemp.value?.let { add(GridCell(CoreMetricId.DEVICE_TEMP, it)) }
        // 刷新率不进悬浮窗（1.0.9）：只用于综合评判的流畅度计算
        data.fps.value?.let { add(GridCell(CoreMetricId.FPS, it)) }
    }

    if (cells.isEmpty()) {
        // 纯数字态没有容器，缺数据时给一行小字兜底（避免空白窗口看不出所以然）
        Text(
            text = "读不到指标",
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary,
        )
        return
    }

    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        cells.forEach { cell ->
            val numberColor = metricColor(cell.id.source.colorKey)
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center,
            ) {
                AnimatedNumber(
                    value = cell.value,
                    intervalMs = intervalMs,
                    decimals = cell.id.decimals,
                    color = numberColor,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = cell.id.unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

/** 收起态：贴边后的窄条 + 提示点，点击恢复完整卡片。 */
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
                },
        )
        Box(
            modifier = Modifier
                .padding(top = 52.dp)
                .size(4.dp)
                .drawBehind { drawCircle(color = colors.accent.copy(alpha = 0.8f)) },
        )
    }
}
