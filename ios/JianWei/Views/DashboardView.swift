import SwiftUI

struct DashboardView: View {
	@EnvironmentObject private var vm: MetricsViewModel

	var body: some View {
		NavigationView {
			ScrollView {
				VStack(spacing: 18) {
					if let s = vm.latest {
						ScoreCard(score: s.score)
						LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 14) {
							MetricTile(title: "CPU 占用", value: String(format: "%.0f%%", s.cpu), systemImage: "cpu")
							MetricTile(title: "内存", value: String(format: "%.0f / %.0f MB", s.memoryUsedMB, s.memoryTotalMB), systemImage: "memorychip")
							MetricTile(title: "电池", value: String(format: "%.0f%%", s.battery * 100), systemImage: "battery.100")
							MetricTile(title: "帧率", value: String(format: "%.0f / %.0f", s.fps, s.maxFps), systemImage: "waveform")
							MetricTile(title: "存储", value: String(format: "%.1f / %.1f GB", s.storageUsedGB, s.storageTotalGB), systemImage: "internaldrive")
							MetricTile(title: "温度", value: "iOS 不支持", systemImage: "thermometer")
						}
					} else {
						ProgressView("正在采集…")
							.padding(.top, 40)
					}
				}
				.padding()
			}
			.navigationTitle("观序 · 监测")
		}
	}
}

struct ScoreCard: View {
	let score: Int
	var body: some View {
		VStack(spacing: 6) {
			Text("\(score)")
				.font(.system(size: 56, weight: .bold, design: .rounded))
			Text("综合健康分")
				.font(.subheadline)
				.foregroundStyle(.secondary)
			Text("iOS 不暴露设备温度，评分基于 流畅度 / 内存 / 电量")
				.font(.caption2)
				.foregroundStyle(.secondary)
		}
		.frame(maxWidth: .infinity)
		.padding(.vertical, 22)
		.background(RoundedRectangle(cornerRadius: 18).fill(.ultraThinMaterial))
	}
}

struct MetricTile: View {
	let title: String
	let value: String
	let systemImage: String
	var body: some View {
		VStack(alignment: .leading, spacing: 8) {
			HStack {
				Image(systemName: systemImage).foregroundStyle(.blue)
				Text(title).font(.caption).foregroundStyle(.secondary)
			}
			Text(value).font(.system(size: 18, weight: .semibold))
		}
		.frame(maxWidth: .infinity, alignment: .leading)
		.padding(14)
		.background(RoundedRectangle(cornerRadius: 14).fill(Color(.secondarySystemBackground)))
	}
}
