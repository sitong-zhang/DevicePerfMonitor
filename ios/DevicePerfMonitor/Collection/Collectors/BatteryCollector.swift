//
//  BatteryCollector.swift
//  DevicePerfMonitor
//
//  电池采集器：IOPSCopyPowerSourcesInfo（公开 IOKit API，始终可用）。
//
//  三态说明（iOS 平台限制，README「降级说明」同步记录）：
//  - 电量 / 充电状态：kIOPSCurrentCapacityKey / kIOPSIsChargingKey（公开）；
//  - 电流 / 电压 / 电池温度：iOS 无公开 API（kIOPSAmperageKey 等未公开）
//    → 常态 UNAVAILABLE（禁止用估算值冒充）；
//  - 电池健康百分比：iOS 无公开 API → 常态 UNAVAILABLE。
//

import Foundation
import IOKit

final class BatteryCollector {

    private let kIOPSIsChargingKey = "Is Charging"
    private let kIOPSCurrentCapacityKey = "Current Capacity"
    private let kIOPSMaxCapacityKey = "Max Capacity"
    private let kIOPSPowerSourceStateKey = "Power Source State"

    func sample(now: TimeInterval) -> BatteryMetrics {
        guard let info = IOPSCopyPowerSourcesInfo()?.takeRetainedValue(),
              let sources = IOPSCopyPowerSourcesList(info)?.takeRetainedValue() as? [CFTypeRef],
              let source = sources.first,
              let desc = IOPSGetPowerSourceDescription(info, source)?.takeUnretainedValue() as? [String: Any]
        else {
            let reason = "无法读取电源信息（IOPSCopyPowerSourcesInfo 失败）"
            return BatteryMetrics(
                temperatureC: .unavailable(reason: reason, at: now),
                currentMa: .unavailable(reason: reason, at: now),
                voltageV: .unavailable(reason: reason, at: now),
                levelPercent: .unavailable(reason: reason, at: now),
                chargingState: .unknown,
                healthPercent: .unavailable(reason: "iOS 无电池健康百分比公开 API", at: now)
            )
        }

        // 电量百分比
        let level: Float?
        if let current = desc[kIOPSCurrentCapacityKey] as? Int,
           let max = desc[kIOPSMaxCapacityKey] as? Int, max > 0 {
            level = Float(current) / Float(max) * 100
        } else {
            level = nil
        }

        // 充电状态（Power Source State + Is Charging 联合判断）
        let charging: Bool = (desc[kIOPSIsChargingKey] as? Bool) ?? false
        let onAC: Bool = (desc[kIOPSPowerSourceStateKey] as? String) == "AC Power"
        let state: ChargingState
        if charging && (level ?? 0) >= 100 {
            state = .full
        } else if charging {
            state = .charging
        } else if onAC {
            state = .notCharging
        } else {
            state = .discharging
        }

        return BatteryMetrics(
            temperatureC: .unavailable(reason: "iOS 无电池温度公开 API", at: now),
            currentMa: .unavailable(reason: "iOS 无电流公开 API", at: now),
            voltageV: .unavailable(reason: "iOS 无电压公开 API", at: now),
            levelPercent: Metric.make(level, at: now, unavailableReason: "无法读取电量"),
            chargingState: state,
            healthPercent: .unavailable(reason: "iOS 无电池健康百分比公开 API", at: now)
        )
    }
}
