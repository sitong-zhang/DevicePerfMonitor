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
 * 1. 拖动：位移超过 touchSlop 即进入拖动（**不需要长按**——按下即拖是
 *    悬浮窗的基本交互，1.0.9 及以前要求长按 350ms 且进入时不结算已拖
 *    位移，用户按下直接拖时前段全部丢失，表现为"完全不跟手"）；
 * 2. 缩放：双指捏合（拖动未开始时才生效，避免与拖动冲突）；
 * 3. 单击 / 双击：快速点击（无位移、短时长）。双击判定窗口 250ms
 *    （design-tokens gesture.doubleTapWindowMs）——第一次点击后延迟 250ms
 *    再执行单击动作，窗口内第二次落下则取消单击并触发双击；
 * 4. 贴边收窄：不是手势，而是窗口贴边后的自动形态（由控制器决定），
 *    因此永远让位给上述手势。
 *
 * 拖动位移的两段式结算（跟手性的关键）：
 * - 从按下到超过 slop 之前，位移只累积不发送（避免误触抖动）；
 * - 超过 slop 进入拖动的那一个事件，**先把累计位移一次性补发**
 *   （onDrag(totalDrag)），窗口立即跟上手指，之后按事件增量跟手。
 *   少这一步，进入拖动窗口不动、之后才动，用户感知就是"拖一大段只动一小段"。
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
                    // ---- 优先级 1：位移超过 slop → 立即拖动 ----
                    // 不要求长按：用户按下直接拖是基本交互。单击/双击靠
                    // "时长短 + 位移小" 判定，天然不与拖动冲突。
                    if (totalDrag.getDistance() > slopPx) {
                        dragging = true
                        callbacks.onDragStart()
                        // 补发累计位移：从按下点连续跟手（1.0.9 缺这一步，
                        // 导致"拖一大段只动一小段"）
                        if (totalDrag != Offset.Zero) {
                            callbacks.onDrag(totalDrag)
                        }
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
                    // ---- 优先级 3：单击 / 双击 ----
                    // 无拖动发生（位移未超 slop）且时长够短才算点击；
                    // 长按不动不触发任何动作（与单击语义互斥）
                    val held = SystemClock.uptimeMillis() - downAt
                    if (held <= TAP_TIMEOUT_MS && totalDrag.getDistance() <= slopPx) {
                        handleTap(tapState, scope, doubleTapWindowMs, callbacks)
                    }
                }
            }
        }
    }
}

/**
 * 点击最大时长（ms）：超过即视为长按，不触发点击。
 * 注意与拖动的区分只靠时长 + 位移：拖动不看时长（按下即拖），
 * 点击不看距离（只要没超过 slop 且松手快）。
 */
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
