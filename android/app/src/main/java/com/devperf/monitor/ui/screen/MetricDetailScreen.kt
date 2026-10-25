package com.devperf.monitor.ui.screen

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devperf.monitor.core.CoreMetricId
import com.devperf.monitor.core.MetricViewMode
import com.devperf.monitor.data.defaultMetricViewModes
import com.devperf.monitor.ui.MonitorViewModel
import com.devperf.monitor.ui.kit.AnimatedNumber
import com.devperf.monitor.ui.kit.BarSeries
import com.devperf.monitor.ui.kit.EnterStagger
import com.devperf.monitor.ui.kit.GlassTabs
import com.devperf.monitor.ui.kit.GlassCard
import com.devperf.monitor.ui.kit.ProgressRing
import com.devperf.monitor.ui.kit.Sparkline
import com.devperf.monitor.ui.kit.enterSpec
import com.devperf.monitor.ui.kit.metricColor
import com.devperf.monitor.ui.kit.pressScale
import com.devperf.monitor.ui.kit.rememberHaptics
import com.devperf.monitor.ui.theme.DesignTokens
import com.devperf.monitor.ui.theme.LocalTokenColors
import com.devperf.monitor.ui.theme.TokenColors

/** 可回看的时间窗口（秒）。历史保留 2 小时，所以最长 2 小时。 */
private val WINDOWS = listOf(
    60 to "1 分钟",
    300 to "5 分钟",
    1800 to "30 分钟",
    7200 to "2 小时",
)

/**
 * 指标详情：当前值 + 历史轨迹回看 + 显示模式切换。
 *
 * 历史是实时采集落下来的真实采样，往回翻就能看到刚才发生了什么
 * （比如帧率什么时候掉的、温度什么时候上去的）。数据不足时说数据不足，不补线。
 */
