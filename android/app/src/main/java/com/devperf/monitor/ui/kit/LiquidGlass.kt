package com.devperf.monitor.ui.kit

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.devperf.monitor.ui.theme.DesignTokens
import com.devperf.monitor.ui.theme.LocalIsDarkTheme
import com.devperf.monitor.ui.theme.LocalTokenColors

/**
 * 液态玻璃材质体系（Android 端）。
 *
 * 两条渲染路径：
 *
 * A. 真实玻璃路径（主应用内）：
 *    使用 Kyant0/AndroidLiquidGlass（Apache-2.0）的 [drawBackdrop]：
 *    - 根容器通过 [layerBackdrop] 把背景内容录制成 GraphicsLayer；
 *    - 玻璃表面对该图层做真实 RuntimeShader 模糊（vibrancy + blur）；
 *    - highlight / shadow / innerShadow 由库绘制。
 *
 * B. 降级路径（系统悬浮窗场景）：
 *    悬浮窗无法对「窗口之外的其他应用画面」采样（WindowManager 不提供背后像素），
 *    因此无法真实模糊。降级为：真实半透明底（窗口本身透明，可透视后方内容）
 *    + 自绘顶亮底暗高光渐变 + 1px 渐变描边 + 内阴影 + 程序化噪点。
 *    这是「真实绘制的玻璃拟态」，不是假模糊——README「降级说明」中如实记录。
 */

/** 当前作用域内的 Backdrop 提供者（由 [LiquidGlassScaffold] 提供；悬浮窗中为 null → 走降级路径）。 */
val LocalBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/**
 * 是否支持真实液态玻璃。
 *
 * backdrop 库的模糊走 `android.graphics.RuntimeShader`（AGSL），这个类是 **API 33** 才有的。
 * 在 API 32 及以下调用它会在绘制期直接抛 NoClassDefFoundError —— 而且发生在合成/绘制线程，
 * 表现就是"打开即闪退"。所以低于 33 一律走降级路径 [drawDegradedGlass]，
 * 视觉上是真实半透明 + 自绘高光/描边/内阴影/噪点，不是假模糊。
 */
val supportsLiquidGlass: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/** 创建并记住一个 [LayerBackdrop]；不支持实时模糊时返回 null（走降级路径）。 */
@Composable
fun rememberLiquidGlassBackdrop(): LayerBackdrop? =
    if (supportsLiquidGlass) rememberLayerBackdrop() else null

/**
 * 液态玻璃脚手架：录制背景内容，并向子树提供 [LocalBackdrop]。
 *
 * @param background 背景内容（被玻璃采样）。一般为渐变 + 氛围图形。
 * @param modifier 根修饰符
 * @param content 前景内容（玻璃卡片、控件）
 */
@Composable
fun LiquidGlassScaffold(
    modifier: Modifier = Modifier,
    background: @Composable BoxScope.() -> Unit = { DefaultGlassBackground() },
    content: @Composable BoxScope.(backdrop: LayerBackdrop?) -> Unit,
) {
    val backdrop = rememberLiquidGlassBackdrop()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LocalTokenColors.current.background)
            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
    ) {
        background()
        CompositionLocalProvider(LocalBackdrop provides backdrop) {
            content(backdrop)
        }
    }
}

/** 默认背景：Catppuccin 语义色渐变 + 两团氛围光斑（供玻璃采样）。 */
@Composable
fun DefaultGlassBackground(modifier: Modifier = Modifier) {
    val colors = LocalTokenColors.current
    val isDark = LocalIsDarkTheme.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    0f to colors.background,
                    0.55f to colors.surface,
                    1f to colors.background,
                )
            )
    ) {
        // 氛围光斑：增强玻璃折射后的色彩层次（真实绘制，非贴图）
        Box(
            modifier = Modifier
                .fillMaxSize(0.9f)
                .background(
                    Brush.radialGradient(
                        listOf(
                            colors.accent.copy(alpha = if (isDark) 0.16f else 0.22f),
                            Color.Transparent,
                        )
                    )
                )
                .align(Alignment.TopStart)
        )
        Box(
            modifier = Modifier
                .fillMaxSize(0.75f)
                .background(
                    Brush.radialGradient(
                        listOf(
                            colors.accentSecondary.copy(alpha = if (isDark) 0.12f else 0.18f),
                            Color.Transparent,
                        )
                    )
                )
                .align(Alignment.BottomEnd)
        )
    }
}

// ---------------------------------------------------------------------------
// 玻璃表面
// ---------------------------------------------------------------------------

/** 玻璃表面参数预设（对应 design-tokens.json 的 glass 段）。 */
@Immutable
data class GlassStyle(
    val blurRadiusDp: Float = DesignTokens.Blur.GLASS,
    val surfaceAlpha: Float = DesignTokens.Glass.BACKGROUND_ALPHA.MIN,
    val highlightAlphaTop: Float = DesignTokens.Glass.HIGHLIGHT_ALPHA_TOP,
    val highlightAlphaBottom: Float = DesignTokens.Glass.HIGHLIGHT_ALPHA_BOTTOM,
    val borderAlpha: Float = DesignTokens.Glass.BORDER_ALPHA,
    val innerShadowAlpha: Float = DesignTokens.Glass.INNER_SHADOW_ALPHA,
    val outerShadowAlpha: Float = DesignTokens.Glass.OUTER_SHADOW_ALPHA,
)

/**
 * 液态玻璃表面：卡片 / 面板 / 控件容器。
 *
 * @param backdrop Backdrop 提供者。null 时走降级路径（真实半透明 + 自绘高光，无模糊）。
 * @param shape 圆角（设计令牌 card 24 / small 16 / large 28 / pill 999）
 * @param style 玻璃参数（模糊半径、透明度、高光、描边、阴影）
 * @param onClick 非空时整块可点击
 * @param contentAlignment 内容对齐
 */
