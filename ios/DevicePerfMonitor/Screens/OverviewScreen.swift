//
//  OverviewScreen.swift
//  DevicePerfMonitor
//
//  首页总览（对应 Android 端 ui/screen/OverviewScreen.kt）：
//  - 标题 + 告警 Chip；
//  - 综合评判大卡片（环形总分 + 评级 + 一句话结论 + 最短板）；
//  - 四个核心指标卡（内存占用率 / 设备温度 / 屏幕刷新率 / 帧率），
//    每张卡带最近 1 分钟迷你波形，点进去看历史回看；
//  - 采不到的指标不占位、不显示「—」——整个界面只认这四个指标。
//
//  说明：iOS 无机身温度公开 API（详见 README 降级说明），设备温度卡在
//  iOS 上常态不出现；综合评判的温度维度随之缺席，分数按流畅度 + 内存折算。
//

import SwiftUI

/// 总览页的核心指标读数（与 Android 端 MonitorViewModel.CoreReading 对齐）。
struct CoreReading: Identifiable {
    let id: MetricId
    let value: Float
}

struct OverviewScreen: View {
    @Environment(\.colors) private var colors
    @StateObject private var viewModel = OverviewViewModel()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DesignTokens.Spacing.s12) {
                header

                // 综合评判
                FocusedHealthCard(result: viewModel.focusedHealth)

                // 四个核心指标（采得到的才渲染）
                ForEach(viewModel.coreReadings) { reading in
                    CoreMetricCard(
                        reading: reading,
                        tickMs: viewModel.tickMs,
                        history: viewModel.history(for: reading.id, windowSec: 60)
                    )
                }

                if viewModel.coreReadings.isEmpty {
                    emptyState
                }
            }
            .padding(.horizontal, DesignTokens.Spacing.s16)
            .padding(.top, DesignTokens.Spacing.s32)
            .padding(.bottom, 96) // 底部标签栏空间
        }
        .background(colors.background.ignoresSafeArea())
    }

    private var header: some View {
        HStack {
            Text("设备性能")
                .font(Typography.titleLarge(.semibold))
                .foregroundStyle(colors.textPrimary)
            Spacer()
            if AlertManager.shared.alertActive {
                Label("告警", systemImage: "exclamationmark.triangle.fill")
                    .font(Typography.labelMedium(.medium))
                    .foregroundStyle(colors.danger)
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: DesignTokens.Spacing.s4) {
            Text("这台设备暂时读不到这些指标")
                .font(Typography.bodyMedium())
                .foregroundStyle(colors.textSecondary)
            Text("能采到的会自动出现，采不到的不会占位")
                .font(Typography.labelMedium())
                .foregroundStyle(colors.textSecondary.opacity(0.7))
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 56)
    }
}

// MARK: - 综合评判大卡片

private struct FocusedHealthCard: View {
    @Environment(\.colors) private var colors
    let result: FocusedHealthResult

    var body: some View {
        let total = result.total
        let accent: Color = {
            guard let total else { return colors.textSecondary }
            switch total {
            case 75...: return colors.success
            case 50..<75: return colors.accent
            default: return colors.danger
            }
        }()

        GlassPanel {
            HStack(spacing: DesignTokens.Spacing.s16) {
                ProgressRing(
                    progress: (total ?? 0) / 100,
                    // 维度全采不到就不给分：环留空，不写占位数字
                    centerText: total.map { String(Int($0.rounded())) } ?? "",
                    caption: result.grade?.rawValue ?? "采集中",
                    color: accent,
                    trackColor: accent.opacity(0.18),
                    lineWidth: 10
                )
                .frame(width: 108, height: 108)

                VStack(alignment: .leading, spacing: DesignTokens.Spacing.s4) {
                    Text("综合评判")
                        .font(Typography.titleMedium(.medium))
                        .foregroundStyle(colors.textPrimary)
                    Text(result.summary)
                        .font(Typography.bodyMedium())
                        .foregroundStyle(colors.textSecondary)
                    if let weakest = result.weakest {
                        Text("最短板：\(weakest.rawValue)")
                            .font(Typography.labelMedium())
                            .foregroundStyle(accent)
                    }
                }
            }
        }
    }
}

