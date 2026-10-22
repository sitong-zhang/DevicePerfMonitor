//
//  DeviceSnapshot.swift
//  DevicePerfMonitor
//
//  设备快照：每个类别一组指标，一律为三态 Metric（对应 Android 端
//  data/collectors/MetricCollector.kt 的 DeviceSnapshot）。
//
//  iOS 平台指标可用性总览（详见 README「API-权限-可用性-降级」对照表）：
//  - 内存：host_statistics64 / vm_statistics64（公开 mach API，始终可用）；
//  - 电池：IOPSCopyPowerSourcesInfo（公开 IOKit API，始终可用；
//    注意 iOS 无电流/电压/真实温度公开 API → 相应指标常态 UNAVAILABLE）；
//  - 温度：ProcessInfo.thermalState（公开，粗粒度）+ 无传感器直读；
//  - FPS：CADisplayLink（本 App 绘制帧，始终可用；全局 FPS iOS 无公开 API
//    → UNAVAILABLE，不用本 App 帧率冒充全局值）；
//  - CPU 使用率：host_processor_info（公开 mach API，整机+各核心时间片）；
//  - GPU：iOS 无公开 GPU 负载/频率 API → UNAVAILABLE（除 Metal 调试用
//    MTLCounterSampler 需 entitlements，本工程不申请）。
//

import Foundation

struct CoreUsage: Identifiable {
    let id = UUID()
    let index: Int
    let percent: Float
}

struct CoreFreq: Identifiable {
    let id = UUID()
    let index: Int
    let mhz: Float
}

enum ChargingState: String {
    case charging, discharging, full, notCharging, unknown

    var label: String {
        switch self {
        case .charging: return "充电中"
        case .discharging: return "放电中"
        case .full: return "已充满"
        case .notCharging: return "未充电"
        case .unknown: return "未知"
        }
    }
}

/// 系统热状态（ProcessInfo.thermalState，iOS 11.0+ 公开 API）。
enum ThermalStatus: String {
    case nominal, fair, serious, critical, unknown

    var label: String {
        switch self {
        case .nominal: return "正常"
        case .fair: return "轻微"
        case .serious: return "严重"
        case .critical: return "危险"
        case .unknown: return "未知"
        }
    }

    init(_ state: ProcessInfo.ThermalState) {
        switch state {
        case .nominal: self = .nominal
        case .fair: self = .fair
        case .serious: self = .serious
        case .critical: self = .critical
        @unknown default: self = .unknown
        }
    }
}

struct DisplayMetrics {
    /// 本 App FPS（CADisplayLink，真实测量）。
    let fps: Metric<Float>
    /// 全局 FPS：iOS 无公开 API（不可用，禁止用本 App 值冒充）。
    let globalFps: Metric<Float>
    let frameTimeMs: Metric<Float>
    let frameStdevMs: Metric<Float>
    let jankFrames: Metric<Int>
    let jankRatio30s: Metric<Float>
    let refreshRateHz: Metric<Float>
}

struct CpuMetrics {
    let usagePercent: Metric<Float>
    let perCoreUsage: Metric<[CoreUsage]>
    let perCoreFreqMHz: Metric<[CoreFreq]>
    let temperatureC: Metric<Float>
}

struct GpuMetrics {
    let loadPercent: Metric<Float>
    let freqMHz: Metric<Float>
    let temperatureC: Metric<Float>
}

struct BatteryMetrics {
    let temperatureC: Metric<Float>
    let currentMa: Metric<Float>
    let voltageV: Metric<Float>
    let levelPercent: Metric<Float>
    let chargingState: ChargingState
    /// 电池健康粗粒度：iOS 公开 API 仅 maximumCapacity 百分比（电池健康页口径）。
    let healthPercent: Metric<Float>
}

struct MemoryMetrics {
    let totalMb: Metric<Float>
    let usedMb: Metric<Float>
    let availableMb: Metric<Float>
    let usageRatio: Metric<Float>
}

struct ThermalMetrics {
    /// 机身温度：iOS 无公开传感器直读 API，仅热状态档位（见 README 降级说明）。
    let deviceTempC: Metric<Float>
    let status: ThermalStatus
}

