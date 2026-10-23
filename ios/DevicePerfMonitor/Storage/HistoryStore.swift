//
//  HistoryStore.swift
//  DevicePerfMonitor
//
//  秒级历史存储（对应需求「30 分钟秒级历史，本地存储不联网」）：
//  - 内存环形缓冲 + 落盘持久化（JSON，Codable，App 沙盒 Documents/History/）；
//  - 每个 historyTracked 指标一条时间序列 (timestampMs, value)；
//  - 落盘周期 30s（与 Android 端 PERSIST_INTERVAL_MS 一致），
//    冷启动时读回，保证杀进程后历史不丢；
//  - 只存真实采样值：UNAVAILABLE 的采样点直接跳过（不补零、不插值伪造）。
//

import Foundation

struct HistoryPoint: Identifiable, Equatable {
    let id = UUID()
    let timestampMs: Int64
    let value: Float
}

struct HistorySeries: Codable {
    let metricId: String
    var points: [(timestampMs: Int64, value: Float)]

    // Codable 桥接：元组不能直接 Codable
    enum CodingKeys: String, CodingKey {
        case metricId
        case timestamps
        case values
    }

    init(metricId: String, points: [(timestampMs: Int64, value: Float)]) {
        self.metricId = metricId
        self.points = points
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        metricId = try c.decode(String.self, forKey: .metricId)
        let ts = try c.decode([Int64].self, forKey: .timestamps)
        let vs = try c.decode([Float].self, forKey: .values)
        points = Array(zip(ts, vs))
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(metricId, forKey: .metricId)
        try c.encode(points.map { $0.timestampMs }, forKey: .timestamps)
        try c.encode(points.map { $0.value }, forKey: .values)
    }
}

struct HistoryFile: Codable {
    var series: [HistorySeries]
}

final class HistoryStore {

    static let shared = HistoryStore()

    /// 保留 2 小时（与 Android 端一致：开始采集后不删，可回看）。
    let retentionMs: Int64 = 2 * 60 * 60 * 1000

    private let queue = DispatchQueue(label: "com.devperf.monitor.history")
    private var buffer: [String: [(timestampMs: Int64, value: Float)]] = [:]
    private var lastPersistMs: Int64 = 0
    private let persistIntervalMs: Int64 = 30_000

    private var fileURL: URL {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("History", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("history.json")
    }

    private init() {
        load()
    }

    // MARK: - 写入

    func append(metricId: MetricId, timestampMs: Int64, value: Float) {
        queue.async {
            self.buffer[metricId.rawValue, default: []].append((timestampMs: timestampMs, value: value))
            self.trim(metricId: metricId.rawValue, nowMs: timestampMs)
            if timestampMs - self.lastPersistMs >= self.persistIntervalMs {
                self.persistLocked()
                self.lastPersistMs = timestampMs
            }
        }
    }

    private func trim(metricId: String, nowMs: Int64) {
        guard var points = buffer[metricId] else { return }
        points.removeAll { nowMs - $0.timestampMs > retentionMs }
        buffer[metricId] = points
    }

    // MARK: - 查询

    /// 最近 windowSec 秒的序列（升序）。
    func points(metricId: MetricId, windowSec: Int) -> [HistoryPoint] {
        queue.sync {
            let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
            guard let points = buffer[metricId.rawValue] else { return [] }
            return points
                .filter { nowMs - $0.timestampMs <= Int64(windowSec) * 1000 }
                .sorted { $0.timestampMs < $1.timestampMs }
                .map { HistoryPoint(timestampMs: $0.timestampMs, value: $0.value) }
        }
    }

    /// 窗口内峰值统计（样本 < 2 返回 nil，不插值伪造）。
    func peakStats(metricId: MetricId, windowSec: Int) -> (max: Float, min: Float, avg: Float, latest: Float, samples: Int)? {
        let pts = points(metricId: metricId, windowSec: windowSec)
        guard pts.count >= 2 else { return nil }
        let values = pts.map { $0.value }
        return (
            max: values.max() ?? 0,
            min: values.min() ?? 0,
            avg: values.reduce(0, +) / Float(values.count),
            latest: values[values.count - 1],
            samples: pts.count
        )
    }

    // MARK: - 持久化

    private func persistLocked() {
        let file = HistoryFile(series: buffer.map { key, value in
            HistorySeries(metricId: key, points: value)
        })
        queue.async(flags: .barrier) {
            do {
                let data = try JSONEncoder().encode(file)
                try data.write(to: self.fileURL, options: .atomic)
            } catch {
                #if DEBUG
                print("[HistoryStore] 持久化失败: \(error)")
                #endif
            }
        }
    }

    private func load() {
        queue.async(flags: .barrier) {
            guard let data = try? Data(contentsOf: self.fileURL),
                  let file = try? JSONDecoder().decode(HistoryFile.self, from: data) else { return }
            var loaded: [String: [(timestampMs: Int64, value: Float)]] = [:]
            for series in file.series {
                loaded[series.metricId] = series.points
            }
            self.buffer = loaded
        }
    }

    /// 立即落盘（App 退后台时调用）。
    func flush() {
        queue.sync(flags: .barrier) {
            let file = HistoryFile(series: buffer.map { key, value in
                HistorySeries(metricId: key, points: value)
            })
            do {
                let data = try JSONEncoder().encode(file)
                try data.write(to: self.fileURL, options: .atomic)
            } catch {
                #if DEBUG
                print("[HistoryStore] flush 失败: \(error)")
                #endif
            }
        }
    }
}
