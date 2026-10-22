//
//  OverviewScreen.swift
//  DevicePerfMonitor
//
//  首页总览（对应 Android 端 ui/screen/OverviewScreen.kt）：
//  - 标题 + 告警 Chip；
//  - 健康分大卡片（大数字 + 等级 + 四维条形图）；
//  - 四分卡片（FPS / CPU 使用率 / CPU 温度 / 电池温度；
//    iOS 上 CPU 温度不可用 → 降级为电量，README 已说明）；
//  - 场景化建议面板（severity 降序）；
//  - 全量指标按类别分组展示（三态渲染，不可用显示原因）。
//

import SwiftUI

struct OverviewScreen: View {
    @Environment(\.colors) private var colors
    @StateObject private var viewModel = OverviewViewModel()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DesignTokens.Spacing.s12) {
                // 标题 + 告警
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

                // 健康分大卡片
                HealthScoreCard(result: viewModel.health, partial: viewModel.health.partial)

                // 四分卡片
                FeaturedMetricsGrid(snapshot: viewModel.snapshot, tickMs: viewModel.tickMs)

                // 建议面板
                AdvicePanel(advice: viewModel.advice)

                // 全量指标分组
                ForEach(MetricCategory.allCases, id: \.self) { category in
                    let metrics = viewModel.metrics(for: category)
                    if !metrics.isEmpty {
                        GlassSectionTitle(text: category.rawValue)
                        GlassPanel {
                            VStack(spacing: DesignTokens.Spacing.s8) {
                                ForEach(metrics, id: \.id) { id in
                                    MetricRow(id: id, metric: viewModel.snapshot.metricOf(id), tickMs: viewModel.tickMs)
                                }
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, DesignTokens.Spacing.s16)
            .padding(.top, DesignTokens.Spacing.s32)
            .padding(.bottom, 96) // 底部标签栏空间
        }
        .background(colors.background.ignoresSafeArea())
    }
}

// MARK: - 健康分大卡片

struct HealthScoreCard: View {
    @Environment(\.colors) private var colors
    let result: HealthScorer.HealthResult
    let partial: Bool

    var body: some View {
        GlassPanel {
            VStack(alignment: .leading, spacing: DesignTokens.Spacing.s12) {
                HStack(alignment: .firstTextBaseline) {
                    Text("综合健康分")
                        .font(Typography.labelLarge(.medium))
                        .foregroundStyle(colors.textSecondary)
                    Spacer()
                    if partial {
                        Text("部分指标不可用")
                            .font(Typography.labelMedium())
                            .foregroundStyle(colors.warning)
                    }
                }

                if let total = result.total, let grade = result.grade {
                    HStack(alignment: .firstTextBaseline, spacing: DesignTokens.Spacing.s8) {
                        Text(String(format: "%.0f", total))
                            .font(Typography.displayLarge(.bold))
                            .foregroundStyle(colors.textPrimary)
                        Text(grade.rawValue)
                            .font(Typography.titleMedium(.medium))
                            .foregroundStyle(gradeColor(grade))
                    }

                    // 四维条形图
                    VStack(spacing: DesignTokens.Spacing.s8) {
                        ForEach(result.dimensions, id: \.id.rawValue) { dim in
                            DimensionBar(dimension: dim)
                        }
                    }
                } else {
                    Text("数据不足，无法分析")
                        .font(Typography.bodyMedium())
                        .foregroundStyle(colors.textSecondary)
                }
            }
        }
    }

    private func gradeColor(_ grade: HealthScorer.Grade) -> Color {
        switch grade {
        case .excellent: return colors.success
        case .good: return colors.accentSecondary
        case .fair: return colors.warning
        case .warning: return colors.danger
        }
    }
}

private struct DimensionBar: View {
    @Environment(\.colors) private var colors
    let dimension: HealthScorer.DimensionScore

    var body: some View {
        HStack(spacing: DesignTokens.Spacing.s8) {
            Text(dimension.id.rawValue)
                .font(Typography.labelMedium())
                .foregroundStyle(colors.textSecondary)
                .frame(width: 72, alignment: .leading)

            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule()
                        .fill(colors.surfaceElevated)
                        .frame(height: 6)
                    if let score = dimension.score {
                        Capsule()
                            .fill(colors.accent)
                            .frame(width: geo.size.width * CGFloat(score / 100), height: 6)
                    }
                }
            }
            .frame(height: 6)

            Text(dimension.score.map { String(format: "%.0f", $0) } ?? "—")
                .font(Typography.mono(11, .medium))
                .foregroundStyle(colors.textPrimary)
                .frame(width: 28, alignment: .trailing)

            if dimension.isReference {
                Text("参考")
                    .font(Typography.mono(9))
                    .foregroundStyle(colors.warning)
                    .frame(width: 26, alignment: .leading)
            } else {
                Spacer().frame(width: 26)
            }
        }
    }
}

// MARK: - 四分卡片

struct FeaturedMetricsGrid: View {
    @Environment(\.colors) private var colors
    let snapshot: DeviceSnapshot
    let tickMs: Double

