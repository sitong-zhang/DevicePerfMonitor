//
//  ThermalCollector.swift
//  DevicePerfMonitor
//
//  温度采集器：ProcessInfo.thermalState（iOS 11.0+ 公开 API）。
//
//  三态说明（iOS 平台限制，README「降级说明」同步记录）：
//  - 系统热状态：ProcessInfo.thermalState + thermalStateDidChangeNotification
//    （粗粒度档位：nominal / fair / serious / critical）；
//  - 机身温度（℃）：iOS 无公开传感器直读 API（Android 可读
//    /sys/class/thermal）→ 常态 UNAVAILABLE（禁止用档位换算冒充温度值）；
//  - 热余量（0~1）：PowerManager.getThermalHeadroom 为 Android 专有 API，
//    iOS 无对应公开接口 → UNAVAILABLE。
//

import Foundation

final class ThermalCollector {

    /// 最近一次系统热状态。
    private(set) var status: ThermalStatus = .unknown

    private var observer: NSObjectProtocol?

    func start() {
        status = ThermalStatus(ProcessInfo.processInfo.thermalState)
        observer = NotificationCenter.default.addObserver(
            forName: ProcessInfo.thermalStateDidChangeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.status = ThermalStatus(ProcessInfo.processInfo.thermalState)
        }
    }

    func stop() {
        if let observer {
            NotificationCenter.default.removeObserver(observer)
        }
        observer = nil
    }

    func sample(now: TimeInterval) -> ThermalMetrics {
        ThermalMetrics(
            deviceTempC: .unavailable(reason: "iOS 无机身温度公开 API（仅热状态档位）", at: now),
            status: status
        )
    }
}
