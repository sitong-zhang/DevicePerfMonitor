//
//  CpuCollector.swift
//  DevicePerfMonitor
//
//  CPU 采集器：host_processor_info(PROCESSOR_CPU_LOAD_INFO)（公开 mach API）。
//
//  三态说明：
//  - 整机使用率 / 各核心使用率：处理器时间片差分（公开 API，始终可用；
//    CPU 负载为累计值，必须两次采样求差，首次采样返回 UNAVAILABLE）；
//  - 各核心频率：iOS 无公开 API（Android 可读 /sys/devices/system/cpu；
//    iOS 沙箱不可读 sysfs）→ 常态 UNAVAILABLE；
//  - CPU 温度：iOS 无公开 API（Android 可读 thermal zone）→ 常态 UNAVAILABLE。
//

import Foundation

final class CpuCollector {

    /// 每颗处理器的时间片快照 [user, system, idle, nice]（累计 ticks）。
    private struct ProcessorTicks {
        let user: UInt32
        let system: UInt32
        let idle: UInt32
        let nice: UInt32
    }

    private var previous: [ProcessorTicks]? = nil

    func sample(now: TimeInterval) -> CpuMetrics {
        guard let current = readTicks(), !current.isEmpty else {
            let reason = "host_processor_info 读取失败"
            return CpuMetrics(
                usagePercent: .unavailable(reason: reason, at: now),
                perCoreUsage: .unavailable(reason: reason, at: now),
                perCoreFreqMHz: .unavailable(reason: "iOS 无各核心频率公开 API", at: now),
                temperatureC: .unavailable(reason: "iOS 无 CPU 温度公开 API", at: now)
            )
        }

        defer { previous = current }

        guard let previous else {
            // 首次采样：无基线，无法差分 → UNAVAILABLE（下次采样起可用）
            return CpuMetrics(
                usagePercent: .unavailable(reason: "等待第二次采样（CPU 负载需差分）", at: now),
                perCoreUsage: .unavailable(reason: "等待第二次采样（CPU 负载需差分）", at: now),
                perCoreFreqMHz: .unavailable(reason: "iOS 无各核心频率公开 API", at: now),
                temperatureC: .unavailable(reason: "iOS 无 CPU 温度公开 API", at: now)
            )
        }

        var perCore: [CoreUsage] = []
        var totalUsed: Double = 0
        var totalAll: Double = 0

        for (index, cur) in current.enumerated() {
            guard index < previous.count else { continue }
            let prev = previous[index]
            let used = Double(cur.user + cur.system + cur.nice) - Double(prev.user + prev.system + prev.nice)
            let all = used + Double(cur.idle) - Double(prev.idle)
            guard all > 0 else { continue }
            let percent = Float(max(0, min(100, used / all * 100)))
            perCore.append(CoreUsage(index: index, percent: percent))
            totalUsed += used
            totalAll += all
        }

        let totalPercent = totalAll > 0 ? Float(max(0, min(100, totalUsed / totalAll * 100))) : nil

        return CpuMetrics(
            usagePercent: Metric.make(totalPercent, at: now, unavailableReason: "CPU 负载差分异常"),
            perCoreUsage: perCore.isEmpty
                ? .unavailable(reason: "CPU 负载差分异常", at: now)
                : .available(perCore, at: now),
            perCoreFreqMHz: .unavailable(reason: "iOS 无各核心频率公开 API", at: now),
            temperatureC: .unavailable(reason: "iOS 无 CPU 温度公开 API", at: now)
        )
    }

    private func readTicks() -> [ProcessorTicks]? {
        var processorCount: natural_t = 0
        var processorInfo: processor_info_array_t? = nil
        var msgCount: mach_msg_type_number_t = 0

        let result = host_processor_info(
            mach_host_self(),
            PROCESSOR_CPU_LOAD_INFO,
            &processorCount,
            &processorInfo,
            &msgCount
        )

        guard result == KERN_SUCCESS, let info = processorInfo else { return nil }
        defer {
            let size = vm_size_t(msgCount) * vm_size_t(MemoryLayout<integer_t>.stride)
            vm_deallocate(mach_task_self_, vm_address_t(bitPattern: info), size)
        }

        // CPU_LOAD_INFO 每处理器 4 个 integer_t：user / system / idle / nice
        var ticks: [ProcessorTicks] = []
        for cpu in 0..<Int(processorCount) {
            let base = cpu * 4
            guard base + 3 < Int(msgCount) else { break }
            ticks.append(ProcessorTicks(
                user: UInt32(bitPattern: info[base]),
                system: UInt32(bitPattern: info[base + 1]),
                idle: UInt32(bitPattern: info[base + 2]),
                nice: UInt32(bitPattern: info[base + 3])
            ))
        }
        return ticks.isEmpty ? nil : ticks
    }
}
