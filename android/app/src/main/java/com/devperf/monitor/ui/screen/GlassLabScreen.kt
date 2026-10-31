package com.devperf.monitor.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorMatrixColorFilter
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devperf.monitor.ui.kit.LocalBackdrop
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.catalog.components.LiquidBottomTab
import com.kyant.backdrop.catalog.components.LiquidBottomTabs
import com.kyant.backdrop.catalog.components.LiquidButton
import com.kyant.backdrop.catalog.components.LiquidSlider
import com.kyant.backdrop.catalog.components.LiquidToggle
import com.kyant.backdrop.catalog.utils.DampedDragAnimation
import com.kyant.backdrop.catalog.utils.InteractiveHighlight
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.colorFilter
import com.kyant.backdrop.effects.effect
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.opacity
import com.kyant.backdrop.effects.runtimeShaderEffect
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.isRuntimeShaderSupported
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow

/**
 * 玻璃实验室（1.0.49）：把上游 Kyant0/AndroidLiquidGlass 的全部库级能力集中演示一遍。
 *
 * 目的不是做样子，而是把此前用户端没用上的 API 真正调起来验证：
 *  - 4 个成品组件：LiquidButton / LiquidToggle / LiquidSlider / LiquidBottomTabs(+Tab)
 *  - Modifier：drawBackdrop / drawPlainBackdrop
 *  - 8 个 effect：blur / lens / vibrancy / colorFilter / opacity / colorControls /
 *                 effect(RenderEffect) / runtimeShaderEffect(AGSL)
 *  - 高光三样式：Highlight.Plain / Default / Ambient
 *  - 阴影：Shadow / InnerShadow
 *  - Backdrop 四种创建：rememberCanvasBackdrop / rememberCombinedBackdrop /
 *                       emptyBackdrop（+ 全局 rememberLayerBackdrop）
 *  - 工具：InteractiveHighlight（按压跟随高光）/ DampedDragAnimation（阻尼拖拽）
 */
