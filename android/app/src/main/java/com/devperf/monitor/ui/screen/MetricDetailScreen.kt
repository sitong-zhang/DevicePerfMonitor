package com.devperf.monitor.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.devperf.monitor.core.MetricId
import com.devperf.monitor.core.PermissionAction
import com.devperf.monitor.ui.MonitorViewModel
import com.devperf.monitor.ui.kit.GlassButton
import com.devperf.monitor.ui.kit.GlassPanel
import com.devperf.monitor.ui.kit.GlassTabs
import com.devperf.monitor.ui.kit.LiquidGlassScaffold
import com.devperf.monitor.ui.kit.MetricValueText
import com.devperf.monitor.ui.kit.metricColor
import com.devperf.monitor.ui.theme.DesignTokens
import com.devperf.monitor.ui.theme.LocalTokenColors

/**
 * 指标详情页：当前值 + 1/5/30 分钟历史图表 + 峰值查看。
 *
 * - 时间窗口用 GlassTabs 分段（自封装，非 MD3）；
 * - 图表为 Vico 折线（Apache-2.0），颜色随主题；
 * - 峰值卡片展示窗口内 max/min/avg/latest，样本不足 2 个时显示
 *   「数据不足，无法分析」（三态规范，不插值伪造）；
 * - NEEDS_PERMISSION 指标提供「申请权限」入口（Shizuku 等）。
 */
@Composable
fun MetricDetailScreen(
    viewModel: MonitorViewModel,
    metricId: MetricId?,
    onBack: () -> Unit,
) {
    val colors = LocalTokenColors.current
    val snapshot by viewModel.snapshot.collectAsState()
    val tickMs = viewModel.tickMs()

    if (metricId == null) {
        // 路由参数非法：显示提示并提供返回
        LiquidGlassScaffold {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "指标不存在",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                    )
                    Spacer(Modifier.height(DesignTokens.Spacing.S16.dp))
                    GlassButton(text = "返回", onClick = onBack)
                }
            }
        }
        return
    }

    var windowIndex by rememberSaveable { mutableIntStateOf(1) } // 默认 5 分钟
    val windowsSec = listOf(60, 300, 1800)
    val windowSec = windowsSec[windowIndex]

    val metric = snapshot.metricOf(metricId)
    val points = viewModel.history(metricId, windowSec)
    val peaks = viewModel.peakStats(metricId, windowSec)
    val accent = metricColor(metricId.colorKey)

    LiquidGlassScaffold {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    PaddingValues(
                        start = DesignTokens.Spacing.S16.dp,
                        end = DesignTokens.Spacing.S16.dp,
                        top = DesignTokens.Spacing.S32.dp,
                        bottom = DesignTokens.Spacing.S32.dp,
                    ),
                ),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S12.dp),
        ) {
            // ---- 顶栏：返回 + 指标名 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassButton(text = "‹ 返回", onClick = onBack, compact = true)
                Spacer(Modifier.padding(horizontal = DesignTokens.Spacing.S4.dp))
                Text(
                    text = metricId.label,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary,
                )
            }

            // ---- 当前值 ----
            GlassPanel {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(DesignTokens.Spacing.S16.dp),
                ) {
                    Text(
                        text = "当前值（${metricId.unit}）",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                    )
                    Spacer(Modifier.height(DesignTokens.Spacing.S8.dp))
                    if (metric != null) {
                        MetricValueText(
                            metricId = metricId,
                            metric = metric,
                            intervalMs = tickMs,
                            onPermissionAction = { action ->
                                when (action) {
                                    PermissionAction.SHIZUKU -> viewModel.requestPrivilege()
                                    else -> Unit
                                }
                            },
                        )
                    } else {
                        Text(
                            text = "该指标无历史采集（仅实时采样，未纳入历史）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                        )
                    }
                }
            }

            // ---- 时间窗口切换 ----
            GlassTabs(
                options = listOf("1 分钟", "5 分钟", "30 分钟"),
                selectedIndex = windowIndex,
                onSelect = { windowIndex = it },
                modifier = Modifier.fillMaxWidth(),
            )

            // ---- 历史图表 ----
            GlassPanel {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(DesignTokens.Spacing.S12.dp),
                ) {
                    MetricChart(points = points, modifier = Modifier.fillMaxWidth())
                    ChartCaption(points = points, modifier = Modifier.padding(top = DesignTokens.Spacing.S8.dp))
                }
            }

            // ---- 峰值查看 ----
            GlassPanel {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(DesignTokens.Spacing.S16.dp),
                    verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S8.dp),
                ) {
                    Text(
                        text = "窗口峰值（${windowsSec[windowIndex] / 60} 分钟）",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                    )
                    if (peaks != null) {
                        PeakRow("最高", peaks.max, accent)
                        PeakRow("最低", peaks.min, accent)
                        PeakRow("平均", peaks.avg, accent)
                        PeakRow("最新", peaks.latest, accent)
                        Text(
                            text = "样本数 ${peaks.samples}",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textSecondary,
                        )
                    } else {
                        Text(
                            text = "数据不足，无法分析",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PeakRow(label: String, value: Float, color: androidx.compose.ui.graphics.Color) {
    val colors = LocalTokenColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        com.devperf.monitor.ui.kit.MonoNumber(
            text = formatPeak(value),
            color = color,
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

private fun formatPeak(value: Float): String =
    if (value >= 100f || value <= -100f) value.toInt().toString()
    else if (value == value.toInt().toFloat()) value.toInt().toString()
    else String.format("%.1f", value)
