//
//  BatteryCollector.swift
//  DevicePerfMonitor
//
//  电池采集器：UIDevice 公开 API（电量 / 充电状态）。
//  注：IOPSCopyPowerSourcesInfo 那套 IOKit API 只有 macOS 有，iOS SDK
//  链接不到 IOKit，编译直接报 no such module。
//
//  三态说明（iOS 平台限制，README「降级说明」同步记录）：
//  - 电量 / 充电状态：UIDevice.batteryLevel / batteryState（公开）；
//  - 电流 / 电压 / 电池温度：iOS 无公开 API → 常态 UNAVAILABLE
//    （禁止用估算值冒充）；
//  - 电池健康百分比：iOS 无公开 API → 常态 UNAVAILABLE。
//

import Foundation
import UIKit

final class BatteryCollector {

    func sample(now: TimeInterval) -> BatteryMetrics {
        UIDevice.current.isBatteryMonitoringEnabled = true
        defer { UIDevice.current.isBatteryMonitoringEnabled = false }

        let level = UIDevice.current.batteryLevel
        let state = UIDevice.current.batteryState

        // batteryLevel 在拿不到时返回 -1
        let percent: Float? = level >= 0 ? level * 100 : nil

        let charging: ChargingState
        switch state {
        case .charging: charging = .charging
        case .full: charging = .full
        case .unplugged: charging = .discharging
        case .unknown: charging = .unknown
        @unknown default: charging = .unknown
        }

        return BatteryMetrics(
            temperatureC: .unavailable(reason: "iOS 无电池温度公开 API", at: now),
            currentMa: .unavailable(reason: "iOS 无电流公开 API", at: now),
            voltageV: .unavailable(reason: "iOS 无电压公开 API", at: now),
            levelPercent: Metric.make(percent, at: now, unavailableReason: "无法读取电量"),
            chargingState: charging,
            healthPercent: .unavailable(reason: "iOS 无电池健康百分比公开 API", at: now)
        )
    }
}
