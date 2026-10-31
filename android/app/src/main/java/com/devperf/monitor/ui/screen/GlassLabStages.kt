package com.devperf.monitor.ui.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.draggable2D
import androidx.compose.foundation.gestures.rememberDraggable2DState
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import com.devperf.monitor.R
import com.devperf.monitor.ui.kit.LocalBackdrop
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.catalog.components.LiquidButton
import com.kyant.backdrop.catalog.components.LiquidSlider
import com.kyant.backdrop.catalog.utils.ProgressConverter
import com.kyant.backdrop.catalog.utils.rememberSdfShader
import com.kyant.backdrop.catalog.utils.rememberUISensor
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.runtimeShaderEffect
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 玻璃实验室 · 上游 catalog 五大演示体验移植（1.0.51）。
 *
 * 此前实验室只覆盖了库级 API（effect / highlight / backdrop 工具），
 * 上游 demo App 里真正"体验级"的五个页面没有进来——它们各自代表一种
 * 完整交互范式，全部依赖此前未 vendor 的工具（UISensor / ProgressConverter /
 * SdfShader）或 Modifier 进阶参数（onDrawBackdrop / exportedBackdrop）：
 *
 * 1. 放大镜（Magnifier）：rememberCombinedBackdrop 叠三层背景 + onDrawBackdrop
 *    放大重采样 + lens 色散——「玻璃透镜看玻璃内容」；
 * 2. 控制中心（ControlCenter）：UISensor 重力传感器驱动高光角度 + 垂直拖拽
 *    ProgressConverter 进入/退出进度 + 整组玻璃块联动缩放位移；
 * 3. 锁屏时钟（LockScreen）：SdfShader（AGSL 有向距离场）沿 SDF 贴图折射；
 * 4. 渐进模糊（ProgressiveBlur）：runtimeShaderEffect AGSL alpha 蒙版，
 *    越往下越模糊的渐变玻璃；
 * 5. 游乐园（GlassPlayground）：detectTransformGestures 双指拖拽缩放旋转 +
 *    exportedBackdrop 玻璃嵌玻璃（面板内滑块采样面板自身）。
 */

private const val MAGNIFIER_TEXT =
    "液态玻璃的放大镜：把手指按住蓝色光标拖动，透镜会实时折射它后面的文字与背景。" +
        "实现上是三层 backdrop 叠加（窗口背景 + 内容层 + 光标层），再在 onDrawBackdrop " +
        "里把采样结果放大 1.5 倍重画。"

@Composable
private fun StageTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun StageText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

// ---------------------------------------------------------------------------
// 1. 放大镜（上游 MagnifierContent）
// ---------------------------------------------------------------------------

@Composable
internal fun LabMagnifier(backdrop: Backdrop) {
    val dark = MaterialTheme.colorScheme.surface.luminanceDark()
    val contentColor = if (dark) Color.White else Color.Black
    val accentColor = if (dark) Color(0xFF0091FF) else Color(0xFF0088FF)
    val backgroundColor = if (dark) Color(0xFF121212) else Color.White

    StageTitle("放大镜 · 拖动蓝色光标，透镜实时折射")
    val contentBackdrop = rememberLayerBackdrop()
    val cursorBackdrop = rememberLayerBackdrop()
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        Modifier
            .fillMaxWidth()
            .height(320.dp)
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp)),
    ) {
        Text(
            MAGNIFIER_TEXT,
            Modifier
                .layerBackdrop(contentBackdrop)
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(backgroundColor.copy(alpha = 0.9f))
                .padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
        )

        // 可拖动的文本光标：本身也被录进 cursorBackdrop，透镜里能看到放大的它
        Box(
            Modifier
                .graphicsLayer {
                    translationX = offset.x
                    translationY = offset.y
                }
                .draggable2D(rememberDraggable2DState { delta -> offset += delta })
                .layerBackdrop(cursorBackdrop)
                .background(accentColor, Capsule())
                .size(4.dp, 24.dp)
                .align(Alignment.TopStart),
        )

        // 透镜：三层 backdrop 合并采样，onDrawBackdrop 里放大 1.5 倍重画
        Box(
            Modifier
                .graphicsLayer {
                    translationX = offset.x
                    translationY = offset.y - 80.dp.toPx()
                }
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, contentBackdrop, cursorBackdrop),
                    shape = { Capsule() },
                    effects = {
                        lens(
                            8.dp.toPx(),
                            24.dp.toPx(),
                            depthEffect = true,
                            chromaticAberration = true,
                        )
                    },
                    innerShadow = { InnerShadow(radius = 16.dp) },
                    onDrawBackdrop = { drawBackdrop ->
                        withTransform(
                            {
                                scale(1.5f, 1.5f)
                                translate(top = with(this@drawBackdrop) { -80f.dp.toPx() })
                            },
                            drawBackdrop,
                        )
                    },
                )
                .size(128.dp, 96.dp)
                .align(Alignment.Center),
        )
    }
}

