//
//  Visuals.swift
//  DevicePerfMonitor
//
//  自绘轻量视觉组件（对应 Android 端 ui/kit/MetricVisuals.kt）：
//  - Sparkline：迷你波形，指标卡里显示最近一分钟的走势；
//  - BarSeries：柱状轨迹；
//  - ProgressRing：环形进度（综合评判 / 有界指标）。
//
//  全部用 SwiftUI Canvas 自绘，不引第三方图表库（DGCharts 只在详情页
//  大图用）；数据不足两个点时不画线，交由调用方展示空态文案。
//

import SwiftUI

// MARK: - 迷你波形

struct Sparkline: View {
    let points: [Float]
    let color: Color
    var lineWidth: CGFloat = 2

    var body: some View {
        Canvas { context, size in
            guard points.count >= 2, size.width > 0, size.height > 0 else { return }
            let values = points
            let lo = values.min() ?? 0
            let hi = values.max() ?? 1
            let span = max(hi - lo, 0.0001)
            let stepX = size.width / CGFloat(values.count - 1)
            let pad = lineWidth
            let usableH = size.height - pad * 2

            var path = Path()
            for (index, value) in values.enumerated() {
                let x = CGFloat(index) * stepX
                let y = pad + usableH * CGFloat(1 - (value - lo) / span)
                if index == 0 {
                    path.move(to: CGPoint(x: x, y: y))
                } else {
                    path.addLine(to: CGPoint(x: x, y: y))
                }
            }
            context.stroke(path, with: .color(color), lineWidth: lineWidth)

            // 末端点：强调"最新值在这里"
            if let last = values.last {
                let x = size.width
                let y = pad + usableH * CGFloat(1 - (last - lo) / span)
                let dot = Path(ellipseIn: CGRect(
                    x: x - 3, y: y - 3, width: 6, height: 6
                ))
                context.fill(dot, with: .color(color))
            }
        }
    }
}

// MARK: - 柱状轨迹

struct BarSeries: View {
    let points: [Float]
    let color: Color
    var cornerRadius: CGFloat = 2

    var body: some View {
        Canvas { context, size in
            guard points.count >= 2, size.width > 0, size.height > 0 else { return }
            let values = points
            let lo = values.min() ?? 0
            let hi = values.max() ?? 1
            let span = max(hi - lo, 0.0001)
            let slot = size.width / CGFloat(values.count)
            let barWidth = max(slot * 0.62, 1.5)

            for (index, value) in values.enumerated() {
                let h = max(size.height * CGFloat((value - lo) / span), 2)
                let x = CGFloat(index) * slot + (slot - barWidth) / 2
                let rect = CGRect(x: x, y: size.height - h, width: barWidth, height: h)
                let path = Path(roundedRect: rect, cornerRadius: cornerRadius)
                context.fill(path, with: .color(color.opacity(0.85)))
            }
        }
    }
}

// MARK: - 环形进度

struct ProgressRing: View {
    /// 0~1；采不到时传 0，环留空不画占位数字。
    var progress: Float
    /// 圆心文字（空串 = 不画）。
    var centerText: String
    var caption: String = ""
    var color: Color
    var trackColor: Color
    var lineWidth: CGFloat = 10

    var body: some View {
        Canvas { context, size in
            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            let radius = min(size.width, size.height) / 2 - lineWidth / 2

            // 底环
            let track = Path { p in
                p.addArc(
                    center: center,
                    radius: radius,
                    startAngle: .degrees(0),
                    endAngle: .degrees(360),
                    clockwise: false
                )
            }
            context.stroke(track, with: .color(trackColor), lineWidth: lineWidth)

            // 进度环（从 12 点方向顺时针）
            let clamped = min(max(progress, 0), 1)
            if clamped > 0 {
                let arc = Path { p in
                    p.addArc(
                        center: center,
                        radius: radius,
                        startAngle: .degrees(-90),
                        endAngle: .degrees(-90 + 360 * Double(clamped)),
                        clockwise: false
                    )
                }
                context.stroke(arc, with: .color(color), style: StrokeStyle(
                    lineWidth: lineWidth, lineCap: .round
                ))
            }
        }
        .overlay(alignment: .center) {
            if !centerText.isEmpty || !caption.isEmpty {
                VStack(spacing: 1) {
                    if !centerText.isEmpty {
                        Text(centerText)
                            .font(.system(size: centerFontSize, design: .monospaced))
                            .fontWeight(.semibold)
                            .foregroundStyle(color)
                    }
                    if !caption.isEmpty {
                        Text(caption)
                            .font(.system(size: captionFontSize))
                            .foregroundStyle(trackColor)
                    }
                }
            }
        }
    }

    /// 圆心字号（默认按 128pt 环配 34pt 数字；小环传小值）。
    var centerFontSize: CGFloat = 34
    var captionFontSize: CGFloat = 11
}
