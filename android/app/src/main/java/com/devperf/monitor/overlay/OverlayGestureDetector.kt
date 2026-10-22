package com.devperf.monitor.overlay

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.devperf.monitor.ui.theme.DesignTokens
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 悬浮窗手势检测器。
 *
 * 手势优先级（需求硬性规定，从上到下）：
 * 1. 拖动：长按（> longPressTimeout）且位移超过 touchSlop 后进入拖动；
 * 2. 缩放：双指捏合（拖动未开始时才生效，避免与拖动冲突）；
 * 3. 单击 / 双击：快速点击（无位移、短时长）。双击判定窗口 250ms
 *    （design-tokens gesture.doubleTapWindowMs）——第一次点击后延迟 250ms
 *    再执行单击动作，窗口内第二次落下则取消单击并触发双击；
 * 4. 贴边收窄：不是手势，而是窗口贴边后的自动形态（由控制器决定），
 *    因此永远让位给上述手势。
 *
 * 说明：手势在 Compose 层完成，窗口位置/尺寸由 [OverlayController] 更新。
 */
class OverlayGestureCallbacks(
    val onDragStart: () -> Unit,
    val onDrag: (Offset) -> Unit,
    val onDragEnd: () -> Unit,
    val onScale: (Float) -> Unit,
    val onSingleTap: () -> Unit,
    val onDoubleTap: () -> Unit,
    /** 当前触点本地坐标（供垃圾桶命中判定）。 */
    val onTouchLocal: (Offset) -> Unit,
)

@Composable
fun Modifier.overlayGestures(callbacks: OverlayGestureCallbacks): Modifier {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val doubleTapWindowMs = DesignTokens.Gesture.DOUBLE_TAP_WINDOW_MS
    val slopPx = with(density) { DesignTokens.Gesture.TOUCH_SLOP_DP.dp.toPx() }
    // 跨手势迭代保留的单击状态（双击判定窗口内共享）
    val tapState = remember { TapState() }

    return this.pointerInput(callbacks, doubleTapWindowMs, slopPx) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            val downAt = SystemClock.uptimeMillis()
            var dragging = false
            var scaling = false
            var pinchLastDist = 0f
            var totalDrag = Offset.Zero
            var lastTouch = down.position

            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }

                // ---- 优先级 2：双指缩放（拖动未开始时） ----
                if (pressed.size >= 2 && !dragging) {
                    if (!scaling) {
                        scaling = true
                        pinchLastDist = 0f
                    }
                    val dist = (pressed[0].position - pressed[1].position).getDistance()
                    if (pinchLastDist > 0f && dist > 0f) {
                        val factor = dist / pinchLastDist
                        callbacks.onScale(factor)
                        lastTouch = pressed[0].position
                    }
                    pinchLastDist = dist
                    pressed.forEach { it.consume() }
                    continue
                }
                if (scaling) {
                    if (pressed.size < 2) {
                        scaling = false
                        pinchLastDist = 0f
                    }
                    continue
                }

                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed) break // 主手指抬起

                if (dragging) {
                    val delta = change.position - change.previousPosition
                    if (delta != Offset.Zero) {
                        callbacks.onDrag(delta)
                        change.consume()
                    }
                } else {
                    totalDrag += change.position - change.previousPosition
                    val held = SystemClock.uptimeMillis() - downAt
                    // ---- 优先级 1：长按 + 位移 → 拖动 ----
                    if (held >= LONG_PRESS_MS && totalDrag.getDistance() > slopPx) {
                        dragging = true
                        callbacks.onDragStart()
                        change.consume()
                    }
                }
                lastTouch = change.position
                callbacks.onTouchLocal(lastTouch)
            }

            when {
                scaling -> Unit // 缩放结束
                dragging -> callbacks.onDragEnd()
                else -> {
                    val held = SystemClock.uptimeMillis() - downAt
                    // ---- 优先级 3：单击 / 双击 ----
                    if (held <= TAP_TIMEOUT_MS && totalDrag.getDistance() <= slopPx) {
                        handleTap(tapState, scope, doubleTapWindowMs, callbacks)
                    }
                }
            }
        }
    }
}

/** 长按进入拖动的判定时长（ms）：> ViewConfiguration 长按阈值，避免与点击冲突。 */
private const val LONG_PRESS_MS = 350L

/** 点击最大时长（ms）：超过即视为长按/拖动，不触发点击。 */
private const val TAP_TIMEOUT_MS = 150L

/** 跨手势迭代的单击状态（双击判定窗口 250ms）。 */
private class TapState {
    var lastTapAt = 0L
    var pendingSingleTap: Job? = null
}

private fun handleTap(
    state: TapState,
    scope: kotlinx.coroutines.CoroutineScope,
    doubleTapWindowMs: Long,
    callbacks: OverlayGestureCallbacks,
) {
    val now = SystemClock.uptimeMillis()
    if (now - state.lastTapAt <= doubleTapWindowMs) {
        // 窗口内第二次点击：取消挂起的单击，触发双击
        state.pendingSingleTap?.cancel()
        state.pendingSingleTap = null
        state.lastTapAt = 0L
        callbacks.onDoubleTap()
    } else {
        state.lastTapAt = now
        state.pendingSingleTap?.cancel()
        state.pendingSingleTap = scope.launch {
            delay(doubleTapWindowMs)
            state.pendingSingleTap = null
            callbacks.onSingleTap()
        }
    }
}
