import Foundation

/// 本地存储：秒级采样，最多保留约 2 小时。数据仅存本机，不上传。
final class MetricsStore {
    static let shared = MetricsStore()
    private let fileURL: URL
    private let maxSamples = 7200

    private init() {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        fileURL = dir.appendingPathComponent("metrics.json")
    }

    func load() -> [MetricSample] {
        guard let data = try? Data(contentsOf: fileURL) else { return [] }
        return (try? JSONDecoder().decode([MetricSample].self, from: data)) ?? []
    }

    func append(_ sample: MetricSample) {
        var all = load()
        all.append(sample)
        if all.count > maxSamples { all.removeFirst(all.count - maxSamples) }
        if let data = try? JSONEncoder().encode(all) {
            try? data.write(to: fileURL, options: .atomic)
        }
    }

    func recent(_ minutes: Int) -> [MetricSample] {
        let cutoff = Date().addingTimeInterval(-Double(minutes) * 60)
        return load().filter { $0.date >= cutoff }
    }
}