// MARK: - 核心指标卡

private struct CoreMetricCard: View {
    @Environment(\.colors) private var colors
    let reading: CoreReading
    let tickMs: Double
    let history: [HistoryPoint]

    private var mode: MetricViewMode {
        MetricViewMode.effective(ViewModeStore.mode(for: reading.id), id: reading.id)
    }

    var body: some View {
        let accent = colors.metricColor(reading.id.colorKey)
        let values = history.map { $0.value }

        GlassPanel {
            NavigationLink(value: reading.id) {
                VStack(alignment: .leading, spacing: DesignTokens.Spacing.s8) {
                    HStack {
                        Text(reading.id.label)
                            .font(Typography.labelMedium())
                            .foregroundStyle(colors.textSecondary)
                        Spacer()
                        // 当前显示模式（详情页可切换）
                        Text(mode.rawValue)
                            .font(Typography.labelSmall())
                            .foregroundStyle(accent.opacity(0.75))
                    }

                    HStack(alignment: .firstTextBaseline, spacing: 2) {
                        AnimatedNumber(
                            value: Double(reading.value),
                            decimals: reading.id.decimals,
                            color: accent,
                            font: Typography.headlineSmall(.bold),
                            intervalMs: tickMs
                        )
                        Text(reading.id.unit)
                            .font(Typography.labelMedium())
                            .foregroundStyle(colors.textSecondary)
                    }

                    // 最近一分钟迷你波形；数据不足两个点就不画
                    if values.count >= 2 {
                        Sparkline(points: values, color: accent, lineWidth: 1.5)
                            .frame(height: 40)
                    }
                }
            }
            .buttonStyle(.plain)
        }
    }
}

// MARK: - 视图模型（仓库 + 分析的轻量封装）

@MainActor
final class OverviewViewModel: ObservableObject {
    @Published private(set) var snapshot: DeviceSnapshot = .empty()
    @Published private(set) var focusedHealth: FocusedHealthResult
    @Published private(set) var tickMs: Double = 1000

    private var timer: Timer?

    /// 四个核心指标里采得到的（顺序固定：内存 / 温度 / 刷新率 / 帧率）。
    var coreReadings: [CoreReading] {
        let candidates: [(MetricId, Metric<Float>)] = [
            (.memoryUsageRatio, snapshot.memory.usageRatio),
            (.deviceTemp, snapshot.thermal.deviceTempC),
            (.refreshRate, snapshot.display.refreshRateHz),
            (.fps, snapshot.display.fps),
        ]
        return candidates.compactMap { id, metric in
            metric.value.map { CoreReading(id: id, value: $0) }
        }
    }

    init() {
        let repository = MetricsRepository.shared
        _snapshot = Published(wrappedValue: repository.snapshot)
        _focusedHealth = Published(wrappedValue: Self.evaluate(repository.snapshot))
        start()
    }

    private static func evaluate(_ snapshot: DeviceSnapshot) -> FocusedHealthResult {
        FocusedHealth.evaluate(
            memoryRatio: snapshot.memory.usageRatio.value,
            deviceTempC: snapshot.thermal.deviceTempC.value,
            refreshHz: snapshot.display.refreshRateHz.value,
            fps: snapshot.display.fps.value
        )
    }

    private func start() {
        MetricsRepository.shared.start()
        MetricsRepository.shared.sampleOnce()

        timer = Timer(timeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self else { return }
                let repository = MetricsRepository.shared
                self.snapshot = repository.snapshot
                self.focusedHealth = Self.evaluate(repository.snapshot)
                self.tickMs = repository.tickMs
            }
        }
        RunLoop.main.add(timer!, forMode: .common)
    }

    deinit {
        timer?.invalidate()
    }

    /// 某指标最近 windowSec 秒的历史序列（详情页/卡片迷你波形用）。
    func history(for id: MetricId, windowSec: Int) -> [HistoryPoint] {
        HistoryStore.shared.points(metricId: id, windowSec: windowSec)
    }
}
