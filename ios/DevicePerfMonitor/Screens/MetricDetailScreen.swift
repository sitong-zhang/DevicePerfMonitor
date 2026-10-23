//
//  MetricDetailScreen.swift
//  DevicePerfMonitor
//
//  指标详情页（对应 Android 端 ui/screen/MetricDetailScreen.kt）：
//  - 当前值（可用才显示；采不到显示原因文案，不摆「—」占位）；
//  - 回看窗口 1 分钟 / 5 分钟 / 30 分钟 / 2 小时（历史保留 2 小时）；
//  - 显示方式可切换：数字 / 波形 / 柱状 / 环形（环形仅内存占用率），选择持久化；
//  - 窗口峰值查看（max/min/avg/latest，样本不足 2 个显示"数据不足，无法分析"）。
//

import SwiftUI

struct MetricDetailScreen: View {
    @Environment(\.colors) private var colors
    @Environment(\.dismiss) private var dismiss
    let metricId: MetricId?

    @StateObject private var viewModel = MetricDetailViewModel()
    @State private var mode: MetricViewMode = .number

    private let windowLabels = ["1 分钟", "5 分钟", "30 分钟", "2 小时"]

    var body: some View {
        ScrollView {
            if let metricId {
                let metric = viewModel.snapshot.metricOf(metricId)
                let value = metric?.value
                let points = viewModel.points(metricId)
                let accent = colors.metricColor(metricId.colorKey)

                VStack(alignment: .leading, spacing: DesignTokens.Spacing.s12) {
                    // 顶栏：返回 + 指标名
                    HStack {
                        GlassButton(text: "‹ 返回", compact: true, action: { dismiss() })
                        Text(metricId.label)
                            .font(Typography.titleLarge(.medium))
                            .foregroundStyle(colors.textPrimary)
                    }

                    // 当前值
                    GlassPanel {
                        VStack(alignment: .leading, spacing: DesignTokens.Spacing.s8) {
                            Text("当前值（\(metricId.unit)）")
                                .font(Typography.labelMedium())
                                .foregroundStyle(colors.textSecondary)
                            if let value {
                                HStack(alignment: .firstTextBaseline, spacing: 2) {
                                    AnimatedNumber(
                                        value: Double(value),
                                        decimals: metricId.decimals,
                                        color: accent,
                                        font: Typography.headlineMedium(.bold),
                                        intervalMs: viewModel.tickMs
                                    )
                                    Text(metricId.unit)
                                        .font(Typography.labelMedium())
                                        .foregroundStyle(colors.textSecondary)
                                }
                            } else {
                                // 采不到：说清楚原因，不摆占位数字
                                Text(unavailableReason(metric))
                                    .font(Typography.bodyMedium())
                                    .foregroundStyle(colors.textSecondary)
                            }
                        }
                    }

                    // 回看窗口
                    GlassTabs(options: windowLabels, selectedIndex: $viewModel.windowIndex)

                    // 显示方式（环形只对有界指标开放）
                    let modeOptions = MetricViewMode.allCases
                        .filter { $0 != .ring || metricId == .memoryUsageRatio }
                    let modeIndex = Binding<Int>(
                        get: { modeOptions.firstIndex(of: mode) ?? 0 },
                        set: { mode = modeOptions[$0] }
                    )
                    GlassTabs(
                        options: modeOptions.map { $0.rawValue },
                        selectedIndex: modeIndex
                    )

                    // 轨迹大图
                    GlassPanel {
                        VStack(alignment: .leading, spacing: DesignTokens.Spacing.s8) {
                            chart(mode: MetricViewMode.effective(mode, id: metricId),
                                  points: points,
                                  value: value,
                                  metricId: metricId,
                                  accent: accent)
                        }
                    }

                    // 峰值查看
                    GlassPanel {
                        VStack(alignment: .leading, spacing: DesignTokens.Spacing.s8) {
                            Text("窗口峰值（\(windowLabels[viewModel.windowIndex])）")
                                .font(Typography.labelMedium())
                                .foregroundStyle(colors.textSecondary)
                            if let peaks = viewModel.peakStats(metricId) {
                                PeakRow(label: "最高", value: peaks.max, color: accent)
                                PeakRow(label: "最低", value: peaks.min, color: accent)
                                PeakRow(label: "平均", value: peaks.avg, color: accent)
                                PeakRow(label: "最新", value: peaks.latest, color: accent)
                                Text("样本数 \(peaks.samples)")
                                    .font(Typography.labelMedium())
                                    .foregroundStyle(colors.textSecondary)
                            } else {
                                Text("数据不足，无法分析")
                                    .font(Typography.bodyMedium())
                                    .foregroundStyle(colors.textSecondary)
                            }
                        }
                    }
                }
                .padding(.horizontal, DesignTokens.Spacing.s16)
                .padding(.top, DesignTokens.Spacing.s32)
                .padding(.bottom, 96)
            } else {
                VStack(spacing: DesignTokens.Spacing.s16) {
                    Text("指标不存在")
                        .font(Typography.titleMedium(.medium))
                        .foregroundStyle(colors.textPrimary)
                    GlassButton(text: "返回", action: { dismiss() })
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .background(colors.background.ignoresSafeArea())
        .onAppear {
            viewModel.attach(metricId: metricId)
            if let metricId {
                mode = MetricViewMode.effective(ViewModeStore.mode(for: metricId), id: metricId)
            }
        }
        .onChange(of: metricId) { newValue in
            viewModel.attach(metricId: newValue)
            if let newValue {
                mode = MetricViewMode.effective(ViewModeStore.mode(for: newValue), id: newValue)
            }
        }
        .onChange(of: mode) { newValue in
            if let metricId { ViewModeStore.set(newValue, for: metricId) }
        }
    }

    /// 轨迹区：按显示模式切换；采不到就明说，不摆占位。
    @ViewBuilder
    private func chart(mode: MetricViewMode, points: [HistoryPoint], value: Float?,
                       metricId: MetricId, accent: Color) -> some View {
        let values = points.map { $0.value }
        switch mode {
        case .number:
            if let value {
                AnimatedNumber(
                    value: Double(value),
                    decimals: metricId.decimals,
                    color: accent,
                    font: Typography.displayLarge(.bold),
                    intervalMs: viewModel.tickMs
                )
                .frame(maxWidth: .infinity, alignment: .center)
            } else {
                chartEmpty
            }

        case .wave:
            if values.count >= 2 {
                MetricChartView(points: points, lineColor: accent)
                    .frame(height: 220)
            } else {
                chartEmpty
            }

        case .bars:
            if values.count >= 2 {
                BarSeries(points: values, color: accent)
                    .frame(height: 220)
            } else {
                chartEmpty
            }

        case .ring:
            if let value {
                ProgressRing(
                    progress: value / 100,
                    centerText: String(Int(value.rounded())),
                    caption: metricId.unit,
                    color: accent,
                    trackColor: accent.opacity(0.18),
                    lineWidth: 12
                )
                .frame(width: 180, height: 180)
                .frame(maxWidth: .infinity, alignment: .center)
            } else {
                chartEmpty
            }
        }
    }

    private var chartEmpty: some View {
        Text("这个时间段还没有采到数据")
            .font(Typography.bodyMedium())
            .foregroundStyle(colors.textSecondary)
            .frame(maxWidth: .infinity, alignment: .center)
            .padding(.vertical, 80)
    }

    /// 不可用原因（拿不到内部原因时给一句通用说明）。
    private func unavailableReason(_ metric: Metric<Float>?) -> String {
        if let metric, case .unavailable(let reason, _) = metric {
            return reason
        }
        return "这台设备读不到这个指标"
    }
}

private struct PeakRow: View {
    @Environment(\.colors) private var colors
    let label: String
    let value: Float
    let color: Color

    var body: some View {
        HStack {
            Text(label)
                .font(Typography.bodyMedium())
                .foregroundStyle(colors.textSecondary)
            Spacer()
            MonoNumber(text: Self.format(value), color: color, font: Typography.headlineSmall(.medium))
        }
    }

    static func format(_ value: Float) -> String {
        if value >= 100 || value <= -100 || value == value.rounded() {
            return String(Int(value))
        }
        return String(format: "%.1f", value)
    }
}

// MARK: - 视图模型

@MainActor
final class MetricDetailViewModel: ObservableObject {
    @Published private(set) var snapshot: DeviceSnapshot = .empty()
    @Published private(set) var tickMs: Double = 1000
    @Published var windowIndex: Int = 1 // 默认 5 分钟

    /// 历史保留 2 小时（与 Android 端一致）。
    let windowsSec = [60, 300, 1800, 7200]

    private var timer: Timer?
    private var metricId: MetricId?

    func attach(metricId: MetricId?) {
        self.metricId = metricId
        if timer == nil {
            let timer = Timer(timeInterval: 1, repeats: true) { [weak self] _ in
                Task { @MainActor [weak self] in self?.refresh() }
            }
            RunLoop.main.add(timer, forMode: .common)
            self.timer = timer
        }
        refresh()
    }

    deinit {
        timer?.invalidate()
    }

    private func refresh() {
        let repository = MetricsRepository.shared
        snapshot = repository.snapshot
        tickMs = repository.tickMs
    }

    func points(_ id: MetricId) -> [HistoryPoint] {
        MetricsRepository.shared.history(id, windowSec: windowsSec[windowIndex])
    }

    func peakStats(_ id: MetricId) -> (max: Float, min: Float, avg: Float, latest: Float, samples: Int)? {
        MetricsRepository.shared.peakStats(id, windowSec: windowsSec[windowIndex])
    }
}
