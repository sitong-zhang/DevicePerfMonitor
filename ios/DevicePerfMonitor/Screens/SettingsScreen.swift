//
//  SettingsScreen.swift
//  DevicePerfMonitor
//
//  设置页（对应 Android 端 ui/screen/SettingsScreen.kt）：
//  - 采集：后台采集开关 + 刷新模式（实时/均衡/省电）+ 离线说明；
//  - 悬浮窗：应用内悬浮窗开关（含平台限制说明）；
//  - 告警：通知开关 + 实时活动（Live Activity）开关；
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
    @AppStorage("liveActivity") private var liveActivity = false
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
                        ToggleRow(title: "后台采集", subtitle: "退后台后继续采样（系统可能限制频率）", isOn: $backgroundCollection)
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

                // ---- 悬浮窗 ----
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
                        Text("iOS 平台限制：系统不允许第三方应用创建全局悬浮窗（无 SYSTEM_ALERT_WINDOW 等价物），本悬浮窗仅在 DevicePerfMonitor 内显示，退后台自动隐藏、回前台自动挂出。详见 README「降级说明」。")
                            .font(Typography.labelMedium())
                            .foregroundStyle(colors.warning)
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
                        ToggleRow(
                            title: "实时活动（灵动岛/锁屏）",
                            subtitle: "需要 iOS 16.1+；不支持时自动降级",
                            isOn: $liveActivity
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
                        Text("历史轨迹保留 2 小时，开始采集后不删、可回看；退出应用后重新打开仍在。")
                            .font(Typography.labelMedium())
                            .foregroundStyle(colors.textSecondary)
                        Text("图表：danielgindi/Charts（Apache-2.0）；字体：Inter / JetBrains Mono（OFL-1.1）；配色：Catppuccin Mocha / Latte（MIT）。")
                            .font(Typography.labelMedium())
                            .foregroundStyle(colors.textSecondary)
                        Text("iOS 平台可用性差异与降级路径详见仓库 README「API-权限-可用性-降级」对照表。")
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
