package com.devperf.monitor.ui.screen

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import com.devperf.monitor.ui.kit.EnterStagger
import com.devperf.monitor.ui.kit.enterSpec
import com.devperf.monitor.ui.kit.GlassCard
import com.devperf.monitor.ui.kit.ProgressRing
import com.devperf.monitor.ui.kit.Sparkline
import com.devperf.monitor.ui.kit.metricColor
import com.devperf.monitor.ui.kit.pressScale
import com.devperf.monitor.ui.kit.rememberHaptics
import com.devperf.monitor.ui.kit.shimmer
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
    // 首帧还没采上来：先给骨架，别让界面先闪一下空白再跳成有内容
    val awaitingFirstSample = focused.updatedAtMs <= 0L && visible.isEmpty()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(colors.background, colors.surface, colors.background),
                ),
            ),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 16.dp,
            end = 16.dp,
            // 底部必须给浮动胶囊底栏让出空间（底栏高度 + 下边距 + 一点呼吸），
            // 否则最后一张卡片会被底栏压住——1.0.9 之前没留，列表滚到底也看不见末尾。
            // iOS 侧同样处理（ContentView 里的 bottom inset）。
            bottom = DesignTokens.BottomBar.HEIGHT.dp +
                DesignTokens.BottomBar.MARGIN_BOTTOM.dp + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.Surface.GAP.dp),
    ) {
        if (awaitingFirstSample) {
            items(count = 5, key = { "skeleton-$it" }) { index ->
                SkeletonCard(index = index)
            }
            return@LazyColumn
        }

        item(key = "health") {
            EnterStagger(index = 0) {
                HealthCard(result = focused.health)
            }
        }

        itemsIndexed(items = visible, key = { _, r -> r.id.name }) { index, reading ->
            val mode = settings.metricViewModes[reading.id]
                ?: defaultMetricViewModes()[reading.id]
                ?: MetricViewMode.NUMBER
            EnterStagger(index = index + 1) {
                MetricCard(
                    reading = reading,
                    mode = effectiveMode(reading.id, mode),
                    history = viewModel.history(reading.id.source, CARD_WINDOW_SEC),
                    tickMs = viewModel.tickMs(),
                    onClick = { onOpenDetail(reading.id) },
                )
            }
        }

        if (visible.isEmpty()) {
            item(key = "empty") {
                EmptyState()
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
// 骨架屏
// ---------------------------------------------------------------------------

@Composable
private fun SkeletonCard(index: Int) {
    val colors = LocalTokenColors.current
    val shape = RoundedCornerShape(
        (if (index == 0) DesignTokens.Radius.LARGE else DesignTokens.Radius.CARD).dp,
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (index == 0) 190.dp else 132.dp)
            .background(
                color = colors.surface.copy(alpha = 0.42f),
                shape = shape,
            )
            .shimmer(colors.textPrimary),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(if (index == 0) 128.dp else 76.dp)
                    .background(
                        color = colors.textSecondary.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(DesignTokens.Radius.PILL.dp),
                    ),
            )
            Column(
                modifier = Modifier
                    .padding(start = 18.dp)
                    .weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.42f)
                        .height(14.dp)
                        .background(
                            color = colors.textSecondary.copy(alpha = 0.22f),
                            shape = RoundedCornerShape(DesignTokens.Radius.PILL.dp),
                        ),
                )
                Box(
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .fillMaxWidth(0.72f)
                        .height(12.dp)
                        .background(
                            color = colors.textSecondary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(DesignTokens.Radius.PILL.dp),
                        ),
                )
            }
        }
    }
}

@Composable
private fun EmptyState() {
    val colors = LocalTokenColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "这台设备暂时读不到这些指标",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "能采到的会自动出现，采不到的不会占位",
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
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

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 176.dp),
        shape = RoundedCornerShape(DesignTokens.Radius.LARGE.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProgressRing(
                progress = (total ?: 0f) / 100f,
                // 三个维度全采不到就不给分：圆环留空、不写占位数字
                centerText = total?.let { "${it.toInt()}" } ?: "",
                caption = result.grade?.label ?: "采集中",
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
    val interactionSource = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 112.dp)
            .pressScale(interactionSource),
        shape = RoundedCornerShape(DesignTokens.Radius.CARD.dp),
        onClick = {
            haptics.tick()
            onClick()
        },
        interactionSource = interactionSource,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = reading.id.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                // 右上角的模式标识，点进详情页可切换
                Text(
                    text = mode.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent.copy(alpha = 0.75f),
                )
            }

            // 模式切换时交叉淡入，避免布局硬切
            Crossfade(
                targetState = mode,
                animationSpec = enterSpec(),
                label = "metricMode",
            ) { currentMode ->
                Box(modifier = Modifier.fillMaxSize()) {
                    when (currentMode) {
                        MetricViewMode.NUMBER -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                ValueText(
                                    reading,
                                    tickMs,
                                    accent,
                                    MaterialTheme.typography.displaySmall,
                                )
                            }
                        }

                        MetricViewMode.RING -> {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ProgressRing(
                                    progress = (reading.value ?: 0f) / 100f,
                                    centerText = reading.value?.let { "${it.toInt()}" } ?: "",
                                    caption = reading.id.unit,
                                    color = accent,
                                    trackColor = accent.copy(alpha = 0.18f),
                                    modifier = Modifier.size(72.dp),
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
                                Box(
                                    modifier = Modifier
                                        .weight(0.42f)
                                        .fillMaxHeight(),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
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
                                Box(
                                    modifier = Modifier
                                        .weight(0.42f)
                                        .fillMaxHeight(),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
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
    // 采不到就不占位：这张卡压根不会进来（visible 已过滤），这里是双保险
    if (value == null) return
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
