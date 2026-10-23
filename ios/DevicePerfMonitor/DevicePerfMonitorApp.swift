//
//  DevicePerfMonitorApp.swift
//  DevicePerfMonitor
//
//  App 入口：单 Window SwiftUI 场景。
//  - 启动即开始采集（后台采集开关见设置页）；
//  - 退后台时隐藏悬浮窗并立即落盘历史（scenePhase 驱动）。
//

import SwiftUI

@main
struct DevicePerfMonitorApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("themeMode") private var themeModeRaw = ThemeMode.dark.rawValue
    // 1.0.8 起默认 true：回应用即自动挂出悬浮窗（用户「不需要手动打开」；
    // iOS 平台上限是应用内窗口，退后台仍会隐藏）
    @AppStorage("overlayEnabled") private var overlayEnabled = true

    var body: some Scene {
        WindowGroup {
            LiquidGlassTheme(mode: ThemeMode(rawValue: themeModeRaw) ?? .dark) {
                ContentView()
            }
        }
        .onChange(of: scenePhase) { phase in
            switch phase {
            case .active:
                MetricsRepository.shared.sampleOnce()
                // 回前台：设置开着就自动挂出（隐藏过的也在此恢复）
                if overlayEnabled {
                    FloatingWindowController.shared.show()
                }
            case .background:
                // 退后台：隐藏应用内悬浮窗 + 立即落盘历史
                FloatingWindowController.shared.hide()
                HistoryStore.shared.flush()
            default:
                break
            }
        }
    }
}

// MARK: - AppDelegate：通知授权预热（不强制）

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // 崩溃监测器须最先安装，才能捕获后续所有崩溃（含采集启动）
        CrashReporter.shared.install()
        // 展示上一次（信号类）崩溃记录，若有
        CrashReporter.shared.checkPendingCrash()
        MetricsRepository.shared.start()
        return true
    }
}