@Composable
fun GlassLabScreen(onBack: () -> Unit) {
    val backdrop = LocalBackdrop.current
    if (backdrop == null) {
        // 理论上不会走到（本页只在主窗口打开），兜底给个纯文本说明。
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("当前窗口不支持液态玻璃", color = MaterialTheme.colorScheme.onSurface)
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = 24.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
            }
            Text(
                "玻璃实验室",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        LabCaption("成品组件 · catalog/components")
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LiquidButton(
                onClick = {},
                backdrop = backdrop,
                modifier = Modifier.weight(1f),
            ) {
                LabText("LiquidButton")
            }
            LiquidButton(
                onClick = {},
                backdrop = backdrop,
                modifier = Modifier.weight(1f),
                tint = Color(0xFF007AFF),
            ) {
                LabText("着色")
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LabText("LiquidToggle")
            Spacer(Modifier.weight(1f))
            var checked by remember { mutableStateOf(true) }
            LiquidToggle(
                selected = { checked },
                onSelect = { checked = it },
                backdrop = backdrop,
            )
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.padding(horizontal = 16.dp)) {
            var slider by remember { mutableFloatStateOf(0.55f) }
            LiquidSlider(
                value = { slider },
                onValueChange = { slider = it },
                valueRange = 0f..1f,
                visibilityThreshold = 0.001f,
                backdrop = backdrop,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(14.dp))
        var tab by remember { mutableIntStateOf(0) }
        LiquidBottomTabs(
            selectedTabIndex = { tab },
            onTabSelected = { tab = it },
            backdrop = backdrop,
            tabsCount = 3,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            listOf("One", "Two", "Three").forEachIndexed { index, label ->
                LiquidBottomTab(onClick = { tab = index }) {
                    LabText(label)
                }
            }
        }
        LabGap()

        LabCaption("Modifier · drawBackdrop 的 8 种 effect")
        GlassPanel(backdrop, "vibrancy + blur + lens") {
            vibrancy()
            blur(12f.dp.toPx())
            lens(8f.dp.toPx(), 16f.dp.toPx())
        }
        GlassPanel(backdrop, "lens(depthEffect = true)") {
            vibrancy()
            lens(16f.dp.toPx(), 32f.dp.toPx(), depthEffect = true)
        }
        GlassPanel(backdrop, "colorControls(saturation 1.6)") {
            colorControls(saturation = 1.6f)
        }
        GlassPanel(backdrop, "colorFilter(去饱和 0)") {
            val matrix = ColorMatrix().apply { setToSaturation(0f) }
            colorFilter(ColorMatrixColorFilter(matrix))
        }
        GlassPanel(backdrop, "opacity(0.55)") { opacity(0.55f) }
        GlassPanel(backdrop, "effect(RenderEffect 高斯)") {
            effect(
                android.graphics.RenderEffect
                    .createBlurEffect(18f, 18f, android.graphics.Shader.TileMode.CLAMP)
                    .asComposeRenderEffect(),
            )
        }
        GlassPanel(backdrop, "runtimeShaderEffect(AGSL)") {
            if (isRuntimeShaderSupported()) {
                runtimeShaderEffect("lab_passthrough", PASSTHROUGH_AGSL, "content") {}
            }
        }
        LabGap()

        LabCaption("Highlight 高光三样式")
        GlassPanel(backdrop, "Plain", highlight = { Highlight.Plain }) { vibrancy(); blur(10f.dp.toPx()) }
        GlassPanel(backdrop, "Default", highlight = { Highlight.Default }) { vibrancy(); blur(10f.dp.toPx()) }
        GlassPanel(backdrop, "Ambient", highlight = { Highlight.Ambient }) { vibrancy(); blur(10f.dp.toPx()) }
        LabGap()

        LabCaption("Shadow 外阴影 / InnerShadow 内阴影")
        GlassPanel(backdrop, "Shadow.Default", shadow = { Shadow.Default }) { vibrancy(); blur(10f.dp.toPx()) }
        GlassPanel(
            backdrop,
            "InnerShadow.Default",
            innerShadow = { InnerShadow.Default },
        ) { vibrancy(); blur(10f.dp.toPx()) }
        LabGap()

        LabCaption("Modifier · drawPlainBackdrop（无液态折射）")
        // drawPlainBackdrop：同一套 effects 但走普通面板绘制路径（无 lens 形变）。
        Box(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal = 16.dp)
                .drawPlainBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(16.dp) },
                    effects = {
                        vibrancy()
                        blur(14f.dp.toPx())
                    },
                    onDrawSurface = { drawRect(Color.White.copy(alpha = 0.14f)) },
                ),
            contentAlignment = Alignment.Center,
        ) { LabText("plain backdrop") }
        LabGap()

        LabCaption("Backdrop 创建 API")
        // rememberCanvasBackdrop：自己画一层渐变当参考背景（不依赖 layout 录制）。
        val canvasBackdrop = rememberCanvasBackdrop {
            drawRect(
                brush = androidx.compose.ui.graphics.Brush.linearGradient(
                    listOf(Color(0xFFE8ECF3), Color(0xFFC9D6EA)),
                ),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal = 16.dp)
                .drawBackdrop(
                    backdrop = canvasBackdrop,
                    shape = { RoundedCornerShape(16.dp) },
                    effects = { vibrancy(); blur(10f.dp.toPx()) },
                    onDrawSurface = { drawRect(Color.White.copy(alpha = 0.1f)) },
                ),
            contentAlignment = Alignment.Center,
        ) { LabText("rememberCanvasBackdrop") }
        Spacer(Modifier.height(10.dp))
        // rememberCombinedBackdrop：把画布背景与窗口层背景叠起来采样。
        val combined = rememberCombinedBackdrop(canvasBackdrop, backdrop)
        Box(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal = 16.dp)
                .drawBackdrop(
                    backdrop = combined,
                    shape = { RoundedCornerShape(16.dp) },
                    effects = { vibrancy(); blur(10f.dp.toPx()) },
                    onDrawSurface = { drawRect(Color.White.copy(alpha = 0.1f)) },
                ),
            contentAlignment = Alignment.Center,
        ) { LabText("rememberCombinedBackdrop") }
        Spacer(Modifier.height(10.dp))
        // emptyBackdrop：兜底实现，效果应退化为「只有表面色、没有采样」。
        Box(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal = 16.dp)
                .drawBackdrop(
                    backdrop = emptyBackdrop(),
                    shape = { RoundedCornerShape(16.dp) },
                    effects = { vibrancy(); blur(10f.dp.toPx()) },
                    onDrawSurface = { drawRect(Color.White.copy(alpha = 0.1f)) },
                ),
            contentAlignment = Alignment.Center,
        ) { LabText("emptyBackdrop") }
        LabGap()

        LabCaption("工具 · InteractiveHighlight（按压跟随高光）")
        InteractiveTile(backdrop)
        LabGap()

        LabCaption("工具 · DampedDragAnimation（阻尼拖拽）")
        DampedTile(backdrop)
        LabGap()

        Text(
            "上游 Block / Ripple / SdfShader / UISensor 属于其 demo App 自带的 KMP 演示脚手架" +
                "（非可复用库），未 vendor；其能力已由上面的库级组件与工具等价覆盖。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

private const val PASSTHROUGH_AGSL = """
uniform shader content;
half4 main(float2 coord) {
    return content.eval(coord);
}
"""

@Composable
private fun LabText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun LabCaption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun LabGap() {
    Spacer(Modifier.height(18.dp))
}

/**
 * drawBackdrop 玻璃面板。effects 故意放在最后一个参数——Kotlin 不允许尾随 lambda
 * 之后再跟具名参数，把 effects 放最后才能写成 `GlassPanel(...) { ... }`。
 */
@Composable
private fun GlassPanel(
    backdrop: Backdrop,
    label: String,
    highlight: (() -> Highlight?)? = null,
    shadow: (() -> Shadow?)? = null,
    innerShadow: (() -> InnerShadow?)? = null,
    effects: BackdropEffectScope.() -> Unit = {
        vibrancy()
        blur(10f.dp.toPx())
        lens(8f.dp.toPx(), 14f.dp.toPx())
    },
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(76.dp)
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(16.dp) },
                effects = effects,
                highlight = highlight,
                shadow = shadow,
                innerShadow = innerShadow,
                onDrawSurface = { drawRect(Color.White.copy(alpha = 0.12f)) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        LabText(label)
    }
}

