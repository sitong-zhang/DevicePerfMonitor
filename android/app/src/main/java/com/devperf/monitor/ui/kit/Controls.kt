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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devperf.monitor.BuildConfig
import com.devperf.monitor.ui.theme.DesignTokens
import com.devperf.monitor.ui.theme.LocalTokenColors
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.catalog.components.LiquidButton
import com.kyant.backdrop.catalog.components.LiquidSlider
import com.kyant.backdrop.catalog.components.LiquidToggle
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import kotlin.math.roundToInt

/**
 * 控件层：主窗口按钮/开关/滑块直接调用 Kyant0 catalog 液态玻璃组件，
 * 其余为 Material 3 Expressive 组件 + 品牌语义色（1.0.8 起取代自绘控件）。
 *
 * 设计原则：
 * 1. 主窗口（[LocalBackdrop] 非 null）的 GlassButton / GlassSwitch / GlassSlider
 *    直接调用 Kyant0/AndroidLiquidGlass catalog 的 LiquidButton / LiquidToggle /
 *    LiquidSlider——源码已原样 vendor 到 com.kyant.backdrop.catalog.components，
 *    效果参数与官方 demo 完全一致（vibrancy + blur + lens + InteractiveHighlight +
 *    DampedDragAnimation），不自己调参；
 * 2. 悬浮窗 / 无玻璃（backdrop 为 null）回落 MD3 标准件（Button / Switch / Slider /
 *    SegmentedButton / Card），交互、无障碍（TalkBack / 触摸目标 48dp）、状态层
 *    全部由框架保证；
 * 3. 配色不用 MD3 默认蓝：主题层（DevicePerfTheme）已把语义色映射进
 *    ColorScheme，标准件自动跟随；
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
    val backdrop = if (BuildConfig.LIQUID_GLASS) LocalBackdrop.current else null
    val cs = MaterialTheme.colorScheme
    val container: Color
    val contentColor: Color
    when (variant) {
        GlassButtonVariant.PRIMARY -> {
            container = cs.primary
            contentColor = cs.onPrimary
        }
        GlassButtonVariant.SECONDARY -> {
            container = cs.secondaryContainer
            contentColor = cs.onSecondaryContainer
        }
        GlassButtonVariant.DANGER -> {
            container = cs.error
            contentColor = cs.onError
        }
    }
    if (backdrop != null && enabled) {
        // 主窗口（可交互态）：直接调用 Kyant0/AndroidLiquidGlass catalog 的
        // LiquidButton——源码已原样 vendor 到 com.kyant.backdrop.catalog.components，
        // 效果参数（Capsule + vibrancy + blur(2dp) + lens(12dp,24dp) + InteractiveHighlight
        // 按压高光/拖动回弹）与官方 demo 完全一致，一个数不改。
        // 变体色经 surfaceColor 叠加（0.5 透明，玻璃纹样透出）；compact 的高度走
        // 外层 modifier——修饰符链最外层的高度优先生效，组件内部的 48dp 会被压到 36dp。
        LiquidButton(
            onClick = onClick,
            backdrop = backdrop,
            modifier = modifier
                .then(if (compact) Modifier.height(36.dp) else Modifier),
            isInteractive = true,
            surfaceColor = container.copy(alpha = 0.5f),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1,
            )
        }
        return
    }
    // 悬浮窗 / 无玻璃 / 禁用态：MD3 原样按钮
    val height = if (compact) 36.dp else 48.dp
    val shape = Pill
    val interaction = remember { MutableInteractionSource() }
    val surface = if (backdrop != null) {
        // 禁用态兜底：仍用 backdrop 玻璃面，整体 alpha 压暗
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { Capsule() },
            effects = {
                vibrancy()
                blur(2f.dp.toPx())
                lens(12f.dp.toPx(), 24f.dp.toPx())
            },
            onDrawSurface = { drawRect(container.copy(alpha = 0.5f)) },
        )
    } else {
        Modifier.background(container, shape)
    }
    Box(
        modifier = Modifier
            .height(height)
            .then(modifier)
            .then(surface)
            .clip(shape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = contentColor),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .then(if (enabled) Modifier else Modifier.alpha(0.45f))
            .padding(horizontal = if (compact) 14.dp else 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1,
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
    val backdrop = if (BuildConfig.LIQUID_GLASS) LocalBackdrop.current else null
    val colors = LocalTokenColors.current
    if (backdrop == null) {
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
        return
    }
    // 主窗口：直接调用 Kyant0/AndroidLiquidGlass catalog 的 LiquidToggle
    // （vendor 源码，未改任何参数）——64×28dp 轨道 + 40×24dp 玻璃滑块，
    // blur/lens 随按压进度插值，支持拖动切换；滑块内部再叠一层轨道录制的
    // backdrop（官方同款「玻璃嵌玻璃」结构）。
    LiquidToggle(
        selected = { checked },
        onSelect = onCheckedChange,
        backdrop = backdrop,
        modifier = modifier,
    )
}

// ---------------------------------------------------------------------------
// 滑块（1.0.31 恢复 Kyant0 原版，用户明确要求「直接抄原仓库的技术」）
//
// 视觉与手感完全交给 vendored 的 Kyant0/AndroidLiquidGlass LiquidSlider：
// DampedDragAnimation 弹簧物理 + 按压放大/速度形变 + InteractiveHighlight +
// 玻璃嵌玻璃（滑块 backdrop 内叠轨道录制），与官方 demo 参数一字不改。
//
// 跟手的关键不在组件而在接线：视觉值绝不能直接用 ViewModel 的 DataStore 异步
// 往返驱动（拖动 → 持久化 → StateFlow 回流 → 重组，链路几十毫秒且被 conflate，
// 表现就是「滑动停顿、不跟手」——1.0.29 与 1.0.30 两版都栽在这）。这里持有
// 本地即时 mutableFloatStateOf：手势先写本地（同帧生效、下一帧上屏），持久化
// 异步外发；外部值回流时再同步本地（StateFlow conflate 去重，快拖不回卷）。
// steps 量化在回调里完成：值被吸附到档位，内部弹簧追赶档位，档位手感自然。
// 悬浮窗 / 无玻璃 / 禁用态仍回落 MD3 标准 Slider（本身即 1:1 跟手）。
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
    val backdrop = if (BuildConfig.LIQUID_GLASS) LocalBackdrop.current else null

    // 悬浮窗 / 无玻璃（backdrop 为 null）/ 禁用态：MD3 标准件
    if (backdrop == null || !enabled) {
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
        return
    }

    // 主窗口：Kyant0/AndroidLiquidGlass 原版 LiquidSlider（vendor 源码未改参数）。
    val span = valueRange.endInclusive - valueRange.start
    val stepSize = if (steps > 0) span / (steps + 1) else 0f
    fun snap(v: Float): Float =
        if (steps > 0) {
            val idx = ((v - valueRange.start) / stepSize).roundToInt()
            (valueRange.start + idx * stepSize).coerceIn(valueRange)
        } else {
            v.coerceIn(valueRange)
        }

    // 本地即时视觉值：手势先写它（同帧生效），持久化异步外发
    var localValue by remember { mutableFloatStateOf(value) }
    // 外部值变化（首帧 / 外部重置 / 异步回流）时同步本地
    LaunchedEffect(value) { localValue = value }
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    LiquidSlider(
        value = { localValue },
        onValueChange = { raw ->
            val snapped = snap(raw)
            localValue = snapped
            currentOnValueChange(snapped)
        },
        valueRange = valueRange,
        visibilityThreshold = span / 2000f,
        backdrop = backdrop,
        modifier = modifier,
    )
}

// ---------------------------------------------------------------------------
// 标签页（分段）：主窗口用 LiquidButton 三联胶囊，悬浮窗回落 MD3 分段按钮
// ---------------------------------------------------------------------------

@Composable
fun GlassTabs(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTokenColors.current
    val backdrop = if (BuildConfig.LIQUID_GLASS) LocalBackdrop.current else null
    if (backdrop != null) {
        // 每个选项一颗独立的 LiquidButton 玻璃胶囊（官方组件：vibrancy + blur +
        // lens + InteractiveHighlight 按压反馈），选中态用 tint 区分。
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEachIndexed { index, option ->
                val selected = index == selectedIndex
                LiquidButton(
                    onClick = { onSelect(index) },
                    backdrop = backdrop,
                    modifier = Modifier.weight(1f),
                    isInteractive = true,
                    tint = if (selected) {
                        colors.accent.copy(alpha = 0.35f)
                    } else {
                        Color.Transparent
                    },
                    surfaceColor = if (selected) {
                        colors.accent.copy(alpha = 0.5f)
                    } else {
                        Color.White.copy(alpha = 0.06f)
                    },
                ) {
                    Text(
                        text = option,
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        color = if (selected) colors.accent else colors.textSecondary,
                    )
                }
            }
        }
        return
    }
    // 悬浮窗 / 无玻璃（backdrop 为 null）：MD3 标准分段按钮
    val segColors = SegmentedButtonDefaults.colors(
        activeContainerColor = colors.accent.copy(alpha = 0.22f),
        activeContentColor = colors.accent,
        activeBorderColor = colors.accent.copy(alpha = 0.5f),
        inactiveContainerColor = colors.surface.copy(alpha = 0.5f),
        inactiveContentColor = colors.textSecondary,
        inactiveBorderColor = colors.textSecondary.copy(alpha = 0.25f),
    )
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                colors = segColors,
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
        val backdrop = if (BuildConfig.LIQUID_GLASS) LocalBackdrop.current else null
        val chipSurface = if (backdrop != null) {
            Modifier.glassSurface(backdrop = backdrop, shape = Pill, tint = base.copy(alpha = 0.18f))
        } else {
            Modifier.background(base.copy(alpha = 0.10f), Pill)
        }
        Box(
            modifier = modifier
                .clip(shape = Pill)
                .then(chipSurface)
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
 * 玻璃面板容器：MD3 Card + 玻璃表面 / 层次三件套（1.0.13 双包）。
 *
 * glass flavor 主窗口（[LocalBackdrop] 非 null）时容器表面走 Kyant0 Backdrop 的
 * [glassSurface]（Card 容器色透明，让玻璃显出来）；plain flavor 与悬浮窗窗口
 * （backdrop 为 null，跨窗口捕获无意义）回落 MD3 Card + [elevatedCardChrome]。
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    /**
     * 是否自带内边距。默认 true——内边距由本组件统一承担（token: surface.cardPadding），
     * 调用方不要再包一层 padding。设置页以前是「Panel 内 16 + 内部 Column 又 16」，
     * 实为 32dp，就是这个开关没统一导致的。
     */
    padded: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(DesignTokens.Radius.CARD.dp)
    val glassBackdrop = if (BuildConfig.LIQUID_GLASS) LocalBackdrop.current else null
    Card(
        modifier = modifier.fillMaxWidth().then(
            if (glassBackdrop != null) {
                Modifier.glassSurface(
                    backdrop = glassBackdrop,
                    shape = shape,
                    tint = MaterialTheme.colorScheme.surfaceContainer,
                )
            } else {
                Modifier.elevatedCardChrome(shape)
            }
        ),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (glassBackdrop != null) {
                Color.Transparent
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            modifier = if (padded) {
                Modifier.padding(DesignTokens.Surface.CARD_PADDING.dp)
            } else {
                Modifier
            },
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S8.dp),
            content = content,
        )
    }
}

