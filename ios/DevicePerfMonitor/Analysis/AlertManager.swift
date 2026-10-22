//
//  AlertManager.swift
//  DevicePerfMonitor
//
//  告警管理（对应 Android 端 analysis/AlertManager.kt）：
//  1. 悬浮窗变红：OverlayView 订阅 alertState；
//  2. 通知栏：本地通知（UNUserNotificationCenter，需用户授权；
//     未授权时静默降级，其余功能正常）；
//  3. 悬浮弹窗：应用内悬浮窗顶部告警条；
//  4. 实时活动（Live Activity，iOS 16.1+）：持续更新核心指标，
//     系统不支持时静默降级。
//
//  阈值与 settings 联动（温度阈值默认 45℃，可由设置页调整）。
//

import Foundation
import UserNotifications
import ActivityKit

@MainActor
final class AlertManager: ObservableObject {

    static let shared = AlertManager()

    /// 当前是否处于告警态（悬浮窗变红 + 弹窗）。
    @Published private(set) var alertActive = false

    /// 最近一次告警文案（悬浮弹窗显示）。
    @Published private(set) var alertMessage: String?

    /// 实时活动是否正在运行。
    @Published private(set) var liveActivityRunning = false

    private var activity: Activity<MonitorActivityAttributes>?
    private var lastNotifyMs: Int64 = 0
    /// 通知最小间隔（避免刷屏）：60s。
    private let notifyCooldownMs: Int64 = 60_000

    private init() {}

    // MARK: - 配置

    /// 温度阈值（℃）。iOS 无机身温度 API → 阈值改判热状态档位：
    /// serious 视为超阈值。
    var temperatureThreshold: Double = 45

    // MARK: - 采样驱动

    /// 每次采样后调用（主线程）。
    func evaluate(snapshot: DeviceSnapshot, overlay: OverlayData?) {
        // 告警条件：系统热状态 serious/critical（iOS 温度替代信号）
        // 或本 App 帧时间标准差 > 12ms（严重卡顿）
        let thermalAlert = snapshot.thermal.status == .serious || snapshot.thermal.status == .critical
        let frameStdev = snapshot.display.frameStdevMs.value ?? 0
        let frameAlert = frameStdev > 12

        let active = thermalAlert || frameAlert
        if active != alertActive {
            alertActive = active
            alertMessage = active ? alertText(thermal: thermalAlert, frame: frameAlert) : nil
            if active {
                notifyIfNeeded(thermal: thermalAlert, frame: frameAlert, nowMs: snapshot.timestampMs)
            }
        }

        updateLiveActivity(snapshot: snapshot, overlay: overlay)
    }

    private func alertText(thermal: Bool, frame: Bool) -> String {
        if thermal && frame { return "系统过热且帧时间不稳定" }
        if thermal { return "系统热状态异常" }
        return "帧时间不稳定"
    }

    // MARK: - 本地通知

    func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, error in
            #if DEBUG
            if let error { print("[AlertManager] 通知授权失败: \(error)") }
            #endif
            _ = granted // 未授权时静默降级：仅 App 内告警，不发通知
        }
    }

    private func notifyIfNeeded(thermal: Bool, frame: Bool, nowMs: Int64) {
        guard nowMs - lastNotifyMs >= notifyCooldownMs else { return }
        lastNotifyMs = nowMs

        let content = UNMutableNotificationContent()
        content.title = "设备性能告警"
        content.body = thermal && frame ? "系统过热且帧时间不稳定"
            : thermal ? "系统热状态异常，建议降低负载"
            : "帧时间不稳定，建议清理后台"
        content.sound = .default

        let request = UNNotificationRequest(
            identifier: "perf-alert-\(nowMs)",
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request)
    }

    // MARK: - 实时活动（Live Activity）

    /// 启动实时活动（设置页开关；系统不支持时返回 false 并静默降级）。
    func startLiveActivity() -> Bool {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return false }
        guard activity == nil else { return true }

        let attributes = MonitorActivityAttributes(startedAt: Date())
        let state = MonitorActivityAttributes.ContentState()
        do {
            activity = try Activity.request(attributes: attributes, content: .init(state: state, staleDate: nil))
            liveActivityRunning = true
            return true
        } catch {
            #if DEBUG
            print("[AlertManager] 实时活动启动失败: \(error)")
            #endif
            return false
        }
    }

    func stopLiveActivity() {
        Task {
            await activity?.end(nil, dismissalPolicy: .immediate)
            activity = nil
            liveActivityRunning = false
        }
    }

    private func updateLiveActivity(snapshot: DeviceSnapshot, overlay: OverlayData?) {
        guard let activity else { return }
        let fps = overlay?.fps.value ?? snapshot.display.fps.value
        let cpu = overlay?.cpuUsage.value ?? snapshot.cpu.usagePercent.value
        let memory = overlay?.memoryRatio.value ?? snapshot.memory.usageRatio.value

        let state = MonitorActivityAttributes.ContentState(
            fps: fps.map { Double($0) },
            cpuUsage: cpu.map { Double($0) },
            memoryRatio: memory.map { Double($0) },
            alert: alertActive,
            timestampMs: snapshot.timestampMs
        )
        Task {
            await activity.update(.init(state: state, staleDate: Date().addingTimeInterval(60)))
        }
    }
}
