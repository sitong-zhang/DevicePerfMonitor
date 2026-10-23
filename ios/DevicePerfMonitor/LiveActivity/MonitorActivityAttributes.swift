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
        /// 屏幕刷新率 Hz。
        var refreshHz: Double?
        /// 内存占用率 %。
        var memoryRatio: Double?
        /// 告警态（系统热状态异常 / 帧时间不稳定）。
        var alert: Bool
        /// 快照时间戳（ms）。
        var timestampMs: Int64

        init(fps: Double? = nil, refreshHz: Double? = nil, memoryRatio: Double? = nil, alert: Bool = false, timestampMs: Int64 = 0) {
            self.fps = fps
            self.refreshHz = refreshHz
            self.memoryRatio = memoryRatio
            self.alert = alert
            self.timestampMs = timestampMs
        }
    }

    /// 固定属性（活动创建时确定）。
    var startedAt: Date
}
