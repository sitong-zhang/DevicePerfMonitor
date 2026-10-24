//
//  DevicePerfMonitorApp.swift
//  DevicePerfMonitor
//
//  App 入口：单 Window SwiftUI 场景。
//  - 启动即开始采集（后台采集开关见设置页）；
//  - 退后台时隐藏悬浮窗并立即落盘历史（scenePhase 驱动）；
//  - 1.0.10 起监测形态按平台能力分流：支持实时活动的机型只用
//    灵动岛/锁屏，不挂应用内悬浮窗；不支持的机型才回退悬浮窗；
//  - 1.0.11 起退后台提交 BGAppRefreshTask：iOS 挂起 App 后唯一的
//    正规刷新通道，唤醒时采样一次把新数据推进实时活动。
//

import SwiftUI

@main
struct DevicePerfMonitorApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("themeMode") private var themeModeRaw = ThemeMode.dark.rawValue
    // 1.0.8 起默认 true：回应用即自动挂出悬浮窗（用户「不需要手动打开」；
    // iOS 平台上限是应用内窗口，退后台仍会隐藏）。
    // 1.0.10 起仅在不支持实时活动的机型上生效——支持实时活动的机型
    // 监测形态只用灵动岛/锁屏，设置里不再出现悬浮窗选项。
    @AppStorage("overlayEnabled") private var overlayEnabled = true
    // 1.0.10：支持实时活动的机型，监测数据直接上灵动岛/锁屏，默认开
    @AppStorage("liveActivity") private var liveActivity = true
    // 1.0.11：退后台按系统调度周期性唤醒采样（iOS 挂起后唯一正规刷新通道）
    @AppStorage("backgroundCollection") private var backgroundCollection = true

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
                if AlertManager.isSupported {
                    // 1.0.10：支持实时活动的机型只用灵动岛/锁屏，
                    // 不挂应用内悬浮窗（用户：「支持的话就直接没有悬浮窗这个选项」）
                    FloatingWindowController.shared.hide()
                    if liveActivity {
                        AlertManager.shared.startLiveActivity()
                    }
                } else if overlayEnabled {
                    // 回前台：设置开着就自动挂出（隐藏过的也在此恢复）
                    FloatingWindowController.shared.show()
                }
            case .background:
                // 退后台：隐藏应用内悬浮窗 + 立即落盘历史
                FloatingWindowController.shared.hide()
                HistoryStore.shared.flush()
                // 1.0.11：提交后台刷新任务（开关关着就不提交）。
                // 唤醒时采样一次，经采集中枢把新数据推进实时活动——
                // iOS 挂起 App 后没有别的正规通道，见 BackgroundRefresh.swift 注释
                if backgroundCollection {
                    BackgroundRefresh.scheduleNext()
                }
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
        // 1.0.11：注册后台刷新任务（注册 ≠ 调度，真正提交在退后台时）。
        // 唤醒时走 sampleOnce → evaluate → 实时活动更新，不需要 UI
        BackgroundRefresh.register()
        // 1.0.10：支持实时活动的机型，监测数据直接上灵动岛/锁屏，
        // 冷启动即自动申请（内部优先收养系统里仍在运行的实例，不重复创建）。
        // 鸿蒙 6 实况窗需人工审核申请，安卓 APK 侧只做悬浮窗，走不到这里。
        if AlertManager.isSupported {
            let liveOn = UserDefaults.standard.object(forKey: "liveActivity") as? Bool ?? true
            if liveOn {
                AlertManager.shared.startLiveActivity()
            }
        }
        return true
    }
}
