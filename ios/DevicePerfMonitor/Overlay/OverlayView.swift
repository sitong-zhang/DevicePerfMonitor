//
//  OverlayView.swift
//  DevicePerfMonitor
//
//  应用内悬浮窗内容（对应 Android 端 overlay/OverlayCompose.kt）。
//
//  形态（与 Android 端一致，1.0.8 起三态）：
//  - card 完整卡片：综合评判小环 + 核心指标网格（默认形态，稳定显示不变形）；
//  - numbers 纯数字：只要卡片上的数字——无玻璃、无阴影、无小环的一横排；
//  - collapsed 收起：一条竖线，点一下恢复完整卡片；
//  - 单击循环切换 card → numbers → collapsed → card；
//  - 告警态：卡片整体转危险色 / 数字转危险色。
//
//  硬规则（与 Android 一致）：只保留内存占用率 / 设备温度 / 屏幕刷新率 /
//  帧率四个核心指标；采不到的指标不占位、不显示「—」，直接不进列表。
//  iOS 无机身温度公开 API → 温度格在 iOS 上常态不出现。
//
//  玻璃材质：card 态走 LiquidGlassKit 的 glassSurface（iOS 26+ 原生
//  glassEffect，17~25 纯 SwiftUI fallback；窗口层级在 App 内，
//  可真实采样背后画面）；numbers 态不要卡片，直接浮数字。
//
//  iOS 平台限制（README「降级说明」记录）：iOS 不允许第三方应用创建
//  系统级全局悬浮窗（无 SYSTEM_ALERT_WINDOW 等价物），故本悬浮窗仅在
//  DevicePerfMonitor 应用内显示，退后台即隐藏、回前台自动挂出。
//

import SwiftUI

/// 悬浮窗数据：只带四个核心指标 + 综合评判。
struct OverlayData {
    let memoryRatio: Metric<Float>
    let deviceTemp: Metric<Float>
    let refreshHz: Metric<Float>
    let fps: Metric<Float>
    let health: FocusedHealthResult

    /// 采得到的指标（顺序固定：内存 / 温度 / 刷新率 / 帧率）。
    var cells: [(id: MetricId, value: Float)] {
        var result: [(id: MetricId, value: Float)] = []
        if let v = memoryRatio.value { result.append((.memoryUsageRatio, v)) }
        if let v = deviceTemp.value { result.append((.deviceTemp, v)) }
        if let v = refreshHz.value { result.append((.refreshRate, v)) }
        if let v = fps.value { result.append((.fps, v)) }
        return result
    }
}

/// 悬浮窗 UI 状态（由 FloatingWindowController 驱动）。
struct OverlayUiState {
    /// 三态：完整卡片 / 纯数字 / 收起。顺序即单击循环顺序。
    enum Mode {
        case card, numbers, collapsed
    }

    var mode: Mode = .card
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
            case .numbers:
                NumbersStrip(data: data, alert: state.alert, intervalMs: intervalMs)
            case .card:
                CardContent(data: data, alert: state.alert, intervalMs: intervalMs)
            }
        }
        // 形态切换的形变在内容层完成（spring，token：motion.spring.defaultSpatial），
        // 窗口尺寸瞬切——不拿窗口动画去形变用户正在看的卡片（与 Android 同一原则）
        .animation(
            .spring(
                response: DesignTokens.Motion.Spring.DefaultSpatial.response,
                dampingFraction: DesignTokens.Motion.Spring.DefaultSpatial.dampingFraction
            ),
            value: state.mode
        )
    }
}

// MARK: - 完整卡片：综合评判小环 + 指标网格（液态玻璃容器）

private struct CardContent: View {
    @Environment(\.colors) private var colors
    let data: OverlayData
    let alert: Bool
    var intervalMs: Double

