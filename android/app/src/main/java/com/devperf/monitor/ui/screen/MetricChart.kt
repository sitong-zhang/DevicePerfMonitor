package com.devperf.monitor.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.devperf.monitor.data.storage.HistoryPoint
import com.devperf.monitor.ui.theme.DesignTokens
import com.devperf.monitor.ui.theme.LocalTokenColors
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.lineSeries
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme

/**
 * 指标历史折线图（Vico 3.2.3，Apache-2.0）。
 *
 * 设计约束：
 * - 线条/文字颜色跟随本应用液态玻璃主题（rememberM3VicoTheme 基于 MaterialTheme，
 *   而 MaterialTheme 配色来自 design-tokens.json），不使用库默认蓝；
 * - 空数据显示占位文案（三态规范：数据不足不画假曲线）；
 * - x 轴为相对时间（秒），y 轴为指标值。
 */
@Composable
fun MetricChart(
    points: List<HistoryPoint>,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTokenColors.current
    val vicoTheme = rememberM3VicoTheme(
        lineCartesianLayerColors = listOf(colors.accent),
        lineColor = colors.textSecondary,
        textColor = colors.textSecondary,
    )

    if (points.size < 2) {
        Box(
            modifier = modifier.height(180.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "数据不足，无法绘制图表",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
        }
        return
    }

    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(points) {
        modelProducer.runTransaction {
            lineSeries {
                series(
                    x = points.map { (it.timestampMs - points.first().timestampMs) / 1000.0 },
                    y = points.map { it.value.toDouble() },
                )
            }
        }
    }

    ProvideVicoTheme(vicoTheme) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(),
                startAxis = VerticalAxis.rememberStart(),
                bottomAxis = HorizontalAxis.rememberBottom(
                    valueFormatter = { _, value, _ ->
                        // 相对秒 → mm:ss
                        val total = value.toInt()
                        if (total >= 60) "${total / 60}:${(total % 60).toString().padStart(2, '0')}" else "${total}s"
                    },
                ),
            ),
            modelProducer = modelProducer,
            modifier = modifier
                .fillMaxWidth()
                .height(180.dp),
        )
    }
}

/** 图表下方的时间跨度说明。 */
@Composable
fun ChartCaption(points: List<HistoryPoint>, modifier: Modifier = Modifier) {
    val colors = LocalTokenColors.current
    if (points.size < 2) {
        Box(modifier = modifier)
        return
    }
    val spanSec = (points.last().timestampMs - points.first().timestampMs) / 1000
    Text(
        text = "最近 ${spanSec} 秒 · ${points.size} 个采样点",
        style = MaterialTheme.typography.labelMedium,
        color = colors.textSecondary,
        modifier = modifier,
    )
}

/** 供详情页峰值卡片使用的间距常量（与 design-tokens 对齐）。 */
internal val ChartSpacing = DesignTokens.Spacing.S8.dp
