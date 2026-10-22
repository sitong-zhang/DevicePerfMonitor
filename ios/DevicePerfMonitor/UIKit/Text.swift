//
//  Text.swift
//  DevicePerfMonitor
//
//  指标数值显示（三态渲染，对应 Android 端 ui/kit/Text.kt）：
//  - available：等宽字体 + 单位（数值缓动见 Controls.swift AnimatedNumber）；
//  - unavailable：显示"—"，点击可查看原因（tooltip 由长按提供）；
//  - needsPermission：显示"需授权"（iOS 当前无此态，保留兼容）。
//

import SwiftUI

struct MetricValueText: View {
    @Environment(\.colors) private var colors
    let metricId: MetricId
    let metric: Metric<Float>?
    var intervalMs: Double = 1000
    var onPermissionAction: ((PermissionAction) -> Void)? = nil

    var body: some View {
        switch metric?.state {
        case .available(let value):
            HStack(alignment: .firstTextBaseline, spacing: 2) {
                AnimatedNumber(
                    value: Double(value),
                    decimals: metricId.decimals,
                    color: colors.metricColor(metricId.colorKey),
                    font: Typography.headlineSmall(.bold),
                    intervalMs: intervalMs
                )
                Text(metricId.unit)
                    .font(Typography.labelMedium())
                    .foregroundStyle(colors.textSecondary)
            }
        case .unavailable(let reason):
            VStack(alignment: .trailing, spacing: 1) {
                Text("—")
                    .font(Typography.headlineSmall(.bold))
                    .foregroundStyle(colors.textSecondary)
                Text(reason)
                    .font(Typography.mono(9))
                    .foregroundStyle(colors.textSecondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
        case .needsPermission(let action, _):
            Button("需授权") { onPermissionAction?(action) }
                .font(Typography.labelMedium(.medium))
                .foregroundStyle(colors.warning)
        case .none:
            Text("—")
                .font(Typography.headlineSmall(.bold))
                .foregroundStyle(colors.textSecondary)
        }
    }
}
