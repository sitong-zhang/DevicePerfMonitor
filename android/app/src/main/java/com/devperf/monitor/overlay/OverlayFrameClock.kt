package com.devperf.monitor.overlay

import android.view.Choreographer
import androidx.compose.runtime.MonotonicFrameClock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 用 Choreographer 实现的 [MonotonicFrameClock]。
 *
 * Compose 的 Recomposer 和动画都要求协程上下文里带帧时钟：
 * `Recomposer.runRecomposeAndApplyChanges` 直接读 `coroutineContext[MonotonicFrameClock]`，
 * `Animatable.animateTo`（走 withFrameNanos）同理。缺了这个元素就抛
 * "A MonotonicFrameClock is not available in this CoroutineContext"。
 *
 * Activity 路径下这个元素由 compose-ui 内部的 AndroidUiDispatcher 提供，但那是
 * internal API 拿不到；Service 自跑 Recomposer 时必须自己补一个。实现就是
 * Choreographer 的 postFrameCallback：下一帧回来时把帧时间（ns）交还给挂起的协程，
 * 协程取消时摘掉回调，不泄漏。
 */
class OverlayFrameClock(
    private val choreographer: Choreographer = Choreographer.getInstance(),
) : MonotonicFrameClock {

    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R =
        suspendCancellableCoroutine { continuation ->
            val callback = Choreographer.FrameCallback { frameTimeNanos ->
                try {
                    continuation.resume(onFrame(frameTimeNanos))
                } catch (e: Throwable) {
                    continuation.resumeWithException(e)
                }
            }
            choreographer.postFrameCallback(callback)
            continuation.invokeOnCancellation {
                choreographer.removeFrameCallback(callback)
            }
        }
}
