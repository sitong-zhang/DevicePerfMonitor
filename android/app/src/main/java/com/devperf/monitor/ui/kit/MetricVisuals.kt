package com.devperf.monitor.ui.kit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 指标的可视化组件：环形仪表 / 波形线 / 柱状。
 *
 * 全部用 Canvas 自绘，不引图表库——这些图形要塞进卡片和悬浮窗里，
 * 图表库太重，而且悬浮窗里用重型组件容易在绘制期出问题。
 *
 * 空数据一律画占位而不是画 0：采不到就表明采不到，不编数字。
 */

/**
 * 图表 Y 轴自适应范围：抗离群点 + 最小可范围。
 *
 * 背景（1.0.9 用户反馈「所有数据都偏上」）：之前直接用 min/max 做量程，
 * 一个离群低点（内存偶尔跌到 78.0，其余 59 个点全在 79.5~80.0）把 range
 * 拉宽到 2.0，密集点全被压到图表顶部，看起来就是"贴顶一条线"。
 *
 * 现在的规则：
 * - 主体范围取 2% / 98% 分位数，离群点不参与量程（仍会被画出来，
 *   由调用方 coerce 到边缘，不会被藏掉）；
 * - 主体范围过窄（近恒定数据，如 60Hz 刷新率全程一条直线）时扩展到
 *   最小可范围（值的 2%），让数据落在中部、微小波动可见，
 *   而不是被压成一条贴边直线；
 * - 单点/空数据给一个以该值为中心的兜底范围。
 */
internal fun autoRange(values: List<Float>): ClosedFloatingPointRange<Float> {
    if (values.isEmpty()) return 0f..1f
    if (values.size == 1) {
        val v = values[0]
        val pad = minRangeOf(v)
        return (v - pad / 2f)..(v + pad / 2f)
    }
    val sorted = values.sorted()
    val loIdx = ((sorted.size - 1) * 0.02f).toInt().coerceIn(0, sorted.lastIndex)
    val hiIdx = ((sorted.size - 1) * 0.98f).toInt().coerceIn(loIdx, sorted.lastIndex)
    var lo = sorted[loIdx]
    var hi = sorted[hiIdx]
    // 近恒定数据：扩到最小可范围，让线落在中部而不是贴边
    if (hi - lo < minRangeOf((lo + hi) / 2f)) {
        val mid = (lo + hi) / 2f
        val pad = minRangeOf(mid)
        lo = mid - pad / 2f
        hi = mid + pad / 2f
    }
    return lo..hi
}

/** 最小可范围：量级越大允许的噪声范围越大（内存 80 → ±0.8；帧率 60 → ±0.6）。 */
private fun minRangeOf(v: Float): Float = maxOf(kotlin.math.abs(v) * 0.02f, 0.5f)

/** 环形进度（健康分、内存占用率这类 0~100 有界值）。 */
@Composable
fun ProgressRing(
    progress: Float,
    centerText: String,
    caption: String,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 10.dp,
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 600),
        label = "ring",
    )
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (animated > 0f) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = 360f * animated,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = centerText,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 波形线：历史采样点的上下波动。
 * 点数不足两个时画一条占位虚线，不画 0 线误导。
 */
@Composable
fun Sparkline(
    points: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    fillAlpha: Float = 0.20f,
) {
    Canvas(modifier = modifier) {
        if (points.size < 2) {
            // 数据不足：画一条中位虚线占位
            val y = size.height / 2f
            val dash = 6.dp.toPx()
            var x = 0f
            while (x < size.width) {
                drawLine(
                    color = color.copy(alpha = 0.25f),
                    start = Offset(x, y),
                    end = Offset((x + dash).coerceAtMost(size.width), y),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                x += dash * 2
            }
            return@Canvas
        }
        // Y 轴范围：分位数 + 最小可范围（抗离群点，近恒定数据居中，见 autoRange）
        val range_ = autoRange(points)
        val lo = range_.start
        val span = (range_.endInclusive - lo).takeIf { it > 0.0001f } ?: 1f
        val padY = 4.dp.toPx()
        val usableH = (size.height - padY * 2).coerceAtLeast(1f)
        val stepX = size.width / (points.size - 1).coerceAtLeast(1)

        fun pxOf(i: Int): Offset {
            val v = points[i]
            // 离群点 coerce 到边缘：不被藏掉，也不绑架量程
            val t = ((v - lo) / span).coerceIn(0f, 1f)
            val y = padY + (1f - t) * usableH
            return Offset(i * stepX, y)
        }

        val line = Path()
        points.indices.forEach { i ->
            val p = pxOf(i)
            if (i == 0) line.moveTo(p.x, p.y) else line.lineTo(p.x, p.y)
        }

        val fill = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(
            fill,
            Brush.verticalGradient(
                listOf(color.copy(alpha = fillAlpha), Color.Transparent),
            ),
        )
        drawPath(
            line,
            color,
            style = Stroke(
                width = 2.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

/** 柱状：离散采样条，看分布和突变更清楚。 */
@Composable
fun BarSeries(
    points: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (points.isEmpty()) return@Canvas
        // Y 轴范围：分位数 + 最小可范围（抗离群点，近恒定数据居中，见 autoRange）
        val range_ = autoRange(points)
        val lo = range_.start
        val span = (range_.endInclusive - lo).takeIf { it > 0.0001f } ?: 1f
        val n = points.size
        val slot = size.width / n
        val barW = (slot * 0.6f).coerceAtLeast(1.5.dp.toPx())
        val padY = 3.dp.toPx()
        val usableH = (size.height - padY * 2).coerceAtLeast(1f)
        points.forEachIndexed { i, v ->
            // 离群点 coerce 到满高：不被藏掉，也不绑架量程
            val h = ((v - lo) / span).coerceIn(0f, 1f) * usableH
            val left = i * slot + (slot - barW) / 2f
            drawRoundRect(
                color = color.copy(alpha = 0.85f),
                topLeft = Offset(left, size.height - padY - h.coerceAtLeast(2.dp.toPx())),
                size = Size(barW, h.coerceAtLeast(2.dp.toPx())),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2f, barW / 2f),
            )
        }
    }
}
