//
//  OverlayView.swift
//  DevicePerfMonitor
//
//  应用内悬浮窗内容（对应 Android 端 overlay/OverlayCompose.kt）。
//
//  形态（与 Android 端一致）：
//  - 迷你模式：单行三项核心数据（FPS / CPU 使用率 / 内存占用；
//    iOS 无机身温度 → 第三项用内存占用率替代，README 已说明）；
//  - 标准模式：6 项指标面板（2 行 × 3 列）；
//  - 收窄态：贴边窄条，点击展开；
//  - 告警态：整体变红（边框/数值）。
//
//  玻璃材质：走 LiquidGlassSurface（UIVisualEffectView 真实毛玻璃；
//  窗口层级在 App 内，可真实采样背后画面）。
//
//  iOS 平台限制（README「降级说明」记录）：iOS 不允许第三方应用创建
//  系统级全局悬浮窗（无 SYSTEM_ALERT_WINDOW 等价物），故本悬浮窗仅在
//  DevicePerfMonitor 应用内显示，退后台即隐藏。
//

import SwiftUI

struct OverlayData {
    let fps: Metric<Float>
    let cpuUsage: Metric<Float>
    let cpuTemp: Metric<Float>
    let batteryTemp: Metric<Float>
    let batteryLevel: Metric<Float>
    let memoryRatio: Metric<Float>
}

/// 悬浮窗 UI 状态（由 FloatingWindowController 驱动）。
struct OverlayUiState {
    enum Mode {
        case mini, standard, collapsed
    }

    var mode: Mode = .mini
    var alert: Bool = false
}

struct OverlayView: View {
    @Environment(\.colors) private var colors
    let state: OverlayUiState
    let data: OverlayData
    /// 刷新间隔（ms），用于数值缓动时长。
    var intervalMs: Double = 1000

    var body: some View {
        ZStack {
            switch state.mode {
            case .collapsed:
                CollapsedStrip(alert: state.alert)
            case .mini:
                MiniRow(data: data, intervalMs: intervalMs)
            case .standard:
                StandardGrid(data: data, intervalMs: intervalMs)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .liquidGlass(
            GlassStyle(
                material: .systemThinMaterial,
                surfaceAlpha: state.alert
                    ? DesignTokens.Glass.BackgroundAlpha.max
                    : DesignTokens.Glass.BackgroundAlpha.min,
                cornerRadius: DesignTokens.Radius.card
            ),
            tint: state.alert ? colors.danger : colors.surface
        )
    }
}

// MARK: - 迷你模式：单行三项

private struct MiniRow: View {
    @Environment(\.colors) private var colors
    let data: OverlayData
    var intervalMs: Double

    var body: some View {
        HStack(spacing: 12) {
            MiniCell(label: "FPS", metric: data.fps, id: .fps, intervalMs: intervalMs)
            MiniDivider()
            MiniCell(label: "CPU", metric: data.cpuUsage, id: .cpuUsage, intervalMs: intervalMs)
            MiniDivider()
            MiniCell(label: "内存", metric: data.memoryRatio, id: .memoryUsageRatio, intervalMs: intervalMs)
        }
    }
}

private struct MiniCell: View {
    @Environment(\.colors) private var colors
    let label: String
    let metric: Metric<Float>
    let id: MetricId
    var intervalMs: Double

    var body: some View {
        VStack(spacing: 2) {
            Text(label)
                .font(Typography.mono(10))
                .foregroundStyle(colors.textSecondary)
            switch metric.state {
            case .available(let value):
                AnimatedNumber(
                    value: Double(value),
                    decimals: id.decimals,
                    color: colors.metricColor(id.colorKey),
                    font: Typography.mono(16, .medium),
                    intervalMs: intervalMs
                )
            case .unavailable:
                Text("—")
                    .font(Typography.mono(16, .medium))
                    .foregroundStyle(colors.textSecondary)
            case .needsPermission:
                Text("需授权")
                    .font(Typography.mono(10))
                    .foregroundStyle(colors.warning)
            }
        }
    }
}

private struct MiniDivider: View {
    @Environment(\.colors) private var colors
    var body: some View {
        Rectangle()
            .fill(
                LinearGradient(
                    colors: [.clear, colors.textSecondary.opacity(0.35), .clear],
                    startPoint: .top,
                    endPoint: .bottom
                )
            )
            .frame(width: 1, height: 26)
    }
}

// MARK: - 标准模式：6 项面板（2 行 × 3 列）

private struct StandardGrid: View {
    @Environment(\.colors) private var colors
    let data: OverlayData
    var intervalMs: Double

    var body: some View {
        VStack(spacing: 10) {
            HStack(spacing: 8) {
                GridCell(label: "FPS", metric: data.fps, id: .fps, intervalMs: intervalMs)
                GridCell(label: "CPU", metric: data.cpuUsage, id: .cpuUsage, intervalMs: intervalMs)
                GridCell(label: "CPU 温度", metric: data.cpuTemp, id: .cpuTemp, intervalMs: intervalMs)
            }
            HStack(spacing: 8) {
                GridCell(label: "电池温度", metric: data.batteryTemp, id: .batteryTemp, intervalMs: intervalMs)
                GridCell(label: "电量", metric: data.batteryLevel, id: .batteryLevel, intervalMs: intervalMs)
                GridCell(label: "内存", metric: data.memoryRatio, id: .memoryUsageRatio, intervalMs: intervalMs)
            }
        }
    }
}

private struct GridCell: View {
    @Environment(\.colors) private var colors
    let label: String
    let metric: Metric<Float>
    let id: MetricId
    var intervalMs: Double

    var body: some View {
        VStack(spacing: 2) {
            Text(label)
                .font(Typography.mono(9))
                .foregroundStyle(colors.textSecondary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
            switch metric.state {
            case .available(let value):
                AnimatedNumber(
                    value: Double(value),
                    decimals: id.decimals,
                    color: colors.metricColor(id.colorKey),
                    font: Typography.mono(14, .medium),
                    intervalMs: intervalMs
                )
            case .unavailable:
                Text("—")
                    .font(Typography.mono(14, .medium))
                    .foregroundStyle(colors.textSecondary)
            case .needsPermission:
                Text("需授权")
                    .font(Typography.mono(9))
                    .foregroundStyle(colors.warning)
            }
        }
        .frame(maxWidth: .infinity)
    }
}

// MARK: - 收窄态：贴边窄条

private struct CollapsedStrip: View {
    @Environment(\.colors) private var colors
    let alert: Bool

    var body: some View {
        ZStack {
            Capsule()
                .fill(alert ? colors.danger : colors.accent)
                .frame(width: 5, height: 34)
            Circle()
                .fill(colors.accent.opacity(0.8))
                .frame(width: 4, height: 4)
                .offset(y: 26)
        }
    }
}
