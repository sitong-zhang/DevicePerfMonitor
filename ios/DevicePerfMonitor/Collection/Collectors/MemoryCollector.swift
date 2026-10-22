//
//  MemoryCollector.swift
//  DevicePerfMonitor
//
//  内存采集器：host_statistics64 / vm_statistics64（公开 mach API，始终可用）。
//
//  口径说明（与 Android 端一致）：
//  - 总量：ProcessInfo.physicalMemory（系统总 RAM）；
//  - 可用：(free + inactive) × 页大小 —— inactive 含可回收缓存，
//    与系统"内存"页口径一致；
//  - 已用 = 总量 - 可用；
//  - iOS 无后台应用内存明细公开 API（Android 需 root/Shizuku 读
//    dumpsys meminfo；iOS 平台限制更强）→ 常态 UNAVAILABLE。
//

import Foundation

final class MemoryCollector {

    func sample(now: TimeInterval) -> MemoryMetrics {
        var stats = vm_statistics64()
        var count = UInt32(MemoryLayout<vm_statistics64>.size / MemoryLayout<integer_t>.size)

        let result: kern_return_t = withUnsafeMutablePointer(to: &stats) { ptr in
            ptr.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { intPtr in
                host_statistics64(mach_host_self(), HOST_VM_INFO64, intPtr, &count)
            }
        }

        guard result == KERN_SUCCESS else {
            let reason = "host_statistics64 读取失败（kern_return=\(result)）"
            return MemoryMetrics(
                totalMb: .unavailable(reason: reason, at: now),
                usedMb: .unavailable(reason: reason, at: now),
                availableMb: .unavailable(reason: reason, at: now),
                usageRatio: .unavailable(reason: reason, at: now)
            )
        }

        var pageSize = vm_size_t()
        host_page_size(mach_host_self(), &pageSize)

        let totalBytes = Double(ProcessInfo.processInfo.physicalMemory)
        // free + inactive：inactive 为可回收缓存（口径与系统内存页一致）
        let availableBytes = Double(stats.free_count + stats.inactive_count) * Double(pageSize)
        let usedBytes = max(0, totalBytes - availableBytes)
        let ratio = totalBytes > 0 ? Float(usedBytes / totalBytes * 100) : nil

        return MemoryMetrics(
            totalMb: .available(Float(totalBytes / 1_048_576), at: now),
            usedMb: .available(Float(usedBytes / 1_048_576), at: now),
            availableMb: .available(Float(availableBytes / 1_048_576), at: now),
            usageRatio: Metric.make(ratio, at: now, unavailableReason: "内存统计异常")
        )
    }
}
