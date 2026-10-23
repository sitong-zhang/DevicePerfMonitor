package com.devperf.monitor.ui.kit

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.devperf.monitor.ui.theme.LocalAppMotion

/** 列表项依次入场的错峰步长；设上限避免长列表尾部等太久。 */
private const val STAGGER_MS = 45
private const val STAGGER_MAX_MS = 240

/**
 * 入场动画规格（淡入用）。曲线和时长取自 [LocalAppMotion]（真源 design-tokens.json，
 * 双端共用同一份），页面里不该自己写死时长。
 */
@Composable
fun <T> enterSpec(delayMs: Int = 0): FiniteAnimationSpec<T> {
    val motion = LocalAppMotion.current
    return tween(
        durationMillis = motion.enterDurationMs,
        delayMillis = delayMs,
        easing = motion.enter,
    )
}

/**
 * 按压缩放反馈：按下缩到 [pressedScale]，松手弹回。
 *
 * 走 graphicsLayer 而不是改 padding/尺寸，避免触发重新测量——列表里连续点按不掉帧。
 *
 * 注意：这里只做视觉缩放，**不消费点击事件**。调用方要把同一个 [interactionSource]
 * 传给实际响应点击的组件（[LiquidGlassSurface] 的 interactionSource 参数），
 * 否则按压状态收集不到。
 */
@Composable
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.965f,
): Modifier {
    val motion = LocalAppMotion.current
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = tween(motion.emphasizedDurationMs, easing = motion.emphasized),
        label = "pressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * 列表项入场：淡入 + 轻微上滑，按索引错峰。
 *
 * 不用 AnimatedVisibility——它在 LazyColumn 里会改变 item 占用高度导致列表跳动。
 * 这里只动 alpha 和 translationY，item 的测量尺寸始终不变。
 */
@Composable
fun EnterStagger(
    index: Int,
    modifier: Modifier = Modifier,
    travel: Dp = 24.dp,
    content: @Composable () -> Unit,
) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    val travelPx = with(LocalDensity.current) { travel.toPx() }
    val progress by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = enterSpec(
            delayMs = (index * STAGGER_MS).coerceAtMost(STAGGER_MAX_MS),
        ),
        label = "enterStagger",
    )

    Box(
        modifier = modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * travelPx
        },
    ) {
        content()
    }
}

/**
 * 骨架屏微光扫过：首帧数据还没到的时候用它占位，
 * 避免界面先闪一下"空白"再突然跳成有内容。
 */
@Composable
fun Modifier.shimmer(base: Color): Modifier {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1250, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerProgress",
    )
    return this.drawWithContent {
        drawContent()
        val w = size.width
        if (w <= 0f) return@drawWithContent
        // 高光带宽度约为控件的一半，从左扫到右
        val bandWidth = w * 0.5f
        val start = -bandWidth + progress * (w + bandWidth)
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    base.copy(alpha = 0.14f),
                    Color.Transparent,
                ),
                start = Offset(start, 0f),
                end = Offset(start + bandWidth, size.height),
            ),
            size = Size(size.width, size.height),
        )
    }
}

/**
 * 触觉反馈。
 *
 * 低端机可能没有振动马达或系统禁掉了触感，内部静默失败，不影响交互继续走下去。
 */
@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

class Haptics(private val view: View) {
    fun tick() = perform(HapticFeedbackConstants.KEYBOARD_TAP)
    fun select() = perform(HapticFeedbackConstants.CONTEXT_CLICK)
    fun confirm() = perform(HapticFeedbackConstants.CONFIRM)
    fun reject() = perform(HapticFeedbackConstants.REJECT)

    private fun perform(code: Int) {
        runCatching { view.performHapticFeedback(code) }
    }
}
