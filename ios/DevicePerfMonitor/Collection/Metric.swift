//
//  Metric.swift
//  DevicePerfMonitor
//
//  指标三态模型（与 Android 端 core 模块同构）：
//  - available        真实测量值（含时间戳）
//  - unavailable      不可用 + 原因（禁止假数据/估算值冒充）
//  - needsPermission  需授权 + 授权动作（如 Shizuku —— iOS 端仅 entheavy 场景保留位）
//
//  硬性约定（README「API-权限-可用性-降级」对照表同步）：
//  1. 每次采样 < 50ms，由 MetricsRepository 在后台队列调度；
//  2. 任何读取失败必须降级为 unavailable / needsPermission，禁止抛异常、
//     禁止返回写死值或估算值冒充真实数据。
//

import Foundation

// MARK: - 三态

indirect enum Metric<Value> {
    case available(Value, at: TimeInterval)
    case unavailable(reason: String, at: TimeInterval)
    case needsPermission(action: PermissionAction, reason: String, at: TimeInterval)

    var state: MetricState<Value> {
        switch self {
        case .available(let value, _): return .available(value)
        case .unavailable(let reason, _): return .unavailable(reason)
        case .needsPermission(let action, let reason, _): return .needsPermission(action, reason: reason)
        }
    }

    var timestampMs: Int64 {
        switch self {
        case .available(_, let at): return Int64(at * 1000)
        case .unavailable(_, let at): return Int64(at * 1000)
        case .needsPermission(_, _, let at): return Int64(at * 1000)
        }
    }

    /// 可用值（不可用时 nil）。
    var value: Value? {
        if case .available(let v, _) = self { return v }
        return nil
    }

    static func make(_ value: Value?, at: TimeInterval, unavailableReason: String) -> Metric<Value> {
        if let value { return .available(value, at: at) }
        return .unavailable(reason: unavailableReason, at: at)
    }
}

enum MetricState<Value> {
    case available(Value)
    case unavailable(String)
    case needsPermission(PermissionAction, reason: String)
}

/// 需要授权时的动作（iOS 端无 Shizuku，保留枚举以与 Android 模型对齐；
/// 目前 iOS 无需要授权的指标 —— 全部公开 API，见 README 对照表）。
enum PermissionAction: String, CaseIterable {
    case shizuku
    case usageStats
    case notification
}

// MARK: - 指标定义

enum MetricCategory: String, CaseIterable {
    case display = "显示系统"
    case cpu = "处理器"
    case gpu = "图形"
    case battery = "电池"
    case memory = "内存"
    case thermal = "温度"
}

/// 指标唯一标识 + 展示元数据（与 Android 端 MetricId 对齐）。
enum MetricId: String, CaseIterable {
    case fps = "FPS"
    case globalFps = "GLOBAL_FPS"
    case frameTime = "FRAME_TIME"
    case frameStdev = "FRAME_STDEV"
    case jankFrames = "JANK_FRAMES"
    case jankRatio = "JANK_RATIO"
    case refreshRate = "REFRESH_RATE"

    case cpuUsage = "CPU_USAGE"
    case cpuCoreUsage = "CPU_CORE_USAGE"
    case cpuCoreFreq = "CPU_CORE_FREQ"
    case cpuTemp = "CPU_TEMP"

    case gpuLoad = "GPU_LOAD"
    case gpuFreq = "GPU_FREQ"
    case gpuTemp = "GPU_TEMP"

    case batteryTemp = "BATTERY_TEMP"
    case batteryCurrent = "BATTERY_CURRENT"
    case batteryVoltage = "BATTERY_VOLTAGE"
    case batteryLevel = "BATTERY_LEVEL"
    case batteryHealth = "BATTERY_HEALTH"

    case memoryTotal = "MEMORY_TOTAL"
    case memoryUsed = "MEMORY_USED"
    case memoryAvailable = "MEMORY_AVAILABLE"
    case memoryUsageRatio = "MEMORY_USAGE_RATIO"
    case perAppMemory = "PER_APP_MEMORY"

    case deviceTemp = "DEVICE_TEMP"

    var label: String {
        switch self {
        case .fps: return "实时 FPS"
        case .globalFps: return "全局 FPS"
        case .frameTime: return "帧时间"
        case .frameStdev: return "帧时间标准差"
        case .jankFrames: return "掉帧计数"
        case .jankRatio: return "掉帧率"
        case .refreshRate: return "屏幕刷新率"
        case .cpuUsage: return "CPU 使用率"
        case .cpuCoreUsage: return "各核心使用率"
        case .cpuCoreFreq: return "各核心频率"
        case .cpuTemp: return "CPU 温度"
        case .gpuLoad: return "GPU 负载"
        case .gpuFreq: return "GPU 频率"
        case .gpuTemp: return "GPU 温度"
        case .batteryTemp: return "电池温度"
        case .batteryCurrent: return "电池电流"
        case .batteryVoltage: return "电池电压"
        case .batteryLevel: return "电量"
        case .batteryHealth: return "电池健康"
        case .memoryTotal: return "总内存"
        case .memoryUsed: return "已用内存"
        case .memoryAvailable: return "可用内存"
        case .memoryUsageRatio: return "内存占用率"
        case .perAppMemory: return "后台应用内存"
        case .deviceTemp: return "机身温度"
        }
    }

