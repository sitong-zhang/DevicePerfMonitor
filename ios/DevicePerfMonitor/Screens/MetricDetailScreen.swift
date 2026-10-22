//
//  MetricDetailScreen.swift
//  DevicePerfMonitor
//
//  指标详情页（对应 Android 端 ui/screen/MetricDetailScreen.kt）：
//  - 当前值（三态渲染）；
//  - 1/5/30 分钟历史图表（GlassTabs 分段，Charts 折线）；
//  - 窗口峰值查看（max/min/avg/latest，样本不足 2 个显示"数据不足，无法分析"）。
//

import SwiftUI

struct MetricDetailScreen: View {
    @Environment(\.colors) private var colors
    @Environment(\.dismiss) private var dismiss
    let metricId: MetricId?

    @StateObject private var viewModel = MetricDetailViewModel()

    var body: some View {
        ScrollView {
            if let metricId {
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
                            MetricValueText(
                                metricId: metricId,
                                metric: viewModel.snapshot.metricOf(metricId),
                                intervalMs: viewModel.tickMs
                            )
                        }
                    }

                    // 时间窗口切换
                    GlassTabs(
                        options: ["1 分钟", "5 分钟", "30 分钟"],
                        selectedIndex: $viewModel.windowIndex
                    )

                    // 历史图表
                    GlassPanel {
                        VStack(alignment: .leading, spacing: DesignTokens.Spacing.s8) {
                            MetricChartView(points: viewModel.points(metricId))
                            ChartCaption(points: viewModel.points(metricId))
                        }
                    }

                    // 峰值查看
                    GlassPanel {
                        VStack(alignment: .leading, spacing: DesignTokens.Spacing.s8) {
                            Text("窗口峰值（\(viewModel.windowsSec[viewModel.windowIndex] / 60) 分钟）")
                                .font(Typography.labelMedium())
                                .foregroundStyle(colors.textSecondary)
                            if let peaks = viewModel.peakStats(metricId) {
                                PeakRow(label: "最高", value: peaks.max, color: colors.metricColor(metricId.colorKey))
                                PeakRow(label: "最低", value: peaks.min, color: colors.metricColor(metricId.colorKey))
                                PeakRow(label: "平均", value: peaks.avg, color: colors.metricColor(metricId.colorKey))
                                PeakRow(label: "最新", value: peaks.latest, color: colors.metricColor(metricId.colorKey))
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
        .onAppear { viewModel.attach(metricId: metricId) }
        .onChange(of: metricId) { newValue in viewModel.attach(metricId: newValue) }
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

    let windowsSec = [60, 300, 1800]

    private var timer: Timer?
    private var metricId: MetricId?

    func attach(metricId: MetricId?) {
        self.metricId = metricId
        if timer == nil {
            let timer = Timer(timeInterval: 1, repeats: true) { [weak self] _ in
                Task { @MainActor in self?.refresh() }
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
