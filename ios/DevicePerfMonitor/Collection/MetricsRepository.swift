//
//  MetricsRepository.swift
//  DevicePerfMonitor
//
//  采集调度中心（对应 Android 端 data/MetricsRepository.kt）：
//  - 定时器驱动各采集器 sample()，合成 DeviceSnapshot 发布给 UI；
//  - 三态保留策略：本次 UNAVAILABLE 的指标沿用上一次可用值（"最后一次真实
//    测量"，时间戳体现新鲜度），绝不插值伪造；
//  - historyTracked 指标的可用采样点写入 HistoryStore（30 分钟秒级历史）；
//  - 采集在后台并发队列执行，快照合并仅在该队列内进行（无数据竞争），
//    完成后通过 MainActor 发布给 SwiftUI。
//

import Foundation

/// 刷新模式（对应 Android 端 RefreshMode）。
enum RefreshMode: String, CaseIterable, Identifiable {
    case realtime = "实时（1s）"
    case balanced = "均衡（2s）"
    case powerSaving = "省电（5s）"

    var id: String { rawValue }

    var intervalMs: Double {
        switch self {
        case .realtime: return 1000
        case .balanced: return 2000
        case .powerSaving: return 5000
        }
    }
}

@MainActor
final class MetricsRepository: ObservableObject {

    static let shared = MetricsRepository()

    // MARK: - 发布状态（主线程读，SwiftUI 订阅）

    @Published private(set) var snapshot: DeviceSnapshot = .empty()
    @Published private(set) var tickMs: Double = 1000

    // MARK: - 采集器

    private let displayCollector = DisplayCollector()
    private let memoryCollector = MemoryCollector()
    private let cpuCollector = CpuCollector()
    private let batteryCollector = BatteryCollector()
    private let thermalCollector = ThermalCollector()

    private var timer: Timer?
    private let workQueue = DispatchQueue(label: "com.devperf.monitor.sampling", qos: .utility)

    /// 上一次合成快照：仅 workQueue 内访问（合并用）。
    private var lastSnapshot: DeviceSnapshot = .empty()

    private init() {}

    // MARK: - 生命周期

    func start(mode: RefreshMode = .realtime) {
        stop()
        displayCollector.start()
        thermalCollector.start()
        tickMs = mode.intervalMs
        scheduleTimer(interval: mode.intervalMs)
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        displayCollector.stop()
        thermalCollector.stop()
        HistoryStore.shared.flush()
    }

    /// 切换刷新模式（设置页调用）。
    func setRefreshMode(_ mode: RefreshMode) {
        tickMs = mode.intervalMs
        scheduleTimer(interval: mode.intervalMs)
    }

