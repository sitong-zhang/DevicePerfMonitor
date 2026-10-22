//
//  MetricChartView.swift
//  DevicePerfMonitor
//
//  指标历史折线图：danielgindi/Charts（Apache-2.0）。
//
//  设计约束（与 Android 端 Vico 封装一致）：
//  - 线条/文字颜色跟随液态玻璃主题（Catppuccin 语义色），不使用库默认蓝；
//  - 空数据显示占位文案（三态规范：数据不足不画假曲线）；
//  - x 轴为相对时间（秒），y 轴为指标值；
//  - 关闭图例/网格/右轴/描述，只保留必要的坐标文字。
//

import SwiftUI
import Charts

struct MetricChartView: View {
    @Environment(\.colors) private var colors
    let points: [HistoryPoint]

    var body: some View {
        if points.count < 2 {
            Text("数据不足，无法绘制图表")
                .font(Typography.bodyMedium())
                .foregroundStyle(colors.textSecondary)
                .frame(height: 180)
                .frame(maxWidth: .infinity)
        } else {
            LineChartRepresentable(points: points, lineColor: colors.accent, textColor: colors.textSecondary)
                .frame(height: 180)
        }
    }
}

// MARK: - UIViewRepresentable 封装

private struct LineChartRepresentable: UIViewRepresentable {
    let points: [HistoryPoint]
    let lineColor: UIColor
    let textColor: UIColor

    func makeUIView(context: Context) -> LineChartView {
        let chart = LineChartView()
        chart.chartDescription.enabled = false
        chart.legend.enabled = false
        chart.rightAxis.enabled = false
        chart.leftAxis.enabled = false
        chart.xAxis.enabled = false
        chart.drawGridBackgroundEnabled = false
        chart.dragEnabled = true
        chart.setScaleEnabled(true)
        chart.pinchZoomEnabled = true
        chart.doubleTapToZoomEnabled = false
        chart.highlightPerTapEnabled = true
        chart.highlightPerDragEnabled = false
        chart.noDataText = "数据不足，无法绘制图表"
        chart.noDataTextColor = textColor
        chart.minOffset = 0
        return chart
    }

    func updateUIView(_ chart: LineChartView, context: Context) {
        let base = points.first!.timestampMs
        let entries = points.map { point in
            ChartDataEntry(
                x: Double(point.timestampMs - base) / 1000.0,
                y: Double(point.value)
            )
        }
        let dataSet = LineChartDataSet(entries: entries, label: "")
        dataSet.drawCirclesEnabled = false
        dataSet.drawValuesEnabled = false
        dataSet.lineWidth = 2
        dataSet.mode = .cubicBezier
        dataSet.drawHorizontalHighlightIndicatorEnabled = false
        dataSet.highlightColor = textColor
        dataSet.highlightLineWidth = 0.8

        // 线条主题色 + 渐变填充（同色系低透明度，不用库默认蓝）
        dataSet.setColor(lineColor.withAlphaComponent(1.0))
        dataSet.fillColor = lineColor.withAlphaComponent(0.15)
        dataSet.drawFilledEnabled = true

        chart.data = LineChartData(dataSet: dataSet)
        chart.notifyDataSetChanged()
    }
}

/// 图表下方的时间跨度说明。
struct ChartCaption: View {
    @Environment(\.colors) private var colors
    let points: [HistoryPoint]

    var body: some View {
        if points.count >= 2 {
            let spanSec = (points.last!.timestampMs - points.first!.timestampMs) / 1000
            Text("最近 \(spanSec) 秒 · \(points.count) 个采样点")
                .font(Typography.labelMedium())
                .foregroundStyle(colors.textSecondary)
        } else {
            EmptyView()
        }
    }
}