@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = LocalBackdrop.current,
    shape: Shape = RoundedCornerShape(DesignTokens.Radius.CARD.dp),
    style: GlassStyle = GlassStyle(),
    tint: Color = LocalTokenColors.current.surface,
    onClick: (() -> Unit)? = null,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    // 噪点笔刷在组合期创建一次（128×128 确定性伪随机），绘制期直接使用
    val noiseBrush = rememberNoiseBrush()
    var base = modifier.clip(shape)
    if (onClick != null) {
        base = base.clickable(onClick = onClick)
    }

    val drawModifier = if (backdrop != null) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(with(this) { style.blurRadiusDp.dp.toPx() })
            },
            highlight = {
                Highlight(
                    width = DesignTokens.Glass.BORDER_WIDTH.dp,
                    blurRadius = (DesignTokens.Glass.BORDER_WIDTH / 2f).dp,
                    alpha = style.highlightAlphaTop,
                )
            },
            shadow = {
                Shadow(
                    radius = DesignTokens.Glass.OUTER_SHADOW_RADIUS.dp,
                    offset = DpOffset(0.dp, DesignTokens.Glass.OUTER_SHADOW_OFFSET_Y.dp),
                    color = Color.Black.copy(alpha = style.outerShadowAlpha),
                )
            },
            innerShadow = {
                InnerShadow(
                    radius = DesignTokens.Blur.GLASS.dp,
                    offset = DpOffset(0.dp, DesignTokens.Blur.GLASS.dp),
                    color = Color.Black.copy(alpha = style.innerShadowAlpha),
                )
            },
            onDrawSurface = {
                drawRect(tint.copy(alpha = style.surfaceAlpha))
            },
        )
    } else {
        Modifier.drawWithContent {
            drawDegradedGlass(shape, tint, style, noiseBrush)
            drawContent()
        }
    }

    Box(
        modifier = base.then(drawModifier),
        contentAlignment = contentAlignment,
    ) {
        content()
    }
}

// ---------------------------------------------------------------------------
// 降级路径绘制（无 Backdrop 采样源时）
// ---------------------------------------------------------------------------

/**
 * 降级玻璃绘制：
 * 1) 半透明底（真实半透明，悬浮窗可透视后方内容）；
 * 2) 顶亮底暗高光渐变（自绘，非模糊）；
 * 3) 1px 渐变描边（顶部高、底部低）；
 * 4) 顶部内缘压暗（内阴影拟态）；
 * 5) 程序化噪点（细腻颗粒感）。
 *
 * 说明：拿不到窗口背后的像素，因此这里不调用任何模糊 API——不制造假模糊。
 */
private fun DrawScope.drawDegradedGlass(
    shape: Shape,
    tint: Color,
    style: GlassStyle,
    noiseBrush: Brush,
) {
    // 1) 半透明底
    drawRect(color = tint.copy(alpha = style.surfaceAlpha))

    // 2) 高光渐变：顶部亮 → 中部过渡 → 底部暗
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = style.highlightAlphaTop),
            0.45f to Color.White.copy(alpha = style.highlightAlphaTop * 0.25f),
            1f to Color.Black.copy(alpha = style.innerShadowAlpha),
        )
    )

    // 3) 1px 渐变描边 + 4) 内阴影：裁剪在形状内绘制
    val borderWidth = DesignTokens.Glass.BORDER_WIDTH.dp.toPx()
    val outline = shape.createOutline(
        Size(size.width - borderWidth, size.height - borderWidth),
        layoutDirection,
        this,
    )
    val borderBrush = Brush.verticalGradient(
        0f to Color.White.copy(alpha = style.highlightAlphaTop),
        0.5f to Color.White.copy(alpha = style.borderAlpha),
        1f to Color.White.copy(alpha = style.highlightAlphaBottom),
    )
    val outlinePath = Path().apply {
        when (outline) {
            is Outline.Rectangle -> addRect(outline.rect)
            is Outline.Rounded -> addRoundRect(outline.roundRect)
            is Outline.Generic -> addPath(outline.path)
        }
    }
    clipRect {
        drawPath(
            path = outlinePath,
            brush = borderBrush,
            style = Stroke(width = borderWidth),
        )
        // 内阴影：顶部内缘向下渐隐的暗边
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Black.copy(alpha = style.innerShadowAlpha * 1.6f),
                0.12f to Color.Transparent,
            )
        )
    }

    // 5) 噪点
    drawRect(brush = noiseBrush)
}

/**
 * 程序化噪点笔刷：128×128 灰度随机像素，TileMode.Repeated 平铺。
 * alpha 取 design-tokens 的 noiseAlpha（0.035），真实绘制。
 * 随机源为 xorshift32 确定性种子：每次组成结果一致，避免闪烁。
 */
@Composable
private fun rememberNoiseBrush(): Brush {
    val alpha = (DesignTokens.Glass.NOISE_ALPHA * 255f).toInt().coerceIn(0, 255)
    return remember(alpha) {
        val dim = 128
        val pixels = IntArray(dim * dim)
        var seed = 0x9E3779B9.toInt()
        for (i in pixels.indices) {
            seed = seed xor (seed shl 13)
            seed = seed xor (seed shr 17)
            seed = seed xor (seed shl 5)
            val v = (seed ushr 24) and 0xFF
            pixels[i] = (alpha shl 24) or (v shl 16) or (v shl 8) or v
        }
        val bitmap = ImageBitmap(dim, dim)
        val androidBitmap = bitmap.asAndroidBitmap()
        androidBitmap.setPixels(pixels, 0, dim, 0, 0, dim, dim)
        ShaderBrush(ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated))
    }
}
