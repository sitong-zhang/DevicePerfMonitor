package com.devperf.monitor.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devperf.monitor.analysis.AdviceEngine
import com.devperf.monitor.analysis.HealthScorer
import com.devperf.monitor.core.Metric
import com.devperf.monitor.core.MetricCategory
import com.devperf.monitor.core.MetricId
import com.devperf.monitor.data.collectors.DeviceSnapshot
import com.devperf.monitor.ui.MonitorViewModel
import com.devperf.monitor.ui.kit.GlassChip
import com.devperf.monitor.ui.kit.GlassPanel
import com.devperf.monitor.ui.kit.GlassSectionHeader
import com.devperf.monitor.ui.kit.LiquidGlassScaffold
import com.devperf.monitor.ui.kit.MetricRow
import com.devperf.monitor.ui.kit.MetricValueText
import com.devperf.monitor.ui.kit.MonoNumber
import com.devperf.monitor.ui.theme.DesignTokens
import com.devperf.monitor.ui.theme.LocalTokenColors

/**
 * 首页：健康分大卡片 + 四分卡片 + 智能建议 + 全量指标。
 *
 * 数据流：ViewModel.snapshot（采集仓库 StateFlow）→ 组合期重算健康分与建议
 * （纯本地算法）。数据不足时按三态规范显示「数据不足，无法分析」，不造假数据。
 */

/** 从快照中按指标 ID 取值（未追踪/无对应读口的指标返回 null → 显示不可用）。 */
fun DeviceSnapshot.metricOf(id: MetricId): Metric<Float>? = when (id) {
    MetricId.FPS -> display.fps
    MetricId.GLOBAL_FPS -> display.globalFps
    MetricId.FRAME_TIME -> display.frameTimeMs
    MetricId.FRAME_STDEV -> display.frameStdevMs
    MetricId.JANK_RATIO -> display.jankRatio30s
    MetricId.REFRESH_RATE -> display.refreshRateHz
    MetricId.CPU_USAGE -> cpu.usagePercent
    MetricId.CPU_TEMP -> cpu.temperatureC
    MetricId.GPU_LOAD -> gpu.loadPercent
    MetricId.GPU_TEMP -> gpu.temperatureC
    MetricId.BATTERY_TEMP -> battery.temperatureC
    MetricId.BATTERY_LEVEL -> battery.levelPercent
    MetricId.MEMORY_USED -> memory.usedMb
    MetricId.MEMORY_USAGE_RATIO -> memory.usageRatio
    MetricId.DEVICE_TEMP -> thermal.deviceTempC
    else -> null
}