// ---------------------------------------------------------------------------
// 玻璃卡片容器（接管原 LiquidGlassSurface 的容器职责）
// ---------------------------------------------------------------------------

/** 投影层级：1=卡片，2=浮动底栏，3=弹层 / 拖拽中的悬浮窗。 */
enum class ElevationLevel { LEVEL1, LEVEL2, LEVEL3 }

/**
 * 卡片的「层次三件套」：真阴影 + 描边 + 顶部高光。
 *
 * 以前卡片就是一张纯色 Card——零阴影、零描边、零高光，堆在背景上完全没有前后
 * 关系，这是界面显"平/丑"的直接原因。三件套参数全部来自 design-tokens
 * （surface.borderAlpha / highlightAlpha、elevation.levelN），不在这里写死。
 *
 * 1.0.13 双包：
 * - plain flavor（BuildConfig.LIQUID_GLASS = false）整段跳过，回归纯 MD3 Card；
 * - glass flavor 的主窗口卡片已改走 [glassSurface]（Kyant0 Backdrop，高光与
 *   投影用库默认值），三件套只留在悬浮窗窗口——那里跨窗口捕获 backdrop 无
 *   意义，回落 MD3 + 手绘层次，浮在别的应用之上仍有立体感。
 *
 * 注意顺序：shadow 必须在 clip 之前，高光画在内容之上、描边之下。
 */
