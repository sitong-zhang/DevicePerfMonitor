//
//  SettingsScreen.swift
//  DevicePerfMonitor
//
//  设置页（对应 Android 端 ui/screen/SettingsScreen.kt）：
//  - 采集：后台采集开关 + 刷新模式（实时/均衡/省电）+ 离线说明；
//  - 监测悬浮形态：1.0.13 起用户自选——灵动岛/锁屏（实时活动）或
//    应用内悬浮窗（含仿灵动岛态：紧凑药丸 + 单击展开）；不支持的
//    机型只能悬浮窗；
//  - 告警：通知开关；
//  - 主题：深色/浅色/跟随系统；
//  - 关于：版本 + 依赖许可证 + 降级说明入口。
//

import SwiftUI
import ActivityKit
import UserNotifications

struct SettingsScreen: View {
    @Environment(\.colors) private var colors
    @AppStorage("refreshMode") private var refreshModeRaw = RefreshMode.realtime.rawValue
    // 1.0.8 起默认 true：回应用自动挂出悬浮窗，不需要手动打开
    @AppStorage("overlayEnabled") private var overlayEnabled = true
    @AppStorage("alertNotification") private var alertNotification = true
    // 1.0.10：支持实时活动的机型监测形态默认灵动岛/锁屏
    @AppStorage("liveActivity") private var liveActivity = true
    // 1.0.13：监测形态自选（覆盖 1.0.10 的「支持灵动岛就不给悬浮窗选项」）
    // liveActivity = 灵动岛/锁屏；overlay = 应用内悬浮窗
    @AppStorage("monitorMode") private var monitorMode = "liveActivity"
    // 1.0.13：悬浮窗默认形态三选一（island 灵动岛 / card 卡片 / numbers 纯数字），
    // 取代旧的 overlayMiniMode 二值开关；默认 island
    @AppStorage("overlayDefaultMode") private var overlayDefaultMode = "island"
    @AppStorage("themeMode") private var themeModeRaw = ThemeMode.dark.rawValue
    @AppStorage("backgroundCollection") private var backgroundCollection = true
    /// 悬浮窗窗口透明度 0.4~1.0（1.0.8 起接线到 window.alpha）。
    @AppStorage("overlayOpacity") private var overlayOpacity = 1.0

    @StateObject private var viewModel = SettingsViewModel()

