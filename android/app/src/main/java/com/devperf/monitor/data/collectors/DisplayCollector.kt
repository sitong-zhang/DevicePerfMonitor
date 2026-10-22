package com.devperf.monitor.data.collectors

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Choreographer
import android.view.Display
import com.devperf.monitor.core.Metric
import com.devperf.monitor.core.MetricId
import kotlinx.coroutines.CoroutineScope
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 显示系统采集器。
 *
 * 数据来源与三态：
 * - FPS / 帧时间 / 掉帧：Choreographer 帧回调（本 App 绘制帧，始终可用；
 *   超过 2s 无帧时判定"当前无持续绘制"，显示不可用，不用旧值冒充实时）；
 * - 全局 FPS：见 [GlobalFpsCollector]（需 root/Shizuku）；
 * - 屏幕刷新率：DisplayManager（始终可用）。
 *
 * FPS 窗口固定 500ms（与需求一致），与 Repository 的采集 tick 解耦。
 */
class DisplayCollector(private val context: Context?) : MetricCollector {

    private val lock = Any()
    private val frameTimesMs = ArrayDeque<Float>()      // 最近 30s 的帧间隔
    private var lastFrameNanos = 0L
    private var windowFrames = 0
    private var windowJankStart = 0
    private var windowStartMs = 0L

    /** 500ms 粒度的掉帧桶：用于计算最近 30s 掉帧率（健康分"帧率稳定性"维度）。 */
    private data class JankBucket(val endMs: Long, val janky: Int, val total: Int)

    private val jankBuckets = ArrayDeque<JankBucket>()

    @Volatile
    private var fpsValue = 0f

    @Volatile
    private var lastFrameAtMs = 0L

    @Volatile
    private var jankCount = 0

    @Volatile
    private var jankTotal = 0

    @Volatile
    private var running = false

    private val displayManager: DisplayManager? =
        context?.getSystemService(DisplayManager::class.java)

    // 用 object 表达式而非 SAM lambda：lambda 内的 this 指向外部类，
    // postFrameCallback(this) 会因 DisplayCollector 未实现 FrameCallback 而编译失败
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val nowMs = frameTimeNanos / 1_000_000
            if (lastFrameNanos != 0L) {
                val dtMs = (frameTimeNanos - lastFrameNanos) / 1_000_000f
                // 过滤息屏/窗口切换造成的超大间隔，只统计真实连续帧
                if (dtMs in 0.5f..500f) {
                    synchronized(lock) {
                        frameTimesMs.addLast(dtMs)
                        while (frameTimesMs.size > MAX_FRAMES) frameTimesMs.removeFirst()
                    }
                    jankTotal++
                    if (dtMs > expectedIntervalMs() * JANK_FACTOR) jankCount++
                }
            }
            lastFrameNanos = frameTimeNanos
            lastFrameAtMs = nowMs
            windowFrames++
            if (windowStartMs == 0L) {
                windowStartMs = nowMs
                windowJankStart = jankCount
            }
            if (nowMs - windowStartMs >= FPS_WINDOW_MS) {
                val elapsed = (nowMs - windowStartMs).coerceAtLeast(1L)
                fpsValue = windowFrames * 1000f / elapsed
                val jankyDelta = jankCount - windowJankStart
                synchronized(lock) {
                    jankBuckets.addLast(JankBucket(nowMs, jankyDelta, windowFrames))
                    while (jankBuckets.size > BUCKET_CAPACITY) jankBuckets.removeFirst()
                }
                windowFrames = 0
                windowStartMs = nowMs
                windowJankStart = jankCount
            }
            if (running) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = Unit
    }

    override fun start(scope: CoroutineScope) {
        if (running) return
        running = true
        runCatching {
            displayManager?.registerDisplayListener(displayListener, null)
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }.onFailure {
            // 理论上不会失败；若失败则 FPS 指标进入不可用态，不影响其他采集
            running = false
        }
    }

    override fun stop() {
        running = false
        runCatching {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            displayManager?.unregisterDisplayListener(displayListener)
        }
    }

    override suspend fun sample(): Map<MetricId, Metric<*>> {
        val now = System.currentTimeMillis()
        val fps = currentFps(now)?.let { Metric.available(it, now) }
            ?: Metric.unavailable("当前无持续绘制，无法测量本 App FPS", now)

        val (avg, stdev) = synchronized(lock) {
            if (frameTimesMs.isEmpty()) 0f to 0f
            else {
                val mean = frameTimesMs.average().toFloat()
                val variance = frameTimesMs.map { (it - mean) * (it - mean) }.average().toFloat()
                mean to sqrt(variance.toDouble()).toFloat()
            }
        }
        val frameTime = if (frameTimesMs.isNotEmpty()) Metric.available(avg, now)
        else Metric.unavailable("暂无帧样本", now)
        val stdevMetric = if (frameTimesMs.isNotEmpty()) Metric.available(stdev, now)
        else Metric.unavailable("暂无帧样本", now)

        val jank = if (jankTotal > 0) Metric.available(jankCount, now)
        else Metric.unavailable("暂无帧样本", now)

        // 最近 30s 掉帧率：Σ丢帧 / Σ总帧（桶数据不足 2s 时判为样本不足）
        val (jankRatio, jankRatioAvailable) = synchronized(lock) {
            val nowMs = System.currentTimeMillis()
            val recent = jankBuckets.filter { nowMs - it.endMs <= 30_000L }
            val total = recent.sumOf { it.total }
            if (total >= 4) recent.sumOf { it.janky }.toFloat() / total * 100f to true
            else 0f to false
        }
        val jankRatioMetric = if (jankRatioAvailable) Metric.available(jankRatio, now)
        else Metric.unavailable("30s 样本不足", now)

        val refreshRate = currentRefreshRateHz()
        val refresh = if (refreshRate != null) Metric.available(refreshRate, now)
        else Metric.unavailable("无法读取屏幕刷新率", now)

        return mapOf(
            MetricId.FPS to fps,
            MetricId.FRAME_TIME to frameTime,
            MetricId.FRAME_STDEV to stdevMetric,
            MetricId.JANK_FRAMES to jank,
            MetricId.JANK_RATIO to jankRatioMetric,
            MetricId.REFRESH_RATE to refresh,
        )
    }

    /**
     * 500ms 粒度的 FPS 直读（供悬浮窗使用，绕开采集 tick）。
     * 无持续绘制 / 无有效窗口时返回 null（调用方按三态降级处理）。
     */
    fun currentFps(nowMs: Long = System.currentTimeMillis()): Float? =
        if (nowMs - lastFrameAtMs <= NO_FRAME_TIMEOUT_MS && fpsValue > 0f) fpsValue else null

    private fun expectedIntervalMs(): Float =
        1000f / (currentRefreshRateHz() ?: 60f)

    private fun currentRefreshRateHz(): Float? {
        val display: Display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val rate = runCatching { display.refreshRate }.getOrNull() ?: return null
        if (rate <= 0f) return null
        // 将 59.94 / 119.98 等实际值取整到最近标准档位，避免显示跳变
        val rounded = rate.roundToInt()
        return if (abs(rate - rounded) < 1f) rounded.toFloat() else rate
    }

    private companion object {
        const val FPS_WINDOW_MS = 500L
        const val MAX_FRAMES = 30 * 60 * 2   // 30s，按 60Hz 上限预留
        const val NO_FRAME_TIMEOUT_MS = 2_000L
        const val JANK_FACTOR = 1.5f
        const val BUCKET_CAPACITY = 60       // 500ms × 60 = 30s 窗口
    }
}