struct DeviceSnapshot {
    let timestampMs: Int64
    let display: DisplayMetrics
    let cpu: CpuMetrics
    let gpu: GpuMetrics
    let battery: BatteryMetrics
    let memory: MemoryMetrics
    let thermal: ThermalMetrics

    /// 按指标 ID 取出对应 Metric<Float>（详情页/图表用；非 Float 指标返回 nil）。
    func metricOf(_ id: MetricId) -> Metric<Float>? {
        switch id {
        case .fps: return display.fps
        case .globalFps: return display.globalFps
        case .frameTime: return display.frameTimeMs
        case .frameStdev: return display.frameStdevMs
        case .jankRatio: return display.jankRatio30s
        case .refreshRate: return display.refreshRateHz
        case .cpuUsage: return cpu.usagePercent
        case .cpuTemp: return cpu.temperatureC
        case .gpuLoad: return gpu.loadPercent
        case .gpuTemp: return gpu.temperatureC
        case .batteryTemp: return battery.temperatureC
        case .batteryCurrent: return battery.currentMa
        case .batteryVoltage: return battery.voltageV
        case .batteryLevel: return battery.levelPercent
        case .batteryHealth: return battery.healthPercent
        case .memoryTotal: return memory.totalMb
        case .memoryUsed: return memory.usedMb
        case .memoryAvailable: return memory.availableMb
        case .memoryUsageRatio: return memory.usageRatio
        case .deviceTemp: return thermal.deviceTempC
        case .jankFrames, .cpuCoreUsage, .cpuCoreFreq, .gpuFreq, .perAppMemory:
            return nil // 非 Float 指标（Int/列表），详情页不展示
        }
    }

    /// 全部指标置不可用的初始快照（UI 显示"—"占位，绝不显示假数据）。
    static func empty(at ms: Int64 = 0) -> DeviceSnapshot {
        let at = TimeInterval(ms) / 1000.0
        let wait = { (reason: String) -> Metric<Float> in .unavailable(reason: reason, at: at) }
        return DeviceSnapshot(
            timestampMs: ms,
            display: DisplayMetrics(
                fps: wait("等待采样"),
                globalFps: .unavailable(reason: "iOS 无全局 FPS 公开 API", at: at),
                frameTimeMs: wait("等待采样"),
                frameStdevMs: wait("等待采样"),
                jankFrames: .unavailable(reason: "等待采样", at: at),
                jankRatio30s: wait("等待采样"),
                refreshRateHz: wait("等待采样")
            ),
            cpu: CpuMetrics(
                usagePercent: wait("等待采样"),
                perCoreUsage: .unavailable(reason: "等待采样", at: at),
                perCoreFreqMHz: .unavailable(reason: "iOS 无各核心频率公开 API", at: at),
                temperatureC: wait("等待采样")
            ),
            gpu: GpuMetrics(
                loadPercent: .unavailable(reason: "iOS 无 GPU 负载公开 API", at: at),
                freqMHz: .unavailable(reason: "iOS 无 GPU 频率公开 API", at: at),
                temperatureC: .unavailable(reason: "iOS 无 GPU 温度公开 API", at: at)
            ),
            battery: BatteryMetrics(
                temperatureC: .unavailable(reason: "iOS 无电池温度公开 API", at: at),
                currentMa: .unavailable(reason: "iOS 无电流公开 API", at: at),
                voltageV: .unavailable(reason: "iOS 无电压公开 API", at: at),
                levelPercent: wait("等待采样"),
                chargingState: .unknown,
                healthPercent: .unavailable(reason: "iOS 无电池健康百分比公开 API", at: at)
            ),
            memory: MemoryMetrics(
                totalMb: wait("等待采样"),
                usedMb: wait("等待采样"),
                availableMb: wait("等待采样"),
                usageRatio: wait("等待采样")
            ),
            thermal: ThermalMetrics(
                deviceTempC: .unavailable(reason: "iOS 无机身温度公开 API（仅热状态档位）", at: at),
                status: .unknown
            )
        )
    }
}
