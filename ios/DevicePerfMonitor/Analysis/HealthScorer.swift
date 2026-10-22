//
//  HealthScorer.swift
//  DevicePerfMonitor
//
//  综合健康分引擎（本地纯算法，不联网、不调用大模型；公式与 Android 端逐字一致，
//  权重真源为 design-tokens.json 的 health 段）。
//
//  公式：
//    总分 = 温度分×0.4 + 帧率稳定性分×0.3 + 电池分×0.2 + 内存分×0.1
//  - 温度分：T = max(CPU 温度, 电池温度)；
//      T≤40 → 100；40<T≤50 → 线性 100→70；50<T≤60 → 线性 70→35；
//      60<T≤70 → 线性 35→10；T>70 → 0；
//  - 帧率稳定性分：iOS 无全局 FPS，降级用本 App 帧时间标准差 σ，
//      S = max(0, 100 - σ×10)，标记"参考值"；
//  - 电池分：基础 100，按电量/温度/电流/健康度扣减，clamp 0~100；
//  - 内存分：M = 已用/总量，S = max(0, 100 - (M-0.5)×200)；
//  - 任一维度不可用：其权重按比例分配给其余可用维度，UI 标注"部分指标不可用"；
//  - 全维度不可用：总分 nil，UI 显示"数据不足，无法分析"，禁止编造分数。
//

import Foundation

enum HealthScorer {

    enum DimensionId: String, CaseIterable {
        case temperature = "温度"
        case frameStability = "帧率稳定性"
        case battery = "电池"
        case memory = "内存"
    }

    /// 评级档位。序与 design-tokens.json 的 health.grades 一致（按 minScore 降序），
    /// 因此可直接按下标互转；文案由 token 提供，避免双端文案漂移。
    enum Grade: String, CaseIterable {
        case excellent = "优秀"
        case good = "良好"
        case fair = "一般"
        case warning = "警告"
    }

    struct DimensionScore {
        let id: DimensionId
        let score: Float?
        let weight: Double
        /// 帧率稳定性降级为帧时间标准差时标记为参考值。
        let isReference: Bool
    }

    struct HealthResult {
        let total: Float?
        let grade: Grade?
        let dimensions: [DimensionScore]
        let partial: Bool
    }

    // MARK: - 各维度原始评分

    /// 温度分：分段线性映射（分段参数来自 design-tokens.json，
    /// 与 Android 端 [DesignTokens.Health.TEMPERATURE_BREAKPOINTS] 同源）。
    static func temperatureScore(_ t: Float) -> Float {
        let bps = DesignTokens.Health.temperatureBreakpoints
        for i in bps.indices {
            let bp = bps[i]
            guard let upper = bp.max else { return clamp(bp.to) }
            if Double(t) <= upper {
                if bp.from == bp.to { return clamp(bp.from) }
                let lower = i > 0 ? bps[i - 1].max : nil
                let lowerValue = lower ?? -.infinity
                let span = upper - lowerValue
                if !span.isFinite || span <= 0 { return clamp(bp.to) }
                let ratio = min(max((Double(t) - lowerValue) / span, 0), 1)
                return clamp(bp.from + (bp.to - bp.from) * ratio)
            }
        }
        return 0
    }

    private static func clamp(_ v: Double) -> Float {
        Float(min(max(v, 0), 100))
    }

    /// 帧率稳定性分（iOS 无全局 FPS，仅帧时间标准差降级路径，恒为参考值）。
    static func frameStabilityScore(frameStdevMs: Float?) -> (score: Float?, isReference: Bool) {
        guard let stdev = frameStdevMs else { return (nil, false) }
        return (max(0, 100 - stdev * 10), true)
    }

    /// 电池分：基础 100，按规则扣减后 clamp 0~100。
    static func batteryScore(
        temperatureC: Float?,
        currentMa: Float?,
        charging: Bool,
        levelPercent: Float?,
        healthPercent: Float?
    ) -> Float? {
        if temperatureC == nil && levelPercent == nil { return nil }
        var score: Float = 100
        if let temp = temperatureC, temp > 40 {
            score -= min(40, (temp - 40) * 5)
        }
        if charging, let current = currentMa, current > 3000 { score -= 15 }
        if !charging, let current = currentMa, current < -1500 { score -= 10 }
        if let level = levelPercent, level < 20 { score -= 10 }
        if let health = healthPercent, health < 80 { score -= 15 }
        return min(max(score, 0), 100)
    }

    /// 内存分：50% 得 100、100% 得 0。
    static func memoryScore(usedMb: Float?, totalMb: Float?) -> Float? {
        guard let used = usedMb, let total = totalMb, total > 0 else { return nil }
        let ratio = min(max(used / total, 0), 1)
        return min(max(100 - (ratio - 0.5) * 200, 0), 100)
    }

    // MARK: - 综合计算

    static func score(_ snapshot: DeviceSnapshot) -> HealthResult {
        // 温度维度：CPU 温度与电池温度取较大值（iOS 上两者通常不可用）
        let maxTemp = [snapshot.cpu.temperatureC.value, snapshot.battery.temperatureC.value]
            .compactMap { $0 }.max()
        let temperature = maxTemp.map { temperatureScore($0) }

        // 帧率稳定性：iOS 无全局 FPS → 帧时间标准差降级路径（参考值）
        let (frameScore, frameIsReference) = frameStabilityScore(
            frameStdevMs: snapshot.display.frameStdevMs.value
        )

        let battery = batteryScore(
            temperatureC: snapshot.battery.temperatureC.value,
            currentMa: snapshot.battery.currentMa.value,
            charging: snapshot.battery.chargingState == .charging,
            levelPercent: snapshot.battery.levelPercent.value,
            healthPercent: snapshot.battery.healthPercent.value
        )

        let memory = memoryScore(
            usedMb: snapshot.memory.usedMb.value,
            totalMb: snapshot.memory.totalMb.value
        )

        let dimensions: [DimensionScore] = [
            DimensionScore(id: .temperature, score: temperature, weight: DesignTokens.Health.Weights.temperature, isReference: false),
            DimensionScore(id: .frameStability, score: frameScore, weight: DesignTokens.Health.Weights.frameStability, isReference: frameIsReference),
            DimensionScore(id: .battery, score: battery, weight: DesignTokens.Health.Weights.battery, isReference: false),
            DimensionScore(id: .memory, score: memory, weight: DesignTokens.Health.Weights.memory, isReference: false),
        ]

        let available = dimensions.filter { $0.score != nil }
        if available.isEmpty {
            return HealthResult(total: nil, grade: nil, dimensions: dimensions, partial: true)
        }
        let weightSum = available.reduce(0.0) { $0 + $1.weight }
        let total = available.reduce(0.0) { $0 + Double($1.score!) * $1.weight } / weightSum

        return HealthResult(
            total: min(max(Float(total), 0), 100),
            grade: gradeOf(Float(total)),
            dimensions: dimensions,
            partial: available.count < dimensions.count
        )
    }

    /// 评级：阈值与文案均来自 design-tokens.json 的 health.grades（双端唯一真源）。
    static func gradeOf(_ total: Float) -> Grade {
        let grades = DesignTokens.Health.grades
        for (index, spec) in grades.enumerated() {
            if Double(total) >= spec.min {
                return Grade.allCases[index]
            }
        }
        return .warning
    }
}