@Composable
fun OverviewScreen(
    viewModel: MonitorViewModel,
    onMetricClick: (MetricId) -> Unit,
) {
    val snapshot by viewModel.snapshot.collectAsState()
    val alert by viewModel.alert.collectAsState()
    val colors = LocalTokenColors.current
    val tickMs = viewModel.tickMs()

    LiquidGlassScaffold {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = DesignTokens.Spacing.S16.dp,
                end = DesignTokens.Spacing.S16.dp,
                top = DesignTokens.Spacing.S32.dp,
                // 底部预留玻璃标签栏空间
                bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S8.dp),
        ) {
            // ---- 标题 + 告警态 ----
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "设备性能监控",
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    if (alert != null) {
                        GlassChip(
                            text = "温度告警",
                            color = colors.danger,
                            selected = true,
                        )
                    }
                }
            }

            // ---- 健康分大卡片 ----
            item { HealthScoreCard(viewModel = viewModel) }

            // ---- 四分卡片 ----
            item { GlassSectionHeader(text = "核心指标") }
            item {
                FeaturedMetricsGrid(
                    snapshot = snapshot,
                    tickMs = tickMs,
                    onMetricClick = onMetricClick,
                )
            }

            // ---- 智能建议 ----
            item { GlassSectionHeader(text = "智能建议") }
            item { AdvicePanel(viewModel = viewModel) }

            // ---- 全量指标（按类别分组） ----
            item { GlassSectionHeader(text = "全部指标") }
            MetricCategory.entries.forEach { category ->
                val ids = MetricId.entries.filter { it.category == category }
                if (ids.isNotEmpty()) {
                    item(key = "cat-${category.name}") {
                        Text(
                            text = category.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(
                                top = DesignTokens.Spacing.S8.dp,
                                bottom = DesignTokens.Spacing.S4.dp,
                            ),
                        )
                    }
                    items(ids, key = { it.name }) { id ->
                        val metric = snapshot.metricOf(id)
                            ?: Metric.unavailable("暂无采集", snapshot.timestampMs)
                        GlassPanel(modifier = Modifier.padding(vertical = 3.dp)) {
                            MetricRow(
                                metricId = id,
                                metric = metric,
                                modifier = Modifier.padding(
                                    horizontal = DesignTokens.Spacing.S16.dp,
                                    vertical = DesignTokens.Spacing.S12.dp,
                                ),
                                intervalMs = tickMs,
                                onPermissionAction = { /* 首页不处理跳转，详情页/设置页处理 */ },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 健康分大卡片：总分大数字 + 等级 + 分维度得分。 */
@Composable
private fun HealthScoreCard(viewModel: MonitorViewModel) {
    val colors = LocalTokenColors.current
    val health = viewModel.health()
    GlassPanel {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(DesignTokens.Spacing.S16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "综合健康分",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
            )
            Spacer(Modifier.height(DesignTokens.Spacing.S4.dp))
            if (health.total != null && health.grade != null) {
                MonoNumber(
                    text = health.total.toInt().toString(),
                    color = when (health.grade) {
                        HealthScorer.Grade.EXCELLENT -> colors.success
                        HealthScorer.Grade.GOOD -> colors.accentSecondary
                        HealthScorer.Grade.FAIR -> colors.warning
                        HealthScorer.Grade.WARNING -> colors.danger
                    },
                    style = MaterialTheme.typography.displayLarge,
                )
                Text(
                    text = health.grade.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                )
                if (health.partial) {
                    Text(
                        text = "部分维度不可用，按可用维度加权",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                    )
                }
                Spacer(Modifier.height(DesignTokens.Spacing.S12.dp))
                health.dimensions.forEach { dim ->
                    DimensionBar(
                        label = dim.id.label,
                        score = dim.score,
                        weight = dim.weight,
                        isReference = dim.isReference,
                    )
                }
            } else {
                MonoNumber(
                    text = "—",
                    color = colors.textSecondary,
                    style = MaterialTheme.typography.displayLarge,
                )
                Text(
                    text = "数据不足，无法分析",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun DimensionBar(
    label: String,
    score: Float?,
    weight: Float,
    isReference: Boolean,
) {
    val colors = LocalTokenColors.current
    val fraction = ((score ?: 0f) / 100f).coerceIn(0f, 1f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = DesignTokens.Spacing.S4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
        )
        Box(
            modifier = Modifier
                .padding(horizontal = DesignTokens.Spacing.S8.dp)
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(colors.surfaceElevated),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .background(
                        when {
                            score == null -> colors.textSecondary
                            score >= 70 -> colors.success
                            score >= 40 -> colors.warning
                            else -> colors.danger
                        }
                    ),
            )
        }
        Text(
            text = buildString {
                append(score?.toInt()?.toString() ?: "—")
                if (isReference) append("*")
            },
            style = MaterialTheme.typography.labelMedium,
            color = colors.textPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(28.dp),
        )
        Text(
            text = "权重 ${(weight * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
            modifier = Modifier.padding(start = DesignTokens.Spacing.S8.dp),
        )
    }
}

/** 四分卡片：FPS / CPU 使用率 / CPU 温度 / 电池温度。 */
@Composable
private fun FeaturedMetricsGrid(
    snapshot: DeviceSnapshot,
    tickMs: Long,
    onMetricClick: (MetricId) -> Unit,
) {
    val featured = listOf(
        MetricId.FPS,
        MetricId.CPU_USAGE,
        MetricId.CPU_TEMP,
        MetricId.BATTERY_TEMP,
    )
    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S8.dp)) {
        featured.chunked(2).forEach { rowIds ->
            Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S8.dp)) {
                rowIds.forEach { id ->
                    val metric = snapshot.metricOf(id)
                        ?: Metric.unavailable("暂无采集", snapshot.timestampMs)
                    FeaturedCard(
                        metricId = id,
                        metric = metric,
                        tickMs = tickMs,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1.15f),
                        onClick = { onMetricClick(id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FeaturedCard(
    metricId: MetricId,
    metric: Metric<Float>,
    tickMs: Long,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalTokenColors.current
    GlassPanel(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(DesignTokens.Spacing.S12.dp),
        ) {
            Text(
                text = metricId.label,
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
            )
            Spacer(Modifier.height(DesignTokens.Spacing.S8.dp))
            MetricValueText(
                metricId = metricId,
                metric = metric,
                intervalMs = tickMs,
                showStateHint = false,
            )
        }
    }
}

/** 智能建议面板：严重度降序；无建议显示「运行正常」。 */
@Composable
private fun AdvicePanel(viewModel: MonitorViewModel) {
    val colors = LocalTokenColors.current
    val advice = viewModel.advice().sortedByDescending { it.severity.ordinal }
    GlassPanel {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(DesignTokens.Spacing.S16.dp),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S12.dp),
        ) {
            if (advice.isEmpty()) {
                Text(
                    text = "运行正常，暂无建议",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.success,
                )
            } else {
                advice.forEach { item -> AdviceItem(item) }
            }
        }
    }
}

@Composable
private fun AdviceItem(advice: AdviceEngine.Advice) {
    val colors = LocalTokenColors.current
    val severityColor = when (advice.severity) {
        AdviceEngine.Severity.INFO -> colors.accentSecondary
        AdviceEngine.Severity.WARNING -> colors.warning
        AdviceEngine.Severity.CRITICAL -> colors.danger
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        // 严重度色条（3px 宽，左缘指示）
        Box(
            modifier = Modifier
                .padding(top = 4.dp, end = DesignTokens.Spacing.S8.dp)
                .width(3.dp)
                .height(40.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(severityColor),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = advice.title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
            )
            Text(
                text = advice.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
        }
    }
}
