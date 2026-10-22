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

    var body: some Scene {
        WindowGroup {
            ContentView()
                .liquidGlassTheme(mode: ThemeMode(rawValue: themeModeRaw) ?? .dark)
        }
        .onChange(of: scenePhase) { phase in
            switch phase {
            case .active:
                MetricsRepository.shared.sampleOnce()
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
        MetricsRepository.shared.start()
        return true
    }
}