@Composable
fun MetricDetailScreen(
    viewModel: MonitorViewModel,
    coreId: CoreMetricId?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTokenColors.current
    val focused by viewModel.focused.collectAsState()
    val settings by viewModel.settings.collectAsState()

    if (coreId == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("指标不存在", color = colors.textSecondary)
        }
        return
    }

    val reading = focused.readings.firstOrNull { it.id == coreId }
    val currentValue = reading?.value
    val accent = metricColor(coreId.source.colorKey)
    val tickMs = viewModel.tickMs()

    val backSource = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()

    // 回看窗口记住上次选择（1.0.14 修复）：以前窗口选择只活在页面 Composition
    // 里，退出详情页再进就被打回默认的 1 分钟。与显示方式同一套 DataStore
    // 持久化，换设备重装才丢。
    val savedWindowSec = settings.metricWindows[coreId]
    val windowIndex = WINDOWS.indexOfFirst { it.first == savedWindowSec }
        .takeIf { it >= 0 } ?: 0
    val windowSec = WINDOWS[windowIndex].first
    val points = viewModel.history(coreId.source, windowSec)
    val values = points.map { it.value }

    val savedMode = settings.metricViewModes[coreId] ?: defaultMetricViewModes()[coreId] ?: MetricViewMode.NUMBER
    // 环形只对有界指标开放，其余回落到波形
    val mode = if (savedMode == MetricViewMode.RING && coreId != CoreMetricId.MEMORY_RATIO) {
        MetricViewMode.WAVE
    } else {
        savedMode
    }

    EnterStagger(index = 0, modifier = Modifier.fillMaxSize()) {
        // 1.0.24：根背景改为透明——壁纸（公司 logo）直接可见，玻璃面板浮在它上面
        // （同 OverviewScreen）。以前这里刷不透明主题渐变，壁纸只能透过玻璃看见。
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // 顶部 72dp：给壁纸右上角的公司 logo 角标留位（角标占 10~60dp，
                // 见 AppNav.drawBrandWallpaper），返回 + 指标名卡片不压着角标。
                .padding(start = 16.dp, end = 16.dp, top = 72.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
        // 顶部：返回 + 指标名 + 当前值
        // 高度交给内容：本页在 verticalScroll 里，子节点拿到的是无界高度约束，
        // 写死 min 高度会让 fillMaxSize 退化成 wrap、内容被 GlassCard 的
        // TopStart 对齐顶到卡顶（1.0.14 修复「数字显示靠上」）。
        GlassCard(
            modifier = Modifier
                .fillMaxWidth(),
            shape = RoundedCornerShape(DesignTokens.Radius.LARGE.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = coreId.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                    )
                    val value = reading?.value
                    if (value == null) {
                        Text(
                            text = reading?.reason ?: "这台设备读不到这个指标",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            modifier = Modifier.padding(top = 6.dp),
                        ) {
                            AnimatedNumber(
                                value = value,
                                intervalMs = tickMs,
                                decimals = coreId.decimals,
                                color = accent,
                                style = MaterialTheme.typography.displaySmall,
                            )
                            Text(
                                text = " ${coreId.unit}",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.textSecondary,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                        // 补充说明小字（如温度降级为电池读数时透明标注，不冒充）
                        reading.note?.let { note ->
                            Text(
                                text = note,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
                Text(
                    text = "返回",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.accent,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .pressScale(backSource)
                        .background(
                            color = colors.accent.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(DesignTokens.Radius.PILL.dp),
                        )
                        .clickable(
                            interactionSource = backSource,
                            indication = null,
                            onClick = {
                                haptics.tick()
                                onBack()
                            },
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        // 时间窗口：往回翻看
        SectionLabel("回看")
        GlassTabs(
            options = WINDOWS.map { it.second },
            selectedIndex = windowIndex,
            onSelect = {
                haptics.select()
                viewModel.setMetricWindow(coreId, WINDOWS[it].first)
            },
            modifier = Modifier.fillMaxWidth(),
        )

        // 轨迹大图
        // 固定高度，不能只用 heightIn(min)：verticalScroll 给子节点无界高度，
        // min 高度下卡片按内容收缩，内部 Box(fillMaxSize) 退化成 wrap——
        // Sparkline / BarSeries 是没有固有尺寸的 Canvas，拿到零高度什么都不画
        // （1.0.10 只修了 Y 轴量程，没修这个布局根因，所以图表一直不显示）。
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
            shape = RoundedCornerShape(DesignTokens.Radius.LARGE.dp),
        ) {
            // 切换显示方式时交叉淡入，不要硬切
            Crossfade(
                targetState = mode,
                animationSpec = enterSpec(),
                label = "detailChart",
            ) { currentMode ->
                Box(modifier = Modifier.fillMaxSize()) {
                    // 采不到就不摆占位符：能显示当前值就先显示当前值，
                    // 轨迹不够两个点、当前值也没有，才说数据不足
                    when {
                        currentMode == MetricViewMode.NUMBER -> {
                            if (currentValue != null) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    AnimatedNumber(
                                        value = currentValue,
                                        intervalMs = tickMs,
                                        decimals = coreId.decimals,
                                        color = accent,
                                        style = MaterialTheme.typography.displayLarge,
                                    )
                                }
                            } else {
                                ChartEmptyHint(colors)
                            }
                        }

                        currentMode == MetricViewMode.RING -> {
                            if (currentValue != null) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    ProgressRing(
                                        progress = currentValue / 100f,
                                        centerText = "${currentValue.toInt()}",
                                        caption = coreId.unit,
                                        color = accent,
                                        trackColor = accent.copy(alpha = 0.18f),
                                        modifier = Modifier.size(150.dp),
                                    )
                                }
                            } else {
                                ChartEmptyHint(colors)
                            }
                        }

                        values.size < 2 -> ChartEmptyHint(colors)

                        currentMode == MetricViewMode.BARS -> BarSeries(
                            points = values,
                            color = accent,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                        )

                        else -> Sparkline(
                            points = values,
                            color = accent,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                        )
                    }
                }
            }
        }

        // 显示模式
        SectionLabel("显示方式")
        val modeOptions = MetricViewMode.ordered
            .filter { it != MetricViewMode.RING || coreId == CoreMetricId.MEMORY_RATIO }
        val modeIndex = modeOptions.indexOf(mode).coerceAtLeast(0)
        GlassTabs(
            options = modeOptions.map { it.label },
            selectedIndex = modeIndex,
            onSelect = { index ->
                viewModel.setMetricViewMode(coreId, modeOptions[index])
            },
            modifier = Modifier.fillMaxWidth(),
        )

        // 统计
        val stats = viewModel.peakStats(coreId.source, windowSec)
        if (stats != null) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(DesignTokens.Radius.CARD.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StatCell("最高", fmt(stats.max, coreId.decimals, coreId.unit))
                    StatCell("最低", fmt(stats.min, coreId.decimals, coreId.unit))
                    StatCell("平均", fmt(stats.avg, coreId.decimals, coreId.unit))
                    StatCell("采样", "${stats.samples}")
                }
            }
        }
    }
    }
}

@Composable
private fun ChartEmptyHint(colors: TokenColors) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "这个时间段还没有采到数据",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    val colors = LocalTokenColors.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = colors.textSecondary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

@Composable
private fun StatCell(label: String, value: String) {
    val colors = LocalTokenColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = colors.textPrimary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary,
        )
    }
}

private fun fmt(value: Float, decimals: Int, unit: String): String =
    "${"%.${decimals}f".format(value)}$unit"
