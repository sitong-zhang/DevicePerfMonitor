package com.devperf.monitor.ui.screen

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devperf.monitor.core.DeepOrigin
import com.devperf.monitor.core.MetricViewMode
import com.devperf.monitor.ui.MonitorViewModel
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

/** 深度详情页可回看的窗口（秒）与标签。 */
private val DEEP_WINDOWS = listOf(
    60 to "1 分钟",
    300 to "5 分钟",
)

/**
 * 深度指标详情：当前值 + 走势 + 显示方式 + 统计。
 *
 * 1.0.44 起与核心指标详情页（[MetricDetailScreen]）**同一套版式**：
 * 回看窗口选择 → 轨迹大图（数字 / 波形 / 柱状切换）→ 显示方式 → 统计条。
 * 以前深度详情页只有「当前值 + 一张固定波形」，用户点进绝大多数深度卡片
 * 看不到显示方式切换和统计——本次把样式铺平到所有卡片。
 *
 * 文本值行（governor / 充电状态等）不是数值，没有走势与统计，
 * 只显示当前值卡片，不摆空图表（禁止拿占位图冒充数据）。
 */
@Composable
fun DeepDetailScreen(
    viewModel: MonitorViewModel,
    deepKey: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTokenColors.current
    val deep by viewModel.deep.collectAsState()
    val settings by viewModel.settings.collectAsState()

    // 一次遍历同时拿到「所属分类 + 行」：行存在 ⟹ 分类存在，避免两次判空
    val hit = deep.sections.firstNotNullOfOrNull { s ->
        s.rows.firstOrNull { it.key == deepKey }?.let { s to it }
    }

    val backSource = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    val accent = metricColor(
        DEEP_CATEGORY_COLORS[(hit?.first?.category?.ordinal ?: 0) % DEEP_CATEGORY_COLORS.size],
    )

    if (hit == null) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp, top = 72.dp),
        ) {
            Text(
                text = "返回",
                style = MaterialTheme.typography.labelLarge,
                color = colors.accent,
                modifier = Modifier
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
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("指标不存在", color = colors.textSecondary)
            }
        }
        return
    }
    val (section, row) = hit

    val currentValue = row.value?.toFloatOrNull()
    val numeric = currentValue != null

    // 回看窗口记住上次选择（与核心指标同一套持久化，退出再进保留）
    val savedWindowSec = settings.deepWindows[row.key]
    val windowIndex = DEEP_WINDOWS.indexOfFirst { it.first == savedWindowSec }
        .takeIf { it >= 0 } ?: 0
    val windowSec = DEEP_WINDOWS[windowIndex].first
    val points = viewModel.deepHistory(row.key, windowSec)

    // 显示方式：与核心指标同一套（数字 / 波形 / 柱状）；环形要有界量程，
    // 深度指标量程不统一，不开放环形，避免 90fps 卡在 100 上看着像满。
    val modeOptions = MetricViewMode.ordered
        .filter { it != MetricViewMode.RING }
    val savedMode = settings.deepViewModes[row.key] ?: MetricViewMode.WAVE
    val mode = if (savedMode in modeOptions) savedMode else MetricViewMode.WAVE
    val modeIndex = modeOptions.indexOf(mode).coerceAtLeast(0)

    EnterStagger(index = 0, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // 顶部 72dp：给壁纸右上角的公司 logo 角标留位（同 MetricDetailScreen）
                .padding(start = 16.dp, end = 16.dp, top = 72.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 顶部：返回 + 指标名 + 当前值（与核心详情页一致的版式）
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(DesignTokens.Radius.LARGE.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${section.category.label} · ${row.label}",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (row.value != null) {
                            Row(
                                verticalAlignment = Alignment.Bottom,
                                modifier = Modifier.padding(top = 6.dp),
                            ) {
                                Text(
                                    text = row.value,
                                    style = MaterialTheme.typography.displaySmall,
                                    color = accent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (row.unit.isNotBlank()) {
                                    Text(
                                        text = " ${row.unit}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colors.textSecondary,
                                        modifier = Modifier.padding(bottom = 6.dp),
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = row.note ?: "这台设备读不到这个指标",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        // 实测 / 推算如实标注（禁止假数据冒充真实数据）
                        Text(
                            text = when (row.origin) {
                                DeepOrigin.MEASURED -> "实测值"
                                DeepOrigin.ESTIMATED -> "推算值"
                                DeepOrigin.UNAVAILABLE -> "不可用"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        row.note?.let { note ->
                            if (row.value != null) {
                                Text(
                                    text = note,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textSecondary,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
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

            // 文本值行（非数值）没有走势与统计，到此为止——不摆空图表
            if (!numeric) return@EnterStagger

            // 时间窗口：往回翻看
            DeepSectionLabel("回看")
            GlassTabs(
                options = DEEP_WINDOWS.map { it.second },
                selectedIndex = windowIndex,
                onSelect = {
                    haptics.select()
                    viewModel.setDeepWindow(row.key, DEEP_WINDOWS[it].first)
                },
                modifier = Modifier.fillMaxWidth(),
            )

            // 轨迹大图：与核心详情页同高同交互（固定 240dp，切模式交叉淡入）
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
                shape = RoundedCornerShape(DesignTokens.Radius.LARGE.dp),
            ) {
                Crossfade(
                    targetState = mode,
                    animationSpec = enterSpec(),
                    label = "deepDetailChart",
                ) { currentMode ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        when {
                            currentMode == MetricViewMode.NUMBER -> Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "${row.value}${if (row.unit.isNotBlank()) " ${row.unit}" else ""}",
                                    style = MaterialTheme.typography.displayLarge,
                                    color = accent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            points.size < 2 -> DeepChartEmptyHint(colors)

                            currentMode == MetricViewMode.BARS -> BarSeries(
                                points = points,
                                color = accent,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                            )

                            else -> Sparkline(
                                points = points,
                                color = accent,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                            )
                        }
                    }
                }
            }

            // 显示方式
            DeepSectionLabel("显示方式")
            GlassTabs(
                options = modeOptions.map { it.label },
                selectedIndex = modeIndex,
                onSelect = { index ->
                    haptics.select()
                    viewModel.setDeepViewMode(row.key, modeOptions[index])
                },
                modifier = Modifier.fillMaxWidth(),
            )

            // 统计：最高 / 最低 / 平均 / 采样（真实采样点算出来的，不足两点不摆）
            val stats = deepStats(points)
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
                        DeepStatCell("最高", fmt(stats.max, row.unit))
                        DeepStatCell("最低", fmt(stats.min, row.unit))
                        DeepStatCell("平均", fmt(stats.avg, row.unit))
                        DeepStatCell("采样", "${stats.samples}")
                    }
                }
            }

            // 数据来源说明（推算值必须写清依据）
            if (row.origin == DeepOrigin.ESTIMATED && row.note != null) {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(DesignTokens.Radius.CARD.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "推算依据",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.textSecondary,
                        )
                        Text(
                            text = row.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 深度指标统计：与核心指标 [com.devperf.monitor.ui.MonitorViewModel.peakStats] 同口径。 */
private data class DeepStats(
    val max: Float,
    val min: Float,
    val avg: Float,
    val samples: Int,
)

private fun deepStats(points: List<Float>): DeepStats? {
    if (points.isEmpty()) return null
    return DeepStats(
        max = points.max(),
        min = points.min(),
        avg = points.average().toFloat(),
        samples = points.size,
    )
}

private fun fmt(value: Float, unit: String): String {
    val v = "%.${if (kotlin.math.abs(value) < 10f) 2 else 0}f".format(value)
    return if (unit.isNotBlank()) "$v$unit" else v
}

@Composable
private fun DeepChartEmptyHint(colors: TokenColors) {
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
private fun DeepSectionLabel(text: String) {
    val colors = LocalTokenColors.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = colors.textSecondary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

@Composable
private fun DeepStatCell(label: String, value: String) {
    val colors = LocalTokenColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.size(width = 72.dp, height = 20.dp),
            textAlign = TextAlign.Center,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary,
        )
    }
}
