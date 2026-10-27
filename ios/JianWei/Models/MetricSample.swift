import Foundation

/// 一次性能采样。iOS 不暴露设备温度，故评分维度只含流畅度 / 内存 / 电量。
struct MetricSample: Identifiable, Codable {
    let id: UUID
    let date: Date
    let cpu: Double          // 系统 CPU 占用 0...100 (%)
    let memoryUsedMB: Double
    let memoryTotalMB: Double
    let battery: Double       // 0...1
    let fps: Double
    let maxFps: Double
    let storageUsedGB: Double
    let storageTotalGB: Double

    var smoothness: Double {  // 0...1，帧率达成率
        guard maxFps > 0 else { return 0 }
        return min(1, fps / maxFps)
    }

    var memoryPressure: Double { // 0...1
        guard memoryTotalMB > 0 else { return 0 }
        return min(1, memoryUsedMB / memoryTotalMB)
    }

    /// 综合健康分 0...100
    var score: Int {
        let s = smoothness * 0.5 + (1 - memoryPressure) * 0.3 + battery * 0.2
        return Int((s * 100).rounded())
    }
}
