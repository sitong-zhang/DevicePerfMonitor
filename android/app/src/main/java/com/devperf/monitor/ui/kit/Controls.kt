package com.devperf.monitor.ui.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devperf.monitor.ui.theme.DesignTokens
import com.devperf.monitor.ui.theme.LocalAppMotion
import com.devperf.monitor.ui.theme.LocalTokenColors

/**
 * 自封装液态玻璃控件库。
 *
 * 硬性要求（对应需求「禁止系统默认控件与默认蓝」）：
 * 全部控件基于 Compose Foundation 原语 + 主题语义色自绘，
 * 不使用 MD3 的 Button / Switch / Slider / TabRow 等默认控件；
 * 按压反馈使用主题色 ripple（accent），不用 MD3 默认蓝色。
 */

private val Pill = RoundedCornerShape(percent = 50)

// ---------------------------------------------------------------------------
// 按钮
// ---------------------------------------------------------------------------

enum class GlassButtonVariant { PRIMARY, SECONDARY, DANGER }

@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: GlassButtonVariant = GlassButtonVariant.PRIMARY,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val colors = LocalTokenColors.current
    val contentColor = when (variant) {
        GlassButtonVariant.PRIMARY -> colors.accent
        GlassButtonVariant.SECONDARY -> colors.textPrimary
        GlassButtonVariant.DANGER -> colors.danger
    }
    Box(
        modifier = modifier
            .height(if (compact) 36.dp else 48.dp)
            .clip(Pill)
            .background(
                when (variant) {
                    GlassButtonVariant.PRIMARY -> colors.accent.copy(alpha = 0.16f)
                    GlassButtonVariant.SECONDARY -> colors.surface.copy(alpha = 0.5f)
                    GlassButtonVariant.DANGER -> colors.danger.copy(alpha = 0.14f)
                }
            )
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = contentColor),
                onClick = onClick,
            )
            .padding(horizontal = if (compact) 14.dp else 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) contentColor else colors.textSecondary.copy(alpha = 0.5f),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

// ---------------------------------------------------------------------------
// 开关
// ---------------------------------------------------------------------------

@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTokenColors.current
    val motion = LocalAppMotion.current
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = motion.emphasizedDurationMs, easing = motion.emphasized),
        label = "switch",
    )
    val trackW = 52.dp
    val thumbD = 24.dp
    Box(
        modifier = modifier
            .size(trackW, 32.dp)
            .clip(Pill)
            .background(lerp(colors.surfaceElevated, colors.accent, progress))
            .clickable(
                indication = ripple(color = colors.accent, bounded = false),
                interactionSource = remember { MutableInteractionSource() },
                onClick = { onCheckedChange(!checked) },
            )
            .padding(4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = (trackW - thumbD - 8.dp) * progress)
                .size(thumbD)
                .clip(Pill)
                .background(Color.White)
        )
    }
}

// ---------------------------------------------------------------------------
// 滑块（自绘轨道 + 滑块，支持 steps 吸附）
// ---------------------------------------------------------------------------

