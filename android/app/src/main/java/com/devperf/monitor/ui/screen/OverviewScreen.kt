package com.devperf.monitor.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devperf.monitor.analysis.FocusedHealth
import com.devperf.monitor.core.CoreMetricId
import com.devperf.monitor.core.MetricViewMode
import com.devperf.monitor.data.defaultMetricViewModes
import com.devperf.monitor.data.storage.HistoryPoint
import com.devperf.monitor.ui.MonitorViewModel
import com.devperf.monitor.ui.kit.AnimatedNumber
import com.devperf.monitor.ui.kit.BarSeries
import com.devperf.monitor.ui.kit.GlassStyle
import com.devperf.monitor.ui.kit.LiquidGlassSurface
import com.devperf.monitor.ui.kit.ProgressRing
import com.devperf.monitor.ui.kit.Sparkline
import com.devperf.monitor.ui.kit.metricColor
import com.devperf.monitor.ui.theme.DesignTokens
import com.devperf.monitor.ui.theme.LocalTokenColors

/** 卡片上的迷你轨迹看最近 1 分钟。 */
private const val CARD_WINDOW_SEC = 60

/**
 * 主界面：综合评判 + 四个核心指标。
 *
 * 两条硬规则：
 * 1. 只显示这四个指标，其余一概不进界面；
 * 2. 采不到的指标不占位、不显示 0——没数据就当它不存在。
 */
@Composable
fun OverviewScreen(
    viewModel: MonitorViewModel,
    onOpenDetail: (CoreMetricId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focused by viewModel.focused.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val colors = LocalTokenColors.current

    val visible = focused.readings.filter { it.available }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(colors.background, colors.surface, colors.background),
                ),
            ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "health") {
            HealthCard(result = focused.health)
        }

        items(items = visible, key = { it.id.name }) { reading ->
            val mode = settings.metricViewModes[reading.id]
                ?: defaultMetricViewModes()[reading.id]
                ?: MetricViewMode.NUMBER
            MetricCard(
                reading = reading,
                mode = effectiveMode(reading.id, mode),
                history = viewModel.history(reading.id.source, CARD_WINDOW_SEC),
                tickMs = viewModel.tickMs(),
                onClick = { onOpenDetail(reading.id) },
            )
        }

        if (visible.isEmpty()) {
            item(key = "empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "这台设备暂时读不到这些指标",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * 环形只有 0~100 有界的指标用得上（内存占用率）。
 * 温度 / 刷新率 / 帧率选了环形就回落到波形，避免把没有上限的值硬塞进圆环。
 */
private fun effectiveMode(id: CoreMetricId, mode: MetricViewMode): MetricViewMode =
    if (mode == MetricViewMode.RING && id != CoreMetricId.MEMORY_RATIO) {
        MetricViewMode.WAVE
    } else {
        mode
    }

// ---------------------------------------------------------------------------
// 综合评判
// ---------------------------------------------------------------------------

@Composable
private fun HealthCard(result: FocusedHealth.Result) {
    val colors = LocalTokenColors.current
    val total = result.total
    val accent = when {
        total == null -> colors.textSecondary
        total >= 75f -> colors.success
        total >= 50f -> colors.accent
        else -> colors.danger
    }

    LiquidGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp),
        shape = RoundedCornerShape(DesignTokens.Radius.LARGE.dp),
        style = GlassStyle(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProgressRing(
                progress = (total ?: 0f) / 100f,
                centerText = total?.let { "${it.toInt()}" } ?: "—",
                caption = result.grade?.label ?: "",
                color = accent,
                trackColor = accent.copy(alpha = 0.18f),
                modifier = Modifier.size(128.dp),
            )
            Column(
                modifier = Modifier
                    .padding(start = 18.dp)
                    .weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "综合评判",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                )
                Text(
                    text = result.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                result.weakest?.let { weakest ->
                    Text(
                        text = "最短板：${weakest.label}",
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 指标卡
// ---------------------------------------------------------------------------

@Composable
private fun MetricCard(
    reading: MonitorViewModel.CoreReading,
    mode: MetricViewMode,
    history: List<HistoryPoint>,
    tickMs: Long,
    onClick: () -> Unit,
) {
    val colors = LocalTokenColors.current
    val accent = metricColor(reading.id.source.colorKey)
    val values = history.map { it.value }

    LiquidGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .height(132.dp),
        shape = RoundedCornerShape(DesignTokens.Radius.CARD.dp),
        style = GlassStyle(),
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = reading.id.label,
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
            )

            when (mode) {
                MetricViewMode.NUMBER -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        ValueText(reading, tickMs, accent, MaterialTheme.typography.displaySmall)
                    }
                }

                MetricViewMode.RING -> {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProgressRing(
                            progress = (reading.value ?: 0f) / 100f,
                            centerText = reading.value?.let { "${it.toInt()}" } ?: "—",
                            caption = reading.id.unit,
                            color = accent,
                            trackColor = accent.copy(alpha = 0.18f),
                            modifier = Modifier.size(76.dp),
                            strokeWidth = 8.dp,
                        )
                        Text(
                            text = "占用越高越吃力",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(start = 14.dp),
                        )
                    }
                }

                MetricViewMode.WAVE -> {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.weight(0.42f)) {
                            ValueText(
                                reading,
                                tickMs,
                                accent,
                                MaterialTheme.typography.headlineSmall,
                            )
                        }
                        Sparkline(
                            points = values,
                            color = accent,
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                        )
                    }
                }

                MetricViewMode.BARS -> {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.weight(0.42f)) {
                            ValueText(
                                reading,
                                tickMs,
                                accent,
                                MaterialTheme.typography.headlineSmall,
                            )
                        }
                        BarSeries(
                            points = values,
                            color = accent,
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ValueText(
    reading: MonitorViewModel.CoreReading,
    tickMs: Long,
    color: Color,
    style: TextStyle,
) {
    val colors = LocalTokenColors.current
    val value = reading.value
    if (value == null) {
        Text(text = "—", style = style, color = colors.textSecondary)
        return
    }
    Row(verticalAlignment = Alignment.Bottom) {
        AnimatedNumber(
            value = value,
            intervalMs = tickMs,
            decimals = reading.id.decimals,
            color = color,
            style = style,
        )
        Text(
            text = " ${reading.id.unit}",
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}