@Composable
private fun Modifier.elevatedCardChrome(
    shape: Shape,
    level: ElevationLevel = ElevationLevel.LEVEL1,
): Modifier {
    if (!BuildConfig.LIQUID_GLASS) return this
    val spec = when (level) {
        ElevationLevel.LEVEL1 -> Triple(
            DesignTokens.Elevation.LEVEL1.SHADOW_Y,
            DesignTokens.Elevation.LEVEL1.ALPHA,
            DesignTokens.Elevation.LEVEL1.RADIUS,
        )
        ElevationLevel.LEVEL2 -> Triple(
            DesignTokens.Elevation.LEVEL2.SHADOW_Y,
            DesignTokens.Elevation.LEVEL2.ALPHA,
            DesignTokens.Elevation.LEVEL2.RADIUS,
        )
        ElevationLevel.LEVEL3 -> Triple(
            DesignTokens.Elevation.LEVEL3.SHADOW_Y,
            DesignTokens.Elevation.LEVEL3.ALPHA,
            DesignTokens.Elevation.LEVEL3.RADIUS,
        )
    }
    val (shadowY, shadowAlpha, shadowRadius) = spec
    val shade = Color.Black.copy(alpha = shadowAlpha)
    return this
        .shadow(
            // Compose 的阴影扩散由 elevation 决定，这里把 token 的 shadowY 与 radius
            // 合成为一个 elevation（radius 越大越"蓬松"）
            elevation = (shadowY + shadowRadius / 4f).dp,
            shape = shape,
            clip = false,
            ambientColor = shade,
            spotColor = shade,
        )
        .clip(shape)
        .drawWithContent {
            drawContent()
            // 顶部高光：模拟玻璃上沿的反光，只覆盖上半部分，浓度很低
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = DesignTokens.Surface.HIGHLIGHT_ALPHA),
                        Color.Transparent,
                    ),
                    endY = size.height * 0.55f,
                ),
            )
        }
        .border(
            border = BorderStroke(
                width = DesignTokens.Surface.BORDER_WIDTH.dp,
                color = MaterialTheme.colorScheme.outline
                    .copy(alpha = DesignTokens.Surface.BORDER_ALPHA),
            ),
            shape = shape,
        )
}

