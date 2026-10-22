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
        val min = points.min()
        val max = points.max()
        val range = (max - min).takeIf { it > 0.0001f } ?: 1f
        val padY = 4.dp.toPx()
        val usableH = (size.height - padY * 2).coerceAtLeast(1f)
        val stepX = size.width / (points.size - 1).coerceAtLeast(1)

        fun pxOf(i: Int): Offset {
            val v = points[i]
            val y = padY + (1f - (v - min) / range) * usableH
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
        val min = points.min()
        val max = points.max()
        val range = (max - min).takeIf { it > 0.0001f } ?: 1f
        val n = points.size
        val slot = size.width / n
        val barW = (slot * 0.6f).coerceAtLeast(1.5.dp.toPx())
        val padY = 3.dp.toPx()
        val usableH = (size.height - padY * 2).coerceAtLeast(1f)
        points.forEachIndexed { i, v ->
            val h = ((v - min) / range) * usableH
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