    private func scheduleTimer(interval: Double) {
        timer?.invalidate()
        let timer = Timer(timeInterval: interval / 1000, repeats: true) { [weak self] _ in
            self?.sampleOnce()
        }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    /// 立即采样一次（前台恢复 / 悬浮窗展开时调用）。
    func sampleOnce() {
        let now = Date().timeIntervalSince1970
        workQueue.async { [self] in
            let nowMs = Int64(now * 1000)

            let display = displayCollector.sample(now: now)
            let memory = memoryCollector.sample(now: now)
            let cpu = cpuCollector.sample(now: now)
            let battery = batteryCollector.sample(now: now)
            let thermal = thermalCollector.sample(now: now)

            // 历史写入：仅记录本次可用值（UNAVAILABLE 跳过，不补假数据）
            let historyCandidates: [(Metric<Float>, MetricId)] = [
                (display.fps, .fps),
                (display.jankRatio30s, .jankRatio),
                (cpu.usagePercent, .cpuUsage),
                (battery.temperatureC, .batteryTemp),
                (battery.currentMa, .batteryCurrent),
                (battery.levelPercent, .batteryLevel),
                (memory.usedMb, .memoryUsed),
                (memory.usageRatio, .memoryUsageRatio),
            ]
            for (metric, id) in historyCandidates {
                if case .available(let v, _) = metric {
                    HistoryStore.shared.append(metricId: id, timestampMs: nowMs, value: v)
                }
            }

            let previous = lastSnapshot

            let merged = DeviceSnapshot(
                timestampMs: nowMs,
                display: DisplayMetrics(
                    fps: keepOr(display.fps, previous.display.fps),
                    globalFps: .unavailable(reason: "iOS 无全局 FPS 公开 API", at: now),
                    frameTimeMs: keepOr(display.frameTimeMs, previous.display.frameTimeMs),
                    frameStdevMs: keepOr(display.frameStdevMs, previous.display.frameStdevMs),
                    jankFrames: display.jankFrames,
                    jankRatio30s: keepOr(display.jankRatio30s, previous.display.jankRatio30s),
                    refreshRateHz: keepOr(display.refreshRateHz, previous.display.refreshRateHz)
                ),
                cpu: CpuMetrics(
                    usagePercent: keepOr(cpu.usagePercent, previous.cpu.usagePercent),
                    perCoreUsage: keepOr(cpu.perCoreUsage, previous.cpu.perCoreUsage),
                    perCoreFreqMHz: .unavailable(reason: "iOS 无各核心频率公开 API", at: now),
                    temperatureC: .unavailable(reason: "iOS 无 CPU 温度公开 API", at: now)
                ),
                gpu: GpuMetrics(
                    loadPercent: .unavailable(reason: "iOS 无 GPU 负载公开 API", at: now),
                    freqMHz: .unavailable(reason: "iOS 无 GPU 频率公开 API", at: now),
                    temperatureC: .unavailable(reason: "iOS 无 GPU 温度公开 API", at: now)
                ),
                battery: BatteryMetrics(
                    temperatureC: .unavailable(reason: "iOS 无电池温度公开 API", at: now),
                    currentMa: .unavailable(reason: "iOS 无电流公开 API", at: now),
                    voltageV: .unavailable(reason: "iOS 无电压公开 API", at: now),
                    levelPercent: keepOr(battery.levelPercent, previous.battery.levelPercent),
                    chargingState: battery.chargingState,
                    healthPercent: .unavailable(reason: "iOS 无电池健康百分比公开 API", at: now)
                ),
                memory: MemoryMetrics(
                    totalMb: keepOr(memory.totalMb, previous.memory.totalMb),
                    usedMb: keepOr(memory.usedMb, previous.memory.usedMb),
                    availableMb: keepOr(memory.availableMb, previous.memory.availableMb),
                    usageRatio: keepOr(memory.usageRatio, previous.memory.usageRatio)
                ),
                thermal: ThermalMetrics(
                    deviceTempC: .unavailable(reason: "iOS 无机身温度公开 API（仅热状态档位）", at: now),
                    status: thermal.status
                )
            )

            lastSnapshot = merged

            Task { @MainActor in
                self.snapshot = merged
            }
        }
    }

    /// 本次不可用时沿用上一次可用值（"最后一次真实测量"，时间戳体现新鲜度）。
    private func keepOr<V>(_ fresh: Metric<V>, _ previous: Metric<V>) -> Metric<V> {
        if case .available = fresh { return fresh }
        if case .available = previous { return previous }
        return fresh
    }

    // MARK: - 历史查询（详情页 / 图表）

    func history(_ id: MetricId, windowSec: Int) -> [HistoryPoint] {
        HistoryStore.shared.points(metricId: id, windowSec: windowSec)
    }

    func peakStats(_ id: MetricId, windowSec: Int) -> (max: Float, min: Float, avg: Float, latest: Float, samples: Int)? {
        HistoryStore.shared.peakStats(metricId: id, windowSec: windowSec)
    }

    // MARK: - 悬浮窗数据源（500ms 粒度直读，绕开采集 tick）

    func overlayData(now: TimeInterval) -> OverlayData {
        let snap = snapshot
        let at = now
        let fps = displayCollector.currentFps()
            .map { Metric.available($0, at: at) }
            ?? snap.display.fps
        return OverlayData(
            fps: fps,
            cpuUsage: snap.cpu.usagePercent,
            cpuTemp: snap.cpu.temperatureC,
            batteryTemp: snap.battery.temperatureC,
            batteryLevel: snap.battery.levelPercent,
            memoryRatio: snap.memory.usageRatio
        )
    }
}
