//
//  DisplayCollector.swift
//  DevicePerfMonitor
//
//  显示系统采集器：FPS / 帧时间 / 掉帧（CADisplayLink，本 App 绘制帧）。
//
//  三态说明：
//  - 本 App FPS：CADisplayLink 帧回调（公开 API，始终可用；
//    超过 2s 无帧判定"当前无持续绘制" → UNAVAILABLE，不用旧值冒充实时）；
//  - 全局 FPS：iOS 无公开 API（Android 端可用 root/Shizuku 的
//    `service call SurfaceFlinger` 读取，iOS 平台限制）→ 常态 UNAVAILABLE；
//  - 屏幕刷新率：UIScreen.maximumFramesPerSecond（iOS 10+，公开）。
//
//  FPS 窗口固定 500ms（与需求一致），与 Repository 采集 tick 解耦。
//

import Foundation
import QuartzCore
import UIKit

final class DisplayCollector {

    private let lock = NSLock()
    private var frameTimesMs: [Float] = []      // 最近 30s 帧间隔
    private var lastTimestamp: CFTimeInterval = 0
    private var windowFrames = 0
    private var windowJankStart = 0
    private var windowStartMs: Int64 = 0
    private var jankBuckets: [(endMs: Int64, janky: Int, total: Int)] = []

    private var _fps: Float = 0
    private var _lastFrameAtMs: Int64 = 0
    private var _jankCount = 0
    private var _jankTotal = 0
    private var running = false
    private var link: CADisplayLink?

    private let maxFrames = 30 * 60 * 2   // 30s，按 120Hz 上限预留
    private let bucketCapacity = 60       // 500ms × 60 = 30s 窗口
    private let fpsWindowMs: Int64 = 500
    private let noFrameTimeoutMs: Int64 = 2_000
    private let jankFactor: Float = 1.5

    func start() {
        guard !running else { return }
        running = true
        let displayLink = CADisplayLink(target: self, selector: #selector(onFrame(_:)))
        displayLink.add(to: .main, forMode: .common)
        link = displayLink
    }

    func stop() {
        running = false
        link?.invalidate()
        link = nil
    }

    @objc private func onFrame(_ displayLink: CADisplayLink) {
        let timestamp = displayLink.timestamp
        let nowMs = Int64(timestamp * 1000)
        if lastTimestamp != 0 {
            let dtMs = Float((timestamp - lastTimestamp) * 1000)
            // 过滤息屏/窗口切换造成的超大间隔，只统计真实连续帧
            if dtMs >= 0.5 && dtMs <= 500 {
                lock.lock()
                frameTimesMs.append(dtMs)
                if frameTimesMs.count > maxFrames { frameTimesMs.removeFirst(frameTimesMs.count - maxFrames) }
                lock.unlock()
                _jankTotal += 1
                if dtMs > expectedIntervalMs() * jankFactor { _jankCount += 1 }
            }
        }
        lastTimestamp = timestamp
        _lastFrameAtMs = nowMs
        windowFrames += 1
        if windowStartMs == 0 {
            windowStartMs = nowMs
            windowJankStart = _jankCount
        }
        if nowMs - windowStartMs >= fpsWindowMs {
            let elapsed = max(1, nowMs - windowStartMs)
            _fps = Float(windowFrames) * 1000 / Float(elapsed)
            let jankyDelta = _jankCount - windowJankStart
            lock.lock()
            jankBuckets.append((endMs: nowMs, janky: jankyDelta, total: windowFrames))
            if jankBuckets.count > bucketCapacity { jankBuckets.removeFirst(jankBuckets.count - bucketCapacity) }
            lock.unlock()
            windowFrames = 0
            windowStartMs = nowMs
            windowJankStart = _jankCount
        }
    }

    /// 500ms 粒度 FPS 直读（供悬浮窗使用）；无持续绘制时 nil。
    func currentFps(nowMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> Float? {
        guard nowMs - _lastFrameAtMs <= noFrameTimeoutMs, _fps > 0 else { return nil }
        return _fps
    }

    private func expectedIntervalMs() -> Float {
        1000 / (currentRefreshRateHz() ?? 60)
    }

    /// UIScreen.maximumFramesPerSecond（60 / 120，iOS 10+ 公开 API）。
    func currentRefreshRateHz() -> Float? {
        let rate = Float(UIScreen.main.maximumFramesPerSecond)
        return rate > 0 ? rate : nil
    }

    func sample(now: TimeInterval) -> DisplayMetrics {
        let at = now
        let fpsMetric: Metric<Float> = currentFps()
            .map { .available($0, at: at) }
            ?? .unavailable(reason: "当前无持续绘制，无法测量本 App FPS", at: at)

        lock.lock()
        let samples = frameTimesMs
        let buckets = jankBuckets
        lock.unlock()

        var frameTime: Metric<Float> = .unavailable(reason: "暂无帧样本", at: at)
        var stdev: Metric<Float> = .unavailable(reason: "暂无帧样本", at: at)
        if !samples.isEmpty {
            let mean = samples.reduce(0, +) / Float(samples.count)
            frameTime = .available(mean, at: at)
            let variance = samples.map { ($0 - mean) * ($0 - mean) }.reduce(0, +) / Float(samples.count)
            stdev = .available(variance.squareRoot(), at: at)
        }

        let jank: Metric<Int> = _jankTotal > 0 ? .available(_jankCount, at: at)
                                             : .unavailable(reason: "暂无帧样本", at: at)

        // 最近 30s 掉帧率：Σ丢帧 / Σ总帧（桶数据不足 2s 判样本不足）
        let nowMs = Int64(now * 1000)
        let recent = buckets.filter { nowMs - $0.endMs <= 30_000 }
        let total = recent.reduce(0) { $0 + $1.total }
        let jankRatio: Metric<Float> = total >= 4
            ? .available(Float(recent.reduce(0) { $0 + $1.janky }) / Float(total) * 100, at: at)
            : .unavailable(reason: "30s 样本不足", at: at)

        let refresh: Metric<Float> = currentRefreshRateHz()
            .map { .available($0, at: at) }
            ?? .unavailable(reason: "无法读取屏幕刷新率", at: at)

        return DisplayMetrics(
            fps: fpsMetric,
            globalFps: .unavailable(reason: "iOS 无全局 FPS 公开 API", at: at),
            frameTimeMs: frameTime,
            frameStdevMs: stdev,
            jankFrames: jank,
            jankRatio30s: jankRatio,
            refreshRateHz: refresh
        )
    }
}