    var body: some View {
        VStack(spacing: 10) {
            HStack {
                HealthBadge(health: data.health, diameter: 52, lineWidth: 5)
                Spacer()
                Text("综合评判")
                    .font(Typography.mono(10))
                    .foregroundStyle(colors.textSecondary)
            }
            let cells = data.cells
            ForEach(Array(stride(from: 0, to: cells.count, by: 2).enumerated()), id: \.offset) { _, rowStart in
                HStack(spacing: 8) {
                    GridCell(label: cells[rowStart].id.shortLabel, metricValue: cells[rowStart].value, id: cells[rowStart].id, intervalMs: intervalMs)
                    if rowStart + 1 < cells.count {
                        GridCell(label: cells[rowStart + 1].id.shortLabel, metricValue: cells[rowStart + 1].value, id: cells[rowStart + 1].id, intervalMs: intervalMs)
                    } else {
                        Spacer().frame(maxWidth: .infinity)
                    }
                }
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .liquidGlass(
            GlassStyle(
                material: .frosted,
                surfaceAlpha: alert
                    ? DesignTokens.Glass.BackgroundAlpha.max
                    : DesignTokens.Glass.BackgroundAlpha.min,
                cornerRadius: DesignTokens.Radius.card
            ),
            tint: alert ? colors.danger : colors.surface
        )
    }
}

// MARK: - 纯数字：只要卡片上的数字，不要卡片
//
// 无玻璃、无阴影、无小环、无标签——四个数字直接横排浮在屏幕上，
// 每个数字保持自己的指标色；告警时统一转危险色（没有卡片可染，染数字）。

private struct NumbersStrip: View {
    @Environment(\.colors) private var colors
    let data: OverlayData
    let alert: Bool
    var intervalMs: Double

    var body: some View {
        let cells = data.cells
        if cells.isEmpty {
            // 纯数字态没有容器，缺数据时给一行小字兜底
            Text("读不到指标")
                .font(Typography.mono(10))
                .foregroundStyle(colors.textSecondary)
        } else {
            HStack(spacing: 12) {
                ForEach(Array(cells.enumerated()), id: \.offset) { _, cell in
                    HStack(alignment: .lastTextBaseline, spacing: 2) {
                        AnimatedNumber(
                            value: Double(cell.value),
                            decimals: cell.id.decimals,
                            color: alert ? colors.danger : colors.metricColor(cell.id.colorKey),
                            font: Typography.mono(17, .semibold),
                            intervalMs: intervalMs
                        )
                        Text(cell.id.unit)
                            .font(Typography.mono(9))
                            .foregroundStyle(colors.textSecondary)
                    }
                }
            }
            .padding(.horizontal, 6)
        }
    }
}

// MARK: - 综合评判小环

private struct HealthBadge: View {
    @Environment(\.colors) private var colors
    let health: FocusedHealthResult
    var diameter: CGFloat
    var lineWidth: CGFloat

    var body: some View {
        let total = health.total
        let tint: Color = {
            guard let total else { return colors.textSecondary }
            switch total {
            case 75...: return colors.success
            case 50..<75: return colors.accent
            default: return colors.danger
            }
        }()
        ProgressRing(
            progress: (total ?? 0) / 100,
            // 维度全采不到就不给分：环留空，不写占位数字
            centerText: total.map { String(Int($0.rounded())) } ?? "",
            caption: "",
            color: tint,
            trackColor: tint.opacity(0.2),
            lineWidth: lineWidth,
            centerFontSize: diameter * 0.30,
            captionFontSize: diameter * 0.16
        )
        .frame(width: diameter, height: diameter)
    }
}

// MARK: - 单元格

private struct GridCell: View {
    @Environment(\.colors) private var colors
    let label: String
    let metricValue: Float
    let id: MetricId
    var intervalMs: Double

    var body: some View {
        VStack(spacing: 2) {
            Text(label)
                .font(Typography.mono(9))
                .foregroundStyle(colors.textSecondary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
            AnimatedNumber(
                value: Double(metricValue),
                decimals: id.decimals,
                color: colors.metricColor(id.colorKey),
                font: Typography.mono(14, .medium),
                intervalMs: intervalMs
            )
        }
        .frame(maxWidth: .infinity)
    }
}

// MARK: - 收起态：竖线 + 提示点

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
