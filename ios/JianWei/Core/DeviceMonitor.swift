import Foundation
import UIKit

/// 设备指标采集。全部使用公开 API，在 App 内运行，不联网。
final class DeviceMonitor {
    static let shared = DeviceMonitor()

    private var displayLink: CADisplayLink?
    private var lastFrameTS = CFAbsoluteTimeGetCurrent()
    private var frameCount = 0
    private var fps: Double = 0

    private var prevLoad = host_cpu_load_info()
    private var hasPrev = false

    // MARK: - 帧率
    func startFPS() {
        lastFrameTS = CFAbsoluteTimeGetCurrent()
        frameCount = 0
        displayLink?.invalidate()
        let link = CADisplayLink(target: self, selector: #selector(tick(_:)))
        link.add(to: .main, forMode: .common)
        displayLink = link
    }

    func stopFPS() {
        displayLink?.invalidate()
        displayLink = nil
    }

    @objc private func tick(_ link: CADisplayLink) {
        frameCount += 1
        let now = CFAbsoluteTimeGetCurrent()
        let elapsed = now - lastFrameTS
        if elapsed >= 1 {
            fps = Double(frameCount) / elapsed
            frameCount = 0
            lastFrameTS = now
        }
    }

    // MARK: - 系统 CPU 占用（基于两次采样差值，近似瞬时）
    func cpuUsage() -> Double {
        var loadInfo = host_cpu_load_info()
        var numCpuInfo: mach_msg_type_number_t = 0
        let count = MemoryLayout<host_cpu_load_info>.stride / MemoryLayout<integer_t>.stride
        let result = withUnsafeMutablePointer(to: &loadInfo) {
            $0.withMemoryRebound(to: integer_t.self, capacity: count) {
                host_statistics(mach_host_self(), HOST_CPU_LOAD_INFO, $0, &numCpuInfo)
            }
        }
        guard result == KERN_SUCCESS else { return 0 }

        if !hasPrev {
            prevLoad = loadInfo
            hasPrev = true
            return 0
        }
        let userD  = Double(loadInfo.cpu_ticks.0 - prevLoad.cpu_ticks.0)
        let sysD   = Double(loadInfo.cpu_ticks.1 - prevLoad.cpu_ticks.1)
        let idleD  = Double(loadInfo.cpu_ticks.2 - prevLoad.cpu_ticks.2)
        let niceD  = Double(loadInfo.cpu_ticks.3 - prevLoad.cpu_ticks.3)
        let totalD = userD + sysD + idleD + niceD
        prevLoad = loadInfo
        guard totalD > 0 else { return 0 }
        return min(100, max(0, (sysD + userD) / totalD * 100))
    }

    // MARK: - 内存
    func memoryUsage() -> (usedMB: Double, totalMB: Double) {
        let total = Double(ProcessInfo.processInfo.physicalMemory) / 1_048_576.0
        var info = mach_task_basic_info()
        var count = mach_msg_type_number_t(MemoryLayout<mach_task_basic_info>.stride / MemoryLayout<natural_t>.stride)
        let result = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: 1) {
                task_info(mach_task_self_, task_flavor_t(MACH_TASK_BASIC_INFO), $0, &count)
            }
        }
        let used = result == KERN_SUCCESS ? Double(info.resident_size) / 1_048_576.0 : 0
        return (used, total)
    }

    // MARK: - 电池
    func battery() -> (level: Double, state: UIDevice.BatteryState) {
        return (Double(UIDevice.current.batteryLevel), UIDevice.current.batteryState)
    }

    // MARK: - 屏幕刷新率
    func maxRefreshRate() -> Double {
        return Double(UIScreen.main.maximumFramesPerSecond)
    }

    func currentFPS() -> Double { fps }

    // MARK: - 存储
    func storage() -> (usedGB: Double, totalGB: Double) {
        let url = URL(fileURLWithPath: NSHomeDirectory())
        do {
            let values = try url.resourceValues(forKeys: [.volumeTotalCapacityKey, .volumeAvailableCapacityKey])
            let total = Double(values.volumeTotalCapacity ?? 0) / 1_073_741_824.0
            let free  = Double(values.volumeAvailableCapacity ?? 0) / 1_073_741_824.0
            return (max(0, total - free), total)
        } catch {
            return (0, 0)
        }
    }

    // MARK: - 汇总一次采样
    func currentSample() -> MetricSample {
        let mem = memoryUsage()
        let bat = battery()
        let sto = storage()
        return MetricSample(
            id: UUID(),
            date: Date(),
            cpu: cpuUsage(),
            memoryUsedMB: mem.usedMB,
            memoryTotalMB: mem.totalMB,
            battery: bat.level,
            fps: currentFPS(),
            maxFps: maxRefreshRate(),
            storageUsedGB: sto.usedGB,
            storageTotalGB: sto.totalGB
        )
    }
}
