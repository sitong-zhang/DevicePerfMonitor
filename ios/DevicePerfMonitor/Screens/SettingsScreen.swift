//
//  SettingsScreen.swift
//  DevicePerfMonitor
//
//  设置页（对应 Android 端 ui/screen/SettingsScreen.kt）：
//  - 采集：后台采集开关 + 刷新模式（实时/均衡/省电）+ 离线说明；
//  - 监测悬浮形态：按平台能力分流（1.0.10）——本机支持实时活动的机型
//    只用灵动岛/锁屏，设置里不出现悬浮窗选项；不支持的机型才回退
//    应用内悬浮窗（含平台限制说明）；
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
    // 1.0.10：支持实时活动的机型监测形态只用灵动岛/锁屏，默认开
    @AppStorage("liveActivity") private var liveActivity = true
    @AppStorage("themeMode") private var themeModeRaw = ThemeMode.dark.rawValue
    @AppStorage("backgroundCollection") private var backgroundCollection = true
    /// 默认纯数字：启动悬浮窗时只要数字、不显示卡片（对应 Android overlayMiniMode）。
    @AppStorage("overlayMiniMode") private var overlayMiniMode = false
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

                // ---- 监测悬浮形态（1.0.10 起按平台能力分流）----
                // 支持实时活动：只用灵动岛/锁屏，不出现悬浮窗选项；
                // 不支持（iOS 16.1 以下或用户在系统里关了实时活动）：回退应用内悬浮窗。
                if AlertManager.isSupported {
                    GlassSectionTitle(text: "灵动岛 / 锁屏")
                    GlassPanel {
                        VStack(alignment: .leading, spacing: DesignTokens.Spacing.s12) {
                            ToggleRow(
                                title: "实时活动（灵动岛/锁屏）",
                                subtitle: "监测数据直接显示在灵动岛与锁屏，打开应用即自动开启",
                                isOn: $liveActivity
                            )
                            Text("本机支持实时活动（iOS 16.1+），按平台规范不再提供应用内悬浮窗。iOS 没有机身温度的公开 API，灵动岛不显示温度；退后台后系统只给有限运行时，App 被挂起后数据停留在最后一次采样。")
                                .font(Typography.labelMedium())
                                .foregroundStyle(colors.textSecondary)
                        }
                    }
                } else {
                    GlassSectionTitle(text: "悬浮窗")
                    GlassPanel {
                        VStack(alignment: .leading, spacing: DesignTokens.Spacing.s12) {
                            ToggleRow(title: "应用内悬浮窗", subtitle: "回应用自动挂出实时指标小窗（仅在应用内可见）", isOn: $overlayEnabled)
                            ToggleRow(title: "默认纯数字模式", subtitle: "启动时只要数字、不显示卡片；单击可在 卡片 → 纯数字 → 收起 间循环", isOn: $overlayMiniMode)
                            GlassSlider(
                                value: $overlayOpacity,
                                range: 0.4...1.0,
                                label: "悬浮窗不透明度",
                                valueText: "\(Int((overlayOpacity * 100).rounded()))%"
                            )
                            Text("本机未启用实时活动（iOS 16.1 以下，或已在系统设置里关闭），已回退为应用内悬浮窗。iOS 平台限制：系统不允许第三方应用创建全局悬浮窗（无 SYSTEM_ALERT_WINDOW 等价物），本悬浮窗仅在 DevicePerfMonitor 内显示，退后台自动隐藏、回前台自动挂出。详见 README「降级说明」。")
                                .font(Typography.labelMedium())
                                .foregroundStyle(colors.warning)
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
            newValue ? FloatingWindowController.shared.show() : FloatingWindowController.shared.hide()
        }
        .onChange(of: overlayOpacity) { _ in
            FloatingWindowController.shared.applyOpacity()
        }
        .onChange(of: overlayMiniMode) { _ in
            FloatingWindowController.shared.applyDefaultMode()
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
