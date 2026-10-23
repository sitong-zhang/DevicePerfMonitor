//
//  HealthScorer.swift
//  DevicePerfMonitor
//
//  共享评分零件：温度分段映射 + 评级档位（与 Android 端逐字一致）。
//
//  综合评判的公式在 FocusedHealth.swift（四个核心指标 → 流畅度 / 温度 / 内存三维度），
//  这里只保留两个与维度无关的公共零件，分段参数双端唯一真源：
//  - 温度分：分段线性映射，分段参数来自 design-tokens.json；
//  - 评级档位 Grade：序与 design-tokens.json 的 health.grades 一致
//    （按 minScore 降序），文案由 token 提供，避免双端文案漂移。
//

import Foundation

enum HealthScorer {

    /// 评级档位。序与 design-tokens.json 的 health.grades 一致（按 minScore 降序），
    /// 因此可直接按下标互转；文案由 token 提供，避免双端文案漂移。
    enum Grade: String, CaseIterable {
        case excellent = "优秀"
        case good = "良好"
        case fair = "一般"
        case warning = "警告"
    }

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