    /// 悬浮窗等窄场景用的短标签。
    var shortLabel: String {
        switch self {
        case .fps: return "帧率"
        case .refreshRate: return "刷新率"
        case .memoryUsageRatio: return "内存"
        case .deviceTemp: return "温度"
        default: return label
        }
    }

    var unit: String {
        switch self {
        case .fps, .globalFps: return "fps"
        case .frameTime, .frameStdev: return "ms"
        case .jankFrames: return "帧"
        case .jankRatio: return "%"
        case .refreshRate: return "Hz"
        case .cpuUsage, .cpuCoreUsage, .gpuLoad: return "%"
        case .cpuCoreFreq, .gpuFreq: return "MHz"
        case .cpuTemp, .gpuTemp, .batteryTemp, .deviceTemp: return "℃"
        case .batteryCurrent: return "mA"
        case .batteryVoltage: return "V"
        case .batteryLevel, .batteryHealth, .memoryUsageRatio: return "%"
        case .memoryTotal, .memoryUsed, .memoryAvailable, .perAppMemory: return "MB"
        }
    }

    var category: MetricCategory {
        switch self {
        case .fps, .globalFps, .frameTime, .frameStdev, .jankFrames, .jankRatio, .refreshRate:
            return .display
        case .cpuUsage, .cpuCoreUsage, .cpuCoreFreq, .cpuTemp:
            return .cpu
        case .gpuLoad, .gpuFreq, .gpuTemp:
            return .gpu
        case .batteryTemp, .batteryCurrent, .batteryVoltage, .batteryLevel, .batteryHealth:
            return .battery
        case .memoryTotal, .memoryUsed, .memoryAvailable, .memoryUsageRatio, .perAppMemory:
            return .memory
        case .deviceTemp:
            return .thermal
        }
    }

    var decimals: Int {
        switch self {
        case .fps, .globalFps, .jankFrames, .refreshRate, .cpuUsage, .cpuCoreUsage,
             .gpuLoad, .batteryCurrent, .batteryLevel, .batteryHealth,
             .memoryTotal, .memoryUsed, .memoryAvailable, .memoryUsageRatio, .perAppMemory:
            return 0
        case .frameTime, .frameStdev, .jankRatio, .cpuTemp, .gpuTemp, .batteryTemp, .deviceTemp:
            return 1
        case .cpuCoreFreq, .gpuFreq:
            return 0
        case .batteryVoltage:
            return 2
        }
    }

    var colorKey: String {
        switch self {
        case .fps, .globalFps, .refreshRate, .cpuUsage, .gpuLoad, .memoryTotal: return "accent"
        case .frameTime, .frameStdev, .cpuCoreUsage, .cpuCoreFreq, .gpuFreq, .batteryVoltage,
             .memoryAvailable, .perAppMemory: return "accentSecondary"
        case .jankFrames, .jankRatio, .batteryCurrent, .memoryUsed, .memoryUsageRatio: return "warning"
        case .cpuTemp, .gpuTemp, .batteryTemp, .deviceTemp: return "danger"
        case .batteryLevel, .batteryHealth, .memoryAvailable: return "success"
        }
    }

    /// 是否纳入 30 分钟秒级历史（与 Android 端 historyTracked 对齐）。
    var historyTracked: Bool {
        switch self {
        case .fps, .globalFps, .jankRatio, .cpuUsage, .cpuTemp, .gpuLoad, .gpuTemp,
             .batteryTemp, .batteryCurrent, .batteryLevel, .memoryUsed, .memoryUsageRatio, .deviceTemp:
            return true
        default:
            return false
        }
    }
}

// MARK: - 可视化模式（与 Android 端 MetricViewMode 对齐）

/// 每个指标都能单独切换显示模式，选择会被记住（UserDefaults）。
/// 参考 OpenMonitor / 系统监控类应用常见的几种读法：
/// - 数字：大号等宽数字，看瞬时值最准；
/// - 波形：线条上下波动（sparkline），看趋势和抖动；
/// - 环形：0~100% 有界指标（占用率）用环形仪表最直观；
/// - 柱状：离散采样条，看分布和突变。
enum MetricViewMode: String, CaseIterable {
    case number = "数字"
    case wave = "波形"
    case ring = "环形"
    case bars = "柱状"

    /// 环形只对 0~100 有界指标开放，其余回落波形（避免把没有上限的值硬塞进圆环）。
    static func effective(_ mode: MetricViewMode, id: MetricId) -> MetricViewMode {
        if mode == .ring && id != .memoryUsageRatio { return .wave }
        return mode
    }
}

/// 模式选择的本地持久化（对应 Android 端 SettingsRepository.metricViewModes）。
enum ViewModeStore {
    private static let prefix = "metricViewMode."

    static func mode(for id: MetricId) -> MetricViewMode {
        let raw = UserDefaults.standard.string(forKey: prefix + id.rawValue)
        return raw.flatMap(MetricViewMode.init(rawValue:)) ?? .number
    }

    static func set(_ mode: MetricViewMode, for id: MetricId) {
        UserDefaults.standard.set(mode.rawValue, forKey: prefix + id.rawValue)
    }
}