// ---------------------------------------------------------------------------
// 2. 控制中心（上游 ControlCenterContent：UISensor 重力高光 + 拖拽进度）
// ---------------------------------------------------------------------------

@Composable
internal fun LabControlCenter(backdrop: Backdrop) {
    val accentColor = if (MaterialTheme.colorScheme.surface.luminanceDark()) {
        Color(0xFF0091FF)
    } else {
        Color(0xFF0088FF)
    }
    val containerColor = Color.Black.copy(0.05f)
    val dimColor = Color.Black.copy(0.4f)

    val itemSpacing = 12.dp
    val itemSize = 64.dp
    val itemTwoSpanSize = itemSize * 2 + itemSpacing
    val itemShape = RoundedRectangle(itemSize / 2f)

    val animationScope = rememberCoroutineScope()
    val enterProgressAnimation = remember { Animatable(1f) }
    val safeEnterProgressAnimation = remember { Animatable(1f) }
    val progress by remember {
        derivedStateOf {
            val p = enterProgressAnimation.value
            when {
                p < 0f -> ProgressConverter.Default.convert(p)
                p <= 1f -> p
                else -> 1f + ProgressConverter.Default.convert(p - 1f)
            }
        }
    }
    val maxDragHeight = 1000f

    // 重力传感器：倾斜手机，高光跟着转（上游 glassHighlight 同款）
    val uiSensor = rememberUISensor()
    val glassHighlight = {
        Highlight(
            style = HighlightStyle.Default(
                angle = uiSensor.gravityAngle,
                falloff = 2f,
            ),
        )
    }
    val glassLayer: androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit = {
        val p = progress
        val safe = safeEnterProgressAnimation.value
        translationY = -48f.dp.toPx() * (1f - p)
        alpha = EaseIn.transform(safe)
        scaleX /= 1f + 0.1f * (p - 1f).fastCoerceAtLeast(0f)
        scaleY *= 1f + 0.1f * (p - 1f).fastCoerceAtLeast(0f)
    }
    val glassSurface: DrawScope.() -> Unit = { drawRect(containerColor) }
    val glassEffects: BackdropEffectScope.() -> Unit = {
        val safe = safeEnterProgressAnimation.value
        vibrancy()
        lens(24f.dp.toPx() * safe, 48f.dp.toPx() * safe, depthEffect = true)
    }

    StageTitle("上下拖动整组玻璃 · 倾斜手机看高光转向")
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .draggable(
                rememberDraggableState { delta ->
                    val target = enterProgressAnimation.value + delta / maxDragHeight
                    animationScope.launch {
                        launch { enterProgressAnimation.snapTo(target) }
                        launch { safeEnterProgressAnimation.snapTo(target.fastCoerceIn(0f, 1f)) }
                    }
                },
                Orientation.Vertical,
                onDragStopped = { velocity ->
                    val target = when {
                        velocity < 0f -> 0f
                        velocity > 0f -> 1f
                        else -> if (enterProgressAnimation.value < 0.5f) 0f else 1f
                    }
                    animationScope.launch {
                        launch {
                            enterProgressAnimation.animateTo(
                                target,
                                if (target > 0.5f) spring(0.5f, 300f, 0.5f / maxDragHeight)
                                else spring(1f, 300f, 0.01f),
                                velocity / maxDragHeight,
                            )
                        }
                        launch {
                            safeEnterProgressAnimation.animateTo(target, spring(1f, 300f, 0.01f))
                        }
                    }
                },
            )
            .drawWithContent {
                drawContent()
                drawRect(dimColor.copy(dimColor.alpha * safeEnterProgressAnimation.value))
            }
            .graphicsLayer {
                val safe = safeEnterProgressAnimation.value
                val blurRadius = 4f.dp.toPx() * safe
                if (blurRadius > 0f) renderEffect = BlurEffect(blurRadius, blurRadius)
            },
        verticalArrangement = Arrangement.spacedBy(itemSpacing),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(itemSpacing)) {
            GlassTile(itemTwoSpanSize, itemTwoSpanSize, { itemShape }, backdrop, glassEffects, glassHighlight, glassLayer, glassSurface)
            GlassTile(itemTwoSpanSize, itemTwoSpanSize, { itemShape }, backdrop, glassEffects, glassHighlight, glassLayer, glassSurface)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(itemSpacing)) {
            GlassTile(itemSize, itemTwoSpanSize, { itemShape }, backdrop, glassEffects, glassHighlight, glassLayer, glassSurface)
            GlassTile(itemSize, itemTwoSpanSize, { itemShape }, backdrop, glassEffects, glassHighlight, glassLayer, glassSurface)
            GlassTile(itemTwoSpanSize, itemSize, { itemShape }, backdrop, glassEffects, glassHighlight, glassLayer, glassSurface)
        }
        Spacer(
            Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val p = progress
                val height = itemSpacing.roundToPx() +
                    (24f.dp.toPx() * (p - 1f).fastCoerceAtLeast(0f)).fastRoundToInt()
                layout(constraints.minWidth, height) { placeable.place(0, 0) }
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(itemSpacing)) {
            GlassTile(itemTwoSpanSize, itemSize, { itemShape }, backdrop, glassEffects, glassHighlight, glassLayer, glassSurface)
            GlassTile(itemSize, itemSize, { itemShape }, backdrop, glassEffects, glassHighlight, glassLayer, glassSurface)
        }
    }
}