/**
 * 玻璃表面：Kyant0 Backdrop 的 drawBackdrop（1.0.13 glass flavor 专用）。
 *
 * 库：io.github.kyant0:backdrop 2.0.0-alpha03（Kyant0/AndroidLiquidGlass，Apache-2.0）。
 * 效果参数直接照抄库 catalog 的 ControlCenter 示例（catalog/destinations/
 * ControlCenterContent.kt 的既有值）：vibrancy() + lens(24dp, 48dp, depthEffect=true)，
 * 不再自调 blur。高光/投影走库默认值（DefaultHighlight / DefaultShadow）。
 * surface 层用主题 surfaceContainer 40% 透明：玻璃透出来、文字对比度也不塌。
 *
 * 生效条件：glass flavor 且处于主窗口（[LocalBackdrop] 非 null）。plain flavor
 * 与悬浮窗窗口由调用方回落 [elevatedCardChrome]。
 * 降版本由库内部处理：lens 需 Android 13+，更低版本自动跳过效果（只保留采样层），不崩。
 */
private fun Modifier.glassSurface(
    backdrop: LayerBackdrop,
    shape: Shape,
    tint: Color,
): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        vibrancy()
        lens(24f.dp.toPx(), 48f.dp.toPx(), depthEffect = true)
    },
    onDrawSurface = {
        drawRect(color = tint, alpha = 0.4f)
    },
)

/**
 * 内容卡片：MD3 Card + 玻璃表面 / 主题语义色 + 层次三件套，可点击（按压反馈由
 * pressScale 传入的 interactionSource 驱动）。参数名与旧 LiquidGlassSurface 一致，调用方零改动。
 *
 * 1.0.13 双包：glass flavor 主窗口（[LocalBackdrop] 非 null）走 [glassSurface]，
 * Card 容器色透明；表面 tint 沿用 [containerColor] 参数（悬浮窗告警态整体变红等
 * 场景在主窗口同样成立）。plain flavor / 悬浮窗窗口回落 chrome 路径。
 *
 * [containerColor] 可覆盖容器色：悬浮窗告警态整体变红等场景使用；
 * 不传则走主题 surfaceContainer（比背景亮一档，卡片才浮得出来）。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(DesignTokens.Radius.CARD.dp),
    containerColor: Color? = null,
    elevation: ElevationLevel = ElevationLevel.LEVEL1,
    onClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val glassBackdrop = if (BuildConfig.LIQUID_GLASS) LocalBackdrop.current else null
    val colors = CardDefaults.cardColors(
        containerColor = containerColor ?: MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    )
    val chrome = if (glassBackdrop != null) {
        Modifier.glassSurface(
            backdrop = glassBackdrop,
            shape = shape,
            tint = containerColor ?: MaterialTheme.colorScheme.surfaceContainer,
        )
    } else {
        Modifier.elevatedCardChrome(shape, elevation)
    }
    val cardColors = if (glassBackdrop != null) {
        CardDefaults.cardColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
        )
    } else {
        colors
    }
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier.then(chrome),
            enabled = true,
            shape = shape,
            colors = cardColors,
            interactionSource = interactionSource,
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                content()
            }
        }
    } else {
        Card(
            modifier = modifier.then(chrome),
            shape = shape,
            colors = cardColors,
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                content()
            }
        }
    }
}
