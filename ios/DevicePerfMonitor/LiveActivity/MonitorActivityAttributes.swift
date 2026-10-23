//
//  MonitorActivityAttributes.swift
//  DevicePerfMonitor
//
//  ActivityKit 实时活动属性（iOS 16.1+）：灵动岛 / 锁屏实时显示核心指标。
//  与 App 共享类型需放在 App target（widget extension 通过 @main bundle 引用）。
//

import Foundation
import ActivityKit

struct MonitorActivityAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        /// 帧率（nil = 不可用，UI 不显示该项）。
        var fps: Double?
        /// 内存占用率 %。
        var memoryRatio: Double?
        /// 机身温度 ℃（iOS 无传感器直读 API，常态 nil → UI 不显示该项）。
        var deviceTempC: Double?
        /// 告警态（系统热状态异常 / 帧时间不稳定）。
        var alert: Bool
        /// 快照时间戳（ms）。
        var timestampMs: Int64

        // 1.0.9 起实况窗只显示 温度 / 内存占用率 / 实时帧率 三项（用户要求）。
        // 刷新率从实况窗移除（移到应用内看）；温度在 iOS 上没有公开传感器 API，
        // 这里会是 nil，UI 按「采不到不占位」原则整行不渲染，不拿热状态档位换算。
        init(
            fps: Double? = nil,
            memoryRatio: Double? = nil,
            deviceTempC: Double? = nil,
            alert: Bool = false,
            timestampMs: Int64 = 0
        ) {
            self.fps = fps
            self.memoryRatio = memoryRatio
            self.deviceTempC = deviceTempC
            self.alert = alert
            self.timestampMs = timestampMs
        }
    }

    /// 固定属性（活动创建时确定）。
    var startedAt: Date
}