/** InteractiveHighlight：按住在表面拖动会浮现跟随手指的高光。 */
@Composable
private fun InteractiveTile(backdrop: Backdrop) {
    val scope = rememberCoroutineScope()
    val highlight = remember(scope) { InteractiveHighlight(animationScope = scope) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(88.dp)
            .padding(horizontal = 16.dp)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(16.dp) },
                effects = {
                    vibrancy()
                    blur(10f.dp.toPx())
                    lens(8f.dp.toPx(), 16f.dp.toPx())
                },
                onDrawSurface = { drawRect(Color.White.copy(alpha = 0.12f)) },
            )
            .then(highlight.modifier)
            .then(highlight.gestureModifier),
        contentAlignment = Alignment.Center,
    ) {
        LabText("按住并拖动")
    }
}

/** DampedDragAnimation：拖拽时玻璃块带弹簧缩放与阻尼回弹（上游 LiquidButton 同款物理）。 */
@Composable
private fun DampedTile(backdrop: Backdrop) {
    val scope = rememberCoroutineScope()
    var value by remember { mutableFloatStateOf(0.5f) }
    val damped = remember(scope) {
        DampedDragAnimation(
            animationScope = scope,
            initialValue = value,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.06f,
            onDragStarted = {},
            onDragStopped = {
                val target = targetValue.coerceIn(0f, 1f)
                value = target
                animateToValue(target)
            },
            onDrag = { _, drag -> updateValue(value + drag.x / 240f) },
        )
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(88.dp)
            .padding(horizontal = 16.dp)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(16.dp) },
                effects = {
                    vibrancy()
                    blur(10f.dp.toPx())
                    lens(8f.dp.toPx(), 16f.dp.toPx())
                },
                layerBlock = {
                    scaleX = damped.scaleX
                    scaleY = damped.scaleY
                },
                onDrawSurface = { drawRect(Color.White.copy(alpha = 0.12f)) },
            )
            .then(damped.modifier),
        contentAlignment = Alignment.Center,
    ) {
        LabText("左右拖我")
    }
}
