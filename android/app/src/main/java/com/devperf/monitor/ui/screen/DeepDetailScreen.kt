package com.devperf.monitor.ui.screen

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devperf.monitor.core.DeepOrigin
import com.devperf.monitor.ui.MonitorViewModel
import com.devperf.monitor.ui.kit.GlassCard
import com.devperf.monitor.ui.kit.Sparkline
import com.devperf.monitor.ui.kit.metricColor
import com.devperf.monitor.ui.kit.pressScale
import com.devperf.monitor.ui.kit.rememberHaptics
import com.devperf.monitor.ui.theme.DesignTokens
import com.devperf.monitor.ui.theme.LocalTokenColors

/** 深度详情页可回看的窗口（内存走势缓冲上限 5 分钟）。 */
private val DEEP_WINDOWS = listOf(60 to "1 分钟", 300 to "5 分钟")

/**
 * 深度指标详情：当前值 + 走势 + 数据来源说明。
 *
 * 数据与总览卡同源（深度采集快照 + 内存走势缓冲），数值行画波形，
 * 文本值行（governor / 充电状态等）只显示当前值。推算值如实标注依据。
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

    val section = deep.sections.firstOrNull { s -> s.rows.any { it.key == deepKey } }
    val row = section?.rows?.firstOrNull { it.key == deepKey }

    val backSource = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    val accent = metricColor(
        DEEP_CATEGORY_COLORS[(section?.category?.ordinal ?: 0) % DEEP_CATEGORY_COLORS.size],
    )

    if (row == null) {
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

    val points = viewModel.deepHistory(row.key, DEEP_WINDOWS[1].first)
    val estimated = row.origin == DeepOrigin.ESTIMATED

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 72.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 顶部：返回 + 指标名 + 当前值
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(DesignTokens.Radius.LARGE.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${section.category.label} · ${row.label}",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = row.value ?: "—",
                        style = MaterialTheme.typography.displaySmall,
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    if (row.unit.isNotBlank()) {
                        Text(
                            text = "单位：${row.unit}",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    // 实测 / 推算如实标注（禁止假数据冒充真实数据）
                    Text(
                        text = if (estimated) "推算值" else "实测值",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    row.note?.let { note ->
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

        // 数值行：走势波形（文本值行没有波形意义，不画）
        val numeric = row.value?.toFloatOrNull()
        if (numeric != null && points.size >= 2) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(DesignTokens.Radius.LARGE.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    Text(
                        text = "最近 ${DEEP_WINDOWS[1].second}走势",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                    )
                    Sparkline(
                        points = points,
                        color = accent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .padding(top = 8.dp),
                    )
                }
            }
        }
    }
}