    var body: some View {
        let columns = [GridItem(.flexible(), spacing: DesignTokens.Spacing.s8),
                       GridItem(.flexible(), spacing: DesignTokens.Spacing.s8)]
        LazyVGrid(columns: columns, spacing: DesignTokens.Spacing.s8) {
            FeaturedCell(label: "FPS", id: .fps, metric: snapshot.display.fps, tickMs: tickMs)
            FeaturedCell(label: "CPU 使用率", id: .cpuUsage, metric: snapshot.cpu.usagePercent, tickMs: tickMs)
            // iOS 无 CPU 温度：第四项降级为电量（README 已说明）
            FeaturedCell(label: "电量", id: .batteryLevel, metric: snapshot.battery.levelPercent, tickMs: tickMs)
            FeaturedCell(label: "内存占用", id: .memoryUsageRatio, metric: snapshot.memory.usageRatio, tickMs: tickMs)
        }
    }
}

private struct FeaturedCell: View {
    @Environment(\.colors) private var colors
    let label: String
    let id: MetricId
    let metric: Metric<Float>?
    let tickMs: Double

    var body: some View {
        GlassPanel(style: GlassStyle(cornerRadius: DesignTokens.Radius.small, outerShadow: false)) {
            NavigationLink(value: id) {
                VStack(alignment: .leading, spacing: DesignTokens.Spacing.s4) {
                    Text(label)
                        .font(Typography.labelMedium())
                        .foregroundStyle(colors.textSecondary)
                    MetricValueText(metricId: id, metric: metric, intervalMs: tickMs)
                }
            }
            .buttonStyle(.plain)
        }
    }
}

// MARK: - 建议面板

struct AdvicePanel: View {
    @Environment(\.colors) private var colors
    let advice: [AdviceEngine.Advice]

    var body: some View {
        GlassPanel {
            VStack(alignment: .leading, spacing: DesignTokens.Spacing.s8) {
                Text("智能建议")
                    .font(Typography.labelLarge(.medium))
                    .foregroundStyle(colors.textSecondary)
                if advice.isEmpty {
                    Text("暂无建议")
                        .font(Typography.bodyMedium())
                        .foregroundStyle(colors.textSecondary)
                } else {
                    ForEach(advice) { item in
                        HStack(alignment: .top, spacing: DesignTokens.Spacing.s8) {
                            Circle()
                                .fill(severityColor(item.severity))
                                .frame(width: 8, height: 8)
                                .padding(.top, 6)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(item.title)
                                    .font(Typography.labelLarge(.medium))
                                    .foregroundStyle(colors.textPrimary)
                                Text(item.detail)
                                    .font(Typography.labelMedium())
                                    .foregroundStyle(colors.textSecondary)
                            }
                        }
                    }
                }
            }
        }
    }

    private func severityColor(_ severity: AdviceEngine.Severity) -> Color {
        switch severity {
        case .info: return colors.accentSecondary
        case .warning: return colors.warning
        case .critical: return colors.danger
        }
    }
}

// MARK: - 全量指标行

struct MetricRow: View {
    @Environment(\.colors) private var colors
    let id: MetricId
    let metric: Metric<Float>?
    let tickMs: Double

    var body: some View {
        NavigationLink(value: id) {
            HStack {
                Circle()
                    .fill(colors.metricColor(id.colorKey))
                    .frame(width: 8, height: 8)
                Text(id.label)
                    .font(Typography.bodyMedium())
                    .foregroundStyle(colors.textPrimary)
                Spacer()
                MetricValueText(metricId: id, metric: metric, intervalMs: tickMs)
            }
        }
        .buttonStyle(.plain)
    }
}

// MARK: - 视图模型（仓库 + 分析的轻量封装）

@MainActor
final class OverviewViewModel: ObservableObject {
    @Published private(set) var snapshot: DeviceSnapshot = .empty()
    @Published private(set) var health: HealthScorer.HealthResult
    @Published private(set) var advice: [AdviceEngine.Advice] = []
    @Published private(set) var tickMs: Double = 1000

    private var timer: Timer?

    init() {
        let repository = MetricsRepository.shared
        _snapshot = Published(wrappedValue: repository.snapshot)
        _health = Published(wrappedValue: HealthScorer.score(repository.snapshot))
        start()
    }

    private func start() {
        MetricsRepository.shared.start()
        MetricsRepository.shared.sampleOnce()

        timer = Timer(timeInterval: 1, repeats: true) { [weak self] _ in
            Task { [weak self] @MainActor in
                guard let self else { return }
                let repository = MetricsRepository.shared
                self.snapshot = repository.snapshot
                self.health = HealthScorer.score(repository.snapshot)
                self.advice = AdviceEngine.analyze(snapshot: repository.snapshot, history: HistoryStore.shared)
                self.tickMs = repository.tickMs
            }
        }
        RunLoop.main.add(timer!, forMode: .common)
    }

    deinit {
        timer?.invalidate()
    }

    /// 某类别下的 Float 指标（非 Float 指标不在总览展示）。
    func metrics(for category: MetricCategory) -> [MetricId] {
        MetricId.allCases.filter { $0.category == category && snapshot.metricOf($0) != nil }
    }
}