    private var refreshMode: RefreshMode {
        RefreshMode(rawValue: refreshModeRaw) ?? .realtime
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DesignTokens.Spacing.s12) {
                Text("设置")
                    .font(Typography.titleLarge(.semibold))
                    .foregroundStyle(colors.textPrimary)

                // ---- 采集 ----
                GlassSectionTitle(text: "采集")
                GlassPanel {
                    VStack(alignment: .leading, spacing: DesignTokens.Spacing.s12) {
                        ToggleRow(title: "后台采集", subtitle: "退后台后按系统调度周期性唤醒采样、刷新灵动岛数据（iOS 不允许后台常驻，唤醒时机由系统决定，通常十几分钟到几小时）", isOn: $backgroundCollection)
                        VStack(alignment: .leading, spacing: DesignTokens.Spacing.s8) {
                            Text("刷新模式")
                                .font(Typography.labelLarge(.medium))
                                .foregroundStyle(colors.textSecondary)
                            GlassTabs(
                                options: RefreshMode.allCases.map { $0.rawValue },
                                selectedIndex: Binding(
                                    get: { RefreshMode.allCases.firstIndex(of: refreshMode) ?? 0 },
                                    set: { refreshModeRaw = RefreshMode.allCases[$0].rawValue }
                                )
                            )
                        }
                        Text("所有数据仅存本地沙盒，不联网、不上传。")
                            .font(Typography.labelMedium())
                            .foregroundStyle(colors.textSecondary)
                    }
                }

                // ---- 监测形态（1.0.13 起用户自选）----
                // 1.0.10 曾按平台能力分流：支持实时活动的机型不出现悬浮窗选项。
                // 1.0.13 放开——灵动岛退后台即冻结，用户要悬浮窗（仿灵动岛态）
                // 扛前台实时显示，故两种形态都由用户自己选。
                GlassSectionTitle(text: "监测形态")
                GlassPanel {
                    VStack(alignment: .leading, spacing: DesignTokens.Spacing.s12) {
                        if AlertManager.isSupported {
                            VStack(alignment: .leading, spacing: DesignTokens.Spacing.s8) {
                                Text("监测形态")
                                    .font(Typography.labelLarge(.medium))
                                    .foregroundStyle(colors.textSecondary)
                                GlassTabs(
                                    options: ["灵动岛 / 锁屏", "应用内悬浮窗"],
                                    selectedIndex: Binding(
                                        get: { monitorMode == "overlay" ? 1 : 0 },
                                        set: { monitorMode = $0 == 0 ? "liveActivity" : "overlay" }
                                    )
                                )
                            }
                        }
                        // 悬浮窗设置组：选了悬浮窗，或本机不支持实时活动（只能悬浮窗）
                        if monitorMode == "overlay" || !AlertManager.isSupported {
                            VStack(alignment: .leading, spacing: DesignTokens.Spacing.s12) {
                                ToggleRow(title: "应用内悬浮窗", subtitle: "回应用自动挂出实时指标小窗（仅在应用内可见）", isOn: $overlayEnabled)
                                VStack(alignment: .leading, spacing: DesignTokens.Spacing.s8) {
                                    Text("悬浮窗形态")
                                        .font(Typography.labelLarge(.medium))
                                        .foregroundStyle(colors.textSecondary)
                                    GlassTabs(
                                        options: ["灵动岛", "卡片", "纯数字"],
                                        selectedIndex: Binding(
                                            get: { overlayDefaultMode == "numbers" ? 2 : (overlayDefaultMode == "card" ? 1 : 0) },
                                            set: { overlayDefaultMode = ["island", "card", "numbers"][$0] }
                                        )
                                    )
                                }
                                GlassSlider(
                                    value: $overlayOpacity,
                                    range: 0.4...1.0,
                                    label: "悬浮窗不透明度",
                                    valueText: "\(Int((overlayOpacity * 100).rounded()))%"
                                )
                                Text("iOS 无全局悬浮窗权限，悬浮窗仅本应用内显示；灵动岛态单击展开 / 收起。")
                                    .font(Typography.labelMedium())
                                    .foregroundStyle(colors.textSecondary)
                            }
                        }
                        // 实时活动设置组：支持灵动岛且选了它
                        if AlertManager.isSupported && monitorMode != "overlay" {
                            VStack(alignment: .leading, spacing: DesignTokens.Spacing.s12) {
                                ToggleRow(
                                    title: "实时活动（灵动岛/锁屏）",
                                    subtitle: "监测数据直接显示在灵动岛与锁屏，打开应用即自动开启",
                                    isOn: $liveActivity
                                )
                                Text("退后台后系统只给有限运行时，App 挂起后数据停留在最后一次采样。iOS 无机身温度公开 API，灵动岛不显示温度。")
                                    .font(Typography.labelMedium())
                                    .foregroundStyle(colors.textSecondary)
                            }
                        }
                    }
                }

                // ---- 告警 ----
                GlassSectionTitle(text: "告警")
                GlassPanel {
                    VStack(alignment: .leading, spacing: DesignTokens.Spacing.s12) {
                        ToggleRow(
                            title: "告警通知",
                            subtitle: "温度/帧时间异常时发送本地通知",
                            isOn: $alertNotification
                        )
                        if AlertManager.shared.alertActive {
                            Label("当前处于告警态", systemImage: "exclamationmark.triangle.fill")
                                .font(Typography.labelMedium(.medium))
                                .foregroundStyle(colors.danger)
                        }
                    }
                }

                // ---- 主题 ----
                GlassSectionTitle(text: "主题")
                GlassPanel {
                    VStack(alignment: .leading, spacing: DesignTokens.Spacing.s8) {
                        GlassTabs(
                            options: ThemeMode.allCases.map { $0.label },
                            selectedIndex: Binding(
                                get: { ThemeMode.allCases.firstIndex(of: ThemeMode(rawValue: themeModeRaw) ?? .dark) ?? 0 },
                                set: { themeModeRaw = ThemeMode.allCases[$0].rawValue }
                            )
                        )
                    }
                }

                // ---- 关于 ----
                GlassSectionTitle(text: "关于")
                GlassPanel {
                    VStack(alignment: .leading, spacing: DesignTokens.Spacing.s8) {
                        // 版本号读 Bundle：与 CFBundleShortVersionString 永远一致
                        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—"
                        Text("DevicePerfMonitor iOS \(version)")
                            .font(Typography.bodyLarge())
                            .foregroundStyle(colors.textPrimary)
                        Text("图表：danielgindi/Charts（Apache-2.0）；字体：Inter / JetBrains Mono（OFL-1.1）；配色：Catppuccin Mocha / Latte（MIT）。")
                            .font(Typography.labelMedium())
                            .foregroundStyle(colors.textSecondary)
                    }
                }
            }
            .padding(.horizontal, DesignTokens.Spacing.s16)
            .padding(.top, DesignTokens.Spacing.s32)
            .padding(.bottom, DesignTokens.BottomBar.height + DesignTokens.BottomBar.marginBottom + 16) // 浮动胶囊底栏占位
        }
        .background(colors.background.ignoresSafeArea())
        .onChange(of: refreshModeRaw) { newValue in
            MetricsRepository.shared.setRefreshMode(RefreshMode(rawValue: newValue) ?? .realtime)
        }
        .onChange(of: backgroundCollection) { newValue in
            // 1.0.11：这个开关以前只存 UserDefaults、没有任何代码读（死开关），
            // iOS 也没声明 UIBackgroundModes——现在真正接线到 BGTaskScheduler
            newValue ? BackgroundRefresh.scheduleNext() : BackgroundRefresh.cancel()
        }
        .onChange(of: overlayEnabled) { newValue in
            // 1.0.13：仅悬浮窗模式响应（灵动岛模式下不挂窗）
            guard monitorMode == "overlay" || !AlertManager.isSupported else { return }
            newValue ? FloatingWindowController.shared.show() : FloatingWindowController.shared.hide()
        }
        .onChange(of: overlayOpacity) { _ in
            FloatingWindowController.shared.applyOpacity()
        }
        .onChange(of: overlayDefaultMode) { _ in
            FloatingWindowController.shared.applyDefaultMode()
        }
        .onChange(of: monitorMode) { newValue in
            // 1.0.13：两种监测形态互斥切换
            if newValue == "overlay" {
                AlertManager.shared.stopLiveActivity()
                if overlayEnabled { FloatingWindowController.shared.show() }
            } else {
                FloatingWindowController.shared.hide()
                if liveActivity { AlertManager.shared.startLiveActivity() }
            }
        }
        .onChange(of: alertNotification) { newValue in
            if newValue { AlertManager.shared.requestNotificationPermission() }
        }
        .onChange(of: liveActivity) { newValue in
            if newValue {
                let ok = AlertManager.shared.startLiveActivity()
                if !ok { liveActivity = false } // 系统不支持 → 自动回滚开关
            } else {
                AlertManager.shared.stopLiveActivity()
            }
        }
    }
}

// MARK: - 开关行（自封装 Toggle 外观）

private struct ToggleRow: View {
    @Environment(\.colors) private var colors
    let title: String
    let subtitle: String
    @Binding var isOn: Bool

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(Typography.labelLarge(.medium))
                    .foregroundStyle(colors.textPrimary)
                Text(subtitle)
                    .font(Typography.labelMedium())
                    .foregroundStyle(colors.textSecondary)
            }
            Spacer()
            GlassSwitch(isOn: $isOn)
        }
    }
}

// MARK: - 视图模型

@MainActor
final class SettingsViewModel: ObservableObject {
    @Published private(set) var notificationAuthorized = false

    init() {
        UNUserNotificationCenter.current().getNotificationSettings { [weak self] settings in
            Task { @MainActor in
                self?.notificationAuthorized = settings.authorizationStatus == .authorized
            }
        }
    }
}
