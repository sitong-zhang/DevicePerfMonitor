import SwiftUI
import Charts

struct HistoryView: View {
	@State private var range: Int = 60
	@State private var samples: [MetricSample] = []

	var body: some View {
		NavigationView {
			ScrollView {
				VStack(alignment: .leading, spacing: 18) {
					Picker("时间范围", selection: $range) {
						Text("10 分钟").tag(10)
						Text("60 分钟").tag(60)
						Text("120 分钟").tag(120)
					}
					.pickerStyle(.segmented)
					.onChange(of: range) { _ in reload() }

					if samples.isEmpty {
						Text("暂无数据，请先在「监测」页运行一会儿。")
							.foregroundStyle(.secondary)
							.padding(.top, 20)
					} else {
						ChartCard(title: "CPU 占用 %", data: samples, keyPath: \.cpu)
						ChartCard(title: "内存 MB", data: samples, keyPath: \.memoryUsedMB)
						ChartCard(title: "帧率", data: samples, keyPath: \.fps)
					}
				}
				.padding()
			}
			.navigationTitle("观序 · 历史")
			.onAppear { reload() }
		}
	}

	private func reload() {
		samples = MetricsStore.shared.recent(range)
	}
}

struct ChartCard: View {
	let title: String
	let data: [MetricSample]
	let keyPath: KeyPath<MetricSample, Double>
	var body: some View {
		VStack(alignment: .leading) {
			Text(title).font(.subheadline).foregroundStyle(.secondary)
			Chart(data) { s in
				LineMark(
					x: .value("时间", s.date),
					y: .value(title, s[keyPath: keyPath])
				)
				.interpolationMethod(.catmullRom)
				.foregroundStyle(.blue)
			}
			.frame(height: 120)
		}
		.padding(14)
		.background(RoundedRectangle(cornerRadius: 14).fill(Color(.secondarySystemBackground)))
	}
}
