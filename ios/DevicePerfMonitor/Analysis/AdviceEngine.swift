//
//  AdviceEngine.swift
//  DevicePerfMonitor
//
//  场景化智能建议引擎（纯本地规则，无联网、无大模型；规则与 Android 端一致，
//  iOS 上无电流/温度传感器 → 耗电规则降级为电量下降速率，其余规则保留）。
//
//  规则：
//  1. 高温：T = max(CPU 温度, 电池温度)（iOS 两者均不可用 → 规则不触发，
//     由热状态档位在告警层另行提示）
//    - 轻微（40~45℃）：降亮度、清后台；
//    - 中度（45~55℃）：关高刷、降画质；
//    - 严重（>55℃）：停高负载、静置降温，按 0.5℃/分钟估算降温时长
//      （预计时长 = (T - 40) / 0.5 分钟）；
//  2. 卡顿：帧时间标准差 > 8ms 或最近掉帧率 > 10% → 清后台、切性能模式；
//  3. 耗电：iOS 无电流 API → 降级为 5 分钟电量下降 > 8% 时提示；
//  4. 内存：占用 > 85% → 建议清理后台（iOS 无后台应用明细 → 不列名单）；
//  5. 数据不足：输出"数据不足，无法分析"，禁止编造建议。
//

import Foundation

enum AdviceEngine {

    enum Severity: Int, Comparable {
        case info, warning, critical

        static func < (lhs: Severity, rhs: Severity) -> Bool {
            lhs.rawValue < rhs.rawValue
        }
    }

    struct Advice: Identifiable {
        let id: String
        let severity: Severity
        let title: String
        let detail: String
    }

    /// 自然散热速率：℃/分钟。
    static let coolingRateCPerMin: Float = 0.5

    static func analyze(snapshot: DeviceSnapshot, history: HistoryStore) -> [Advice] {
        var advice: [Advice] = []

        let frameStdev = snapshot.display.frameStdevMs.value
        let jankRatio = snapshot.display.jankRatio30s.value
        let usageRatio = snapshot.memory.usageRatio.value
        let level = snapshot.battery.levelPercent.value

        // ---------- 1. 高温场景（iOS 温度不可用 → 仅热状态档位提示） ----------
        if snapshot.thermal.status == .serious {
            advice.append(Advice(
                id: "thermal_serious",
                severity: .warning,
                title: "系统热状态：严重",
                detail: "系统已报告严重热节流。建议降低屏幕亮度、暂停高负载应用；iOS 不提供机身温度读数，无法给出降温时长估算。"
            ))
        } else if snapshot.thermal.status == .critical {
            advice.append(Advice(
                id: "thermal_critical",
                severity: .critical,
                title: "系统热状态：危险",
                detail: "系统已报告危险级热节流，请立即停止高负载应用并静置设备。"
            ))
        }

        // ---------- 2. 卡顿场景 ----------
        let recentJank = history.points(metricId: .jankRatio, windowSec: 30)
        let sustainedJank = recentJank.count >= 3 && recentJank.suffix(3).allSatisfy { $0.value > 10 }
        if (frameStdev.map { $0 > 8 } ?? false) || (jankRatio.map { $0 > 10 } ?? false) || sustainedJank {
            var detail = ""
            if let stdev = frameStdev {
                detail += "帧时间标准差 \(String(format: "%.1f", stdev))ms。"
            }
            if let jank = jankRatio {
                detail += "最近掉帧率 \(String(format: "%.1f", jank))%。"
            }
            detail += "建议清理后台应用、切换到性能模式。"
            advice.append(Advice(
                id: "jank",
                severity: .warning,
                title: "检测到卡顿",
                detail: detail
            ))
        }

        // ---------- 3. 耗电场景（iOS 降级：5 分钟电量下降 > 8%） ----------
        let levelHistory = history.points(metricId: .batteryLevel, windowSec: 300)
        if levelHistory.count >= 2,
           let level,
           snapshot.battery.chargingState == .discharging {
            let oldest = levelHistory.first!.value
            if oldest - level > 8 {
                advice.append(Advice(
                    id: "battery_drain",
                    severity: .warning,
                    title: "电量下降偏快",
                    detail: "最近 5 分钟电量下降 \(String(format: "%.0f", oldest - level))%。建议关闭定位、蓝牙与后台刷新等耗电项。"
                ))
            }
        }

        // ---------- 4. 内存场景 ----------
        if let ratio = usageRatio, ratio > 85 {
            advice.append(Advice(
                id: "memory_high",
                severity: .warning,
                title: "内存占用过高（\(String(format: "%.0f", ratio))%）",
                detail: "建议清理后台应用。（iOS 不提供后台应用内存明细，无法列出占用名单）"
            ))
        }

        // ---------- 5. 数据不足 ----------
        let anyData = [frameStdev, jankRatio, usageRatio, level].compactMap { $0 }.isEmpty == false
        if !anyData {
            advice.append(Advice(
                id: "insufficient",
                severity: .info,
                title: "数据不足，无法分析",
                detail: "等待采样数据积累后生成建议。"
            ))
        }

        // 按严重程度降序（info < warning < critical）
        return advice.sorted { $0.severity > $1.severity }
    }
}