@Composable
private fun GlassTile(
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    shape: () -> androidx.compose.ui.graphics.Shape,
    backdrop: Backdrop,
    effects: BackdropEffectScope.() -> Unit,
    highlight: () -> Highlight?,
    layerBlock: androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit,
    surface: DrawScope.() -> Unit,
) {
    Box(
        Modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = shape,
                effects = effects,
                highlight = highlight,
                shadow = null,
                layerBlock = layerBlock,
                onDrawSurface = surface,
            )
            .size(width, height),
    )
}

// ---------------------------------------------------------------------------
// 3. 锁屏时钟 SDF（上游 LockScreenContent：SdfShader 沿距离场折射）
// ---------------------------------------------------------------------------

@Composable
internal fun LabLockScreen(backdrop: Backdrop) {
    val sdfShader = rememberSdfShader(ImageBitmap.imageResource(R.drawable.clock_sdf))
    var offset by remember { mutableStateOf(Offset.Zero) }

    StageTitle("SdfShader · SDF 时钟折射（可拖动）")
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(240.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .graphicsLayer {
                    translationX = offset.x
                    translationY = offset.y
                }
                .draggable2D(rememberDraggable2DState { delta -> offset += delta })
                .drawPlainBackdrop(
                    backdrop = backdrop,
                    shape = { com.kyant.shapes.Rectangle },
                    effects = {
                        colorControls(brightness = -0.1f, contrast = 0.75f, saturation = 1.5f)
                        blur(2f.dp.toPx())
                        with(sdfShader) { apply() }
                    },
                    onDrawBackdrop = { drawBackdrop ->
                        drawBackdrop()
                        drawRect(Color.White.copy(alpha = 0.25f))
                    },
                )
                .widthIn(max = 400.dp)
                .aspectRatio(sdfShader.width.toFloat() / sdfShader.height.toFloat())
                .fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------------------
// 4. 渐进模糊（上游 ProgressiveBlurContent：AGSL alpha 蒙版）
// ---------------------------------------------------------------------------

@Composable
internal fun LabProgressiveBlur(backdrop: Backdrop) {
    val dark = MaterialTheme.colorScheme.surface.luminanceDark()
    val contentColor = if (dark) Color.White else Color.Black
    val tintColor = if (dark) Color(0xFF808080) else Color.White

    StageTitle("渐进模糊 · 越往下越糊（AGSL alpha 蒙版）")
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .drawPlainBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(20.dp) },
                effects = {
                    blur(4f.dp.toPx())
                    runtimeShaderEffect(
                        "AlphaMask",
                        """
uniform shader content;

uniform float2 size;
layout(color) uniform half4 tint;
uniform float tintIntensity;

half4 main(float2 coord) {
    float blurAlpha = smoothstep(size.y, size.y * 0.5, coord.y);
    float tintAlpha = smoothstep(size.y, size.y * 0.5, coord.y);
    return mix(content.eval(coord) * blurAlpha, tint * tintAlpha, tintIntensity);
}""",
                        "content",
                    ) {
                        setFloatUniform("size", size.width, size.height)
                        setColorUniform("tint", tintColor)
                        setFloatUniform("tintIntensity", 0.8f)
                    }
                },
            )
            .height(128.dp)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "alpha-masked progressive blur",
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------
// 5. 游乐园（上游 GlassPlaygroundContent：手势变换 + 参数滑块）
// ---------------------------------------------------------------------------

@Composable
internal fun LabPlayground(backdrop: Backdrop) {
    val animationScope = rememberCoroutineScope()
    val offsetAnimation = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val zoomAnimation = remember { Animatable(1f) }
    val rotationAnimation = remember { Animatable(0f) }

    var cornerRadiusFrac by remember { mutableFloatStateOf(0.5f) }
    var blurRadiusDp by remember { mutableFloatStateOf(0f) }
    var refractionHeightFrac by remember { mutableFloatStateOf(0.2f) }
    var refractionAmountFrac by remember { mutableFloatStateOf(0.2f) }
    var chromaticAberration by remember { mutableFloatStateOf(0f) }

    StageTitle("游乐园 · 双指拖拽/缩放/旋转，滑块实时调参")
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(280.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedRectangle(256.dp / 2f * cornerRadiusFrac) },
                    effects = {
                        val minDimension = size.minDimension
                        vibrancy()
                        blur(blurRadiusDp.dp.toPx())
                        lens(
                            refractionHeight = refractionHeightFrac * minDimension * 0.5f,
                            refractionAmount = refractionAmountFrac * minDimension,
                            depthEffect = true,
                            chromaticAberration = chromaticAberration > 0f,
                        )
                    },
                    highlight = { Highlight.Plain },
                    layerBlock = {
                        val offset = offsetAnimation.value
                        translationX = offset.x
                        translationY = offset.y
                        scaleX = zoomAnimation.value
                        scaleY = zoomAnimation.value
                        rotationZ = rotationAnimation.value
                    },
                )
                .pointerInput(animationScope) {
                    fun Offset.rotateBy(angle: Float): Offset {
                        val rad = angle * (PI / 180)
                        return Offset(
                            (x * cos(rad) - y * sin(rad)).toFloat(),
                            (x * sin(rad) + y * cos(rad)).toFloat(),
                        )
                    }

                    detectTransformGestures { _, pan, gestureZoom, gestureRotate ->
                        val zoom = zoomAnimation.value * gestureZoom
                        val rotation = rotationAnimation.value + gestureRotate
                        val offset = offsetAnimation.value + pan.rotateBy(rotation) * zoom
                        animationScope.launch {
                            offsetAnimation.snapTo(offset)
                            zoomAnimation.snapTo(zoom)
                            rotationAnimation.snapTo(rotation)
                        }
                    }
                }
                .size(220.dp),
        )
    }

    // 参数面板：exportedBackdrop 把面板自身录成 backdrop，
    // 面板内的 LiquidSlider 采样面板玻璃（上游「玻璃嵌玻璃」同款）
    val sheetBackdrop = rememberLayerBackdrop()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedRectangle(28f.dp) },
                effects = {
                    vibrancy()
                    blur(4f.dp.toPx())
                    lens(16f.dp.toPx(), 32f.dp.toPx())
                },
                highlight = { Highlight.Plain },
                exportedBackdrop = sheetBackdrop,
                onDrawSurface = { drawRect(Color.White.copy(alpha = 0.5f)) },
            )
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PlaygroundSlider("圆角", cornerRadiusFrac, { cornerRadiusFrac = it }, 0f..1f, 0.001f, sheetBackdrop)
        PlaygroundSlider("模糊半径", blurRadiusDp, { blurRadiusDp = it }, 0f..32f, 0.01f, sheetBackdrop)
        PlaygroundSlider("折射高度", refractionHeightFrac, { refractionHeightFrac = it }, 0f..1f, 0.001f, sheetBackdrop)
        PlaygroundSlider("折射量", refractionAmountFrac, { refractionAmountFrac = it }, 0f..1f, 0.001f, sheetBackdrop)
        PlaygroundSlider("色散", chromaticAberration, { chromaticAberration = it }, 0f..1f, 0.001f, sheetBackdrop)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            LiquidButton(
                onClick = {
                    animationScope.launch {
                        launch { offsetAnimation.animateTo(Offset.Zero) }
                        launch { zoomAnimation.animateTo(1f) }
                        launch { rotationAnimation.animateTo(0f) }
                    }
                    cornerRadiusFrac = 0.5f
                    blurRadiusDp = 0f
                    refractionHeightFrac = 0.2f
                    refractionAmountFrac = 0.2f
                    chromaticAberration = 0f
                },
                backdrop = backdrop,
                tint = Color(0xFF007AFF),
            ) {
                Text("复位", color = Color.White, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun PlaygroundSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    threshold: Float,
    backdrop: Backdrop,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StageText(label)
        LiquidSlider(
            value = { value },
            onValueChange = onValueChange,
            valueRange = range,
            visibilityThreshold = threshold,
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 深色判定小工具：主题 surface 亮度 < 0.5 视为深色。 */
@Composable
private fun Color.luminanceDark(): Boolean = luminance() < 0.5f
