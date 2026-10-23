//
//  FocusedHealth.swift
//  DevicePerfMonitor
//
//  综合评判（对应 Android 端 analysis/FocusedHealth.kt，公式逐字一致）：
//  只认四个核心指标里的三个可算维度——
//  - 流畅度（权重 0.40）：实际帧率 / 屏幕刷新率 的达成率。刷新率是这块屏幕的
//    理论上限，帧率越接近它越流畅；30fps 在 30Hz 屏上是满帧，在 120Hz 屏上
//    就是严重掉帧，所以比单看 FPS 绝对值靠谱；
//  - 温度（权重 0.35）：设备温度 ℃，分段点来自 design-tokens.json（双端唯一真源）；
//  - 内存（权重 0.25）：占用率，50% 以下满分，100% 归零。
//
//  规则（与 Android 一致）：
//  - 某个维度采不到就整体不参与，权重按比例分给其他维度，并标注"部分指标不可用"；
//  - 全维度不可用时不给分（nil），不编造；
//  - iOS 无机身温度公开 API → 温度维度常态缺席，分数按流畅度 + 内存折算。
//
//  注：权重没有走 design-tokens 的 health.weights（那里是四维度含电池的旧模型），
//  与 Android 端保持同样的取舍；分段点和评级文案仍走 token，保证双端一致。
//

import Foundation

struct FocusedHealthResult {
    let total: Float?
    let grade: HealthScorer.Grade?
    let summary: String
    let smoothnessScore: Float?
    let temperatureScore: Float?
    let memoryScore: Float?
    /// 最短板维度（用于给出具体建议文案）。
    let weakest: FocusedHealth.Dimension?
    /// 是否有维度缺席（剩余维度按比例吃下权重）。
    let partial: Bool
}

enum FocusedHealth {

    private static let wSmoothness: Double = 0.40
    private static let wTemperature: Double = 0.35
    private static let wMemory: Double = 0.25

    enum Dimension: String, CaseIterable {
        case smoothness = "流畅度"
        case temperature = "温度"
        case memory = "内存"
    }

    /// - Parameters:
    ///   - memoryRatio: 内存占用率百分比（0~100）
    ///   - deviceTempC: 设备温度 ℃（iOS 上恒为 nil）
    ///   - refreshHz: 屏幕刷新率 Hz
    ///   - fps: 实时帧率
    static func evaluate(
        memoryRatio: Float?,
        deviceTempC: Float?,
        refreshHz: Float?,
        fps: Float?
    ) -> FocusedHealthResult {
        let smoothness = smoothnessScore(fps: fps, refreshHz: refreshHz)
        let temperature = deviceTempC.map { HealthScorer.temperatureScore($0) }
        let memory = memoryRatio.map { memoryScore(ratioPercent: $0) }

        // (维度, 分数, 权重)
        var parts: [(dimension: Dimension, score: Float, weight: Double)] = []
        if let smoothness { parts.append((.smoothness, smoothness, wSmoothness)) }
        if let temperature { parts.append((.temperature, temperature, wTemperature)) }
        if let memory { parts.append((.memory, memory, wMemory)) }

        if parts.isEmpty {
            return FocusedHealthResult(
                total: nil,
                grade: nil,
                summary: "数据不足，无法评判",
                smoothnessScore: nil,
                temperatureScore: nil,
                memoryScore: nil,
                weakest: nil,
                partial: true
            )
        }

        let weightSum = parts.reduce(0.0) { $0 + $1.weight }
        let total = parts.reduce(0.0) { $0 + Double($1.score) * $1.weight } / weightSum
        let clamped = Float(min(max(total, 0), 100))

        let weakest = parts.min { $0.score < $1.score }?.dimension
        let summary = buildSummary(total: clamped, weakest: weakest, partial: parts.count < 3)

        return FocusedHealthResult(
            total: clamped,
            grade: HealthScorer.gradeOf(clamped),
            summary: summary,
            smoothnessScore: smoothness,
            temperatureScore: temperature,
            memoryScore: memory,
            weakest: weakest,
            partial: parts.count < 3
        )
    }

    /// 流畅度分：帧率 / 刷新率的达成率分段映射（与 Android 逐段一致）。
    /// ≥95% 满分；80%~95% 线性降到 70；50%~80% 降到 30；低于 50% 线性归零。
    static func smoothnessScore(fps: Float?, refreshHz: Float?) -> Float? {
        guard let fps, let refreshHz, refreshHz > 0 else { return nil }
        let r = min(max(fps / refreshHz, 0), 1.2)
        let score: Float
        switch r {
        case 0.95...: score = 100
        case 0.80..<0.95: score = 70 + (r - 0.80) / 0.15 * 30
        case 0.50..<0.80: score = 30 + (r - 0.50) / 0.30 * 40
        default: score = (r / 0.50) * 30
        }
        return min(max(score, 0), 100)
    }

    /// 内存分：50% 及以下满分，100% 归零，中间线性。
    static func memoryScore(ratioPercent: Float) -> Float {
        let r = min(max(ratioPercent / 100, 0), 1)
        return min(max(100 - (r - 0.5) * 200, 0), 100)
    }

    private static func buildSummary(total: Float, weakest: Dimension?, partial: Bool) -> String {
        let head: String
        switch total {
        case 90...: head = "状态很好"
        case 75..<90: head = "状态良好"
        case 60..<75: head = "能用，但有点吃力"
        case 40..<60: head = "压力偏大"
        default: head = "压力较大"
        }
        var pieces = [head]
        if let weakest {
            switch weakest {
            case .smoothness: pieces.append("短板是流畅度，帧率没跑满屏幕刷新率")
            case .temperature: pieces.append("短板是温度")
            case .memory: pieces.append("短板是内存占用")
            }
        }
        if partial { pieces.append("（部分指标不可用，分数按可用项折算）") }
        return pieces.joined(separator: "，")
    }
}
