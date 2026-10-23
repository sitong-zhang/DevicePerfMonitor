package com.devperf.monitor.ui.kit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devperf.monitor.ui.theme.DesignTokens
import com.devperf.monitor.ui.theme.LocalTokenColors

/**
 * 控件层：Material 3 Expressive 组件 + 品牌语义色（1.0.8 起，取代液态玻璃自绘控件）。
 *
 * 设计原则：
 * 1. 能用 MD3 标准件的全部换成标准件（Button / Switch / Slider / SegmentedButton /
 *    Card），交互、无障碍（TalkBack / 触摸目标 48dp）、状态层全部由框架保证——
 *    自绘时代这三样都要自己维护，是「适配不完整」的重灾区；
 * 2. 配色不用 MD3 默认蓝：主题层（DevicePerfTheme）已把 Catppuccin 语义色映射进
 *    ColorScheme，标准件自动跟随；
 * 3. 按压反馈走主题 ripple（accent）， Expressive 的弹簧动效由
 *    MaterialExpressiveTheme(motionScheme = MotionScheme.expressive()) 统一驱动；
 * 4. 公开 API 名保留 GlassXxx，调用方（四个界面 + 悬浮窗）零改动。
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
    val shape = Pill
    val content: @Composable () -> Unit = {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
        )
    }
    val base = if (compact) Modifier.height(36.dp) else Modifier
    when (variant) {
        GlassButtonVariant.PRIMARY -> Button(
            onClick = onClick,
            modifier = base.then(modifier),
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(),
        ) { content() }
        GlassButtonVariant.SECONDARY -> FilledTonalButton(
            onClick = onClick,
            modifier = base.then(modifier),
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.filledTonalButtonColors(),
        ) { content() }
        GlassButtonVariant.DANGER -> Button(
            onClick = onClick,
            modifier = base.then(modifier),
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) { content() }
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
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = colors.background,
            checkedTrackColor = colors.accent,
            checkedBorderColor = colors.accent,
            uncheckedThumbColor = colors.textSecondary,
            uncheckedTrackColor = colors.surfaceElevated,
            uncheckedBorderColor = colors.textSecondary.copy(alpha = 0.4f),
        ),
    )
}

// ---------------------------------------------------------------------------
// 滑块（MD3 Slider，支持 steps 吸附）
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
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.alpha(if (enabled) 1f else 0.45f),
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
        colors = SliderDefaults.colors(
            thumbColor = colors.accent,
            activeTrackColor = colors.accent,
            inactiveTrackColor = colors.surfaceElevated,
        ),
    )
}

// ---------------------------------------------------------------------------
// 标签页（分段）：MD3 SingleChoiceSegmentedButtonRow
// ---------------------------------------------------------------------------

@Composable
fun GlassTabs(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTokenColors.current
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = colors.accent.copy(alpha = 0.22f),
                    activeContentColor = colors.accent,
                    activeBorderColor = colors.accent.copy(alpha = 0.5f),
                    inactiveContainerColor = colors.surface.copy(alpha = 0.5f),
                    inactiveContentColor = colors.textSecondary,
                    inactiveBorderColor = colors.textSecondary.copy(alpha = 0.25f),
                ),
                label = {
                    Text(
                        text = option,
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 小标签 Chip（MD3 AssistChip，保留自定义着色入口）
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
    if (onClick != null) {
        AssistChip(
            onClick = onClick,
            modifier = modifier,
            label = { Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = base.copy(alpha = if (selected) 0.18f else 0.10f),
                labelColor = base,
            ),
        )
    } else {
        // 纯展示 chip（指标说明等）：无点击态，用同款容器保持视觉一致
        Box(
            modifier = modifier
                .clip(shape = Pill)
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
}

// ---------------------------------------------------------------------------
// 信息行 / 分区标题 / 面板
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

/** 分组标题。 */
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

/**
 * 玻璃面板容器：MD3 Card（tonalElevation 由主题形状/色彩体系驱动）+ 内边距 + 竖向排布。
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DesignTokens.Radius.CARD.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(DesignTokens.Spacing.S16.dp),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S8.dp),
            content = content,
        )
    }
}

// ---------------------------------------------------------------------------
// 玻璃卡片容器（接管原 LiquidGlassSurface 的容器职责）
// ---------------------------------------------------------------------------

/**
 * 内容卡片：MD3 Card + 主题语义色，可点击（按压反馈由 pressScale 传入的
 * interactionSource 驱动）。参数名与旧 LiquidGlassSurface 一致，调用方零改动。
 *
 * [containerColor] 可覆盖容器色：悬浮窗告警态整体变红等场景使用；
 * 不传则走主题 surface。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(DesignTokens.Radius.CARD.dp),
    containerColor: Color? = null,
    onClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(
        containerColor = containerColor ?: MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    )
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            enabled = true,
            shape = shape,
            colors = colors,
            interactionSource = interactionSource,
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                content()
            }
        }
    } else {
        Card(
            modifier = modifier,
            shape = shape,
            colors = colors,
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                content()
            }
        }
    }
}