@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
) {
    val colors = LocalTokenColors.current
    val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start))
        .coerceIn(0f, 1f)
    val trackH = 6.dp
    val thumbR = 11.dp

    fun pxToValue(px: Float, widthPx: Float): Float {
        if (widthPx <= 0f) return value
        val f = (px / widthPx).coerceIn(0f, 1f)
        var raw = valueRange.start + f * (valueRange.endInclusive - valueRange.start)
        if (steps > 0) {
            val stepSize = (valueRange.endInclusive - valueRange.start) / (steps + 1)
            raw = kotlin.math.round(raw / stepSize) * stepSize
        }
        return raw.coerceIn(valueRange.start, valueRange.endInclusive)
    }

    Box(
        modifier = modifier
            .height(40.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .pointerInput(enabled, valueRange, steps) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    onValueChange(pxToValue(down.position.x, size.width.toFloat()))
                    drag(down.id) { change ->
                        onValueChange(pxToValue(change.position.x, size.width.toFloat()))
                    }
                }
            }
            .drawBehind {
                val widthPx = size.width
                val centerY = size.height / 2f
                val trackHpx = trackH.toPx()
                val radius = CornerRadius(trackHpx / 2f)
                // 底轨
                drawRoundRect(
                    color = colors.surfaceElevated,
                    topLeft = Offset(0f, centerY - trackHpx / 2f),
                    size = Size(widthPx, trackHpx),
                    cornerRadius = radius,
                )
                // 已选轨（accent）
                drawRoundRect(
                    color = colors.accent,
                    topLeft = Offset(0f, centerY - trackHpx / 2f),
                    size = Size(widthPx * fraction, trackHpx),
                    cornerRadius = radius,
                )
                // 滑块：accent 底盘 + 白芯 + 顶部高光 + 1px 暗描边
                val thumbX = widthPx * fraction
                val thumbPx = thumbR.toPx() * 2f
                drawCircle(
                    color = Color.Black.copy(alpha = 0.10f),
                    radius = thumbPx / 2f,
                    center = Offset(thumbX, centerY + 0.5f),
                )
                drawCircle(
                    color = colors.accent,
                    radius = thumbPx / 2f,
                    center = Offset(thumbX, centerY),
                )
                drawCircle(
                    brush = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.95f),
                        1f to Color.White.copy(alpha = 0.45f),
                    ),
                    radius = thumbPx / 2f - 3f,
                    center = Offset(thumbX, centerY),
                )
            },
    )
}

// ---------------------------------------------------------------------------
// 标签页（分段）
// ---------------------------------------------------------------------------

@Composable
fun GlassTabs(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTokenColors.current
    val motion = LocalAppMotion.current
    Row(
        modifier = modifier
            .clip(Pill)
            .background(colors.surface.copy(alpha = 0.5f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            val bg by animateColorAsState(
                targetValue = if (selected) colors.accent.copy(alpha = 0.22f) else Color.Transparent,
                animationSpec = tween(motion.emphasizedDurationMs),
                label = "tabBg",
            )
            val textColor by animateColorAsState(
                targetValue = if (selected) colors.accent else colors.textSecondary,
                animationSpec = tween(motion.emphasizedDurationMs),
                label = "tabText",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(Pill)
                    .background(bg)
                    .clickable(
                        indication = ripple(color = colors.accent, bounded = false),
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = { onSelect(index) },
                    )
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option,
                    color = textColor,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 小标签 Chip
// ---------------------------------------------------------------------------

@Composable
fun GlassChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    color: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalTokenColors.current
    val base = color ?: if (selected) colors.accent else colors.textSecondary
    Box(
        modifier = modifier
            .clip(Pill)
            .background(base.copy(alpha = if (selected) 0.18f else 0.10f))
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        indication = ripple(color = base, bounded = false),
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onClick,
                    )
                } else Modifier
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = base,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// 信息行 / 分区标题 / 玻璃面板
// ---------------------------------------------------------------------------

@Composable
fun GlassInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalTokenColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DesignTokens.Radius.SMALL.dp))
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        indication = ripple(color = colors.accent),
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onClick,
                    )
                } else Modifier
            )
            .padding(
                horizontal = DesignTokens.Spacing.S12.dp,
                vertical = DesignTokens.Spacing.S12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = colors.textSecondary,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            color = valueColor ?: colors.textPrimary,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/** 分组标题（玻璃分区）。 */
@Composable
fun GlassSectionHeader(text: String, modifier: Modifier = Modifier) {
    val colors = LocalTokenColors.current
    Text(
        text = text,
        color = colors.textSecondary,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier.padding(
            start = DesignTokens.Spacing.S4.dp,
            top = DesignTokens.Spacing.S16.dp,
            bottom = DesignTokens.Spacing.S8.dp,
        ),
    )
}

/** 禁用态整行变灰的小工具。 */
fun Modifier.disabledAlpha(enabled: Boolean): Modifier =
    if (enabled) this else this.alpha(0.45f)

/** 玻璃面板容器：玻璃卡片 + 内边距 + 竖向排布。 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    LiquidGlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DesignTokens.Radius.CARD.dp),
    ) {
        Column(
            modifier = Modifier.padding(DesignTokens.Spacing.S16.dp),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S8.dp),
            content = content,
        )
    }
}
