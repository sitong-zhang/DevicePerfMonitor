//
//  Controls.swift
//  DevicePerfMonitor
//
//  自封装液态玻璃控件（对应需求「禁止系统默认控件与默认蓝」）：
//  GlassButton / GlassSwitch / GlassSlider / GlassTabs / GlassSectionTitle。
//  数值显示统一走 MonoNumber（等宽字体 + 缓动打断策略）。
//
//  数值缓动规范（design-tokens.json number）：
//  时长 = min(300ms, 刷新间隔 - 50ms)；新数据到达时从当前显示值继续，
//  不重播动画（SwiftUI .animation 默认从当前值插值，天然满足）。
//

import SwiftUI

// MARK: - 玻璃按钮

struct GlassButton: View {
    @Environment(\.colors) private var colors
    var text: String
    var icon: String? = nil
    var compact: Bool = false
    var destructive: Bool = false
    var action: () -> Void

    init(text: String, icon: String? = nil, compact: Bool = false, destructive: Bool = false, action: @escaping () -> Void) {
        self.text = text
        self.icon = icon
        self.compact = compact
        self.destructive = destructive
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: DesignTokens.Spacing.s4) {
                if let icon {
                    Image(systemName: icon)
                        .font(.system(size: compact ? 12 : 14, weight: .medium))
                }
                Text(text)
                    .font(compact ? Typography.labelLarge(.medium) : Typography.titleMedium(.medium))
            }
            .foregroundStyle(destructive ? colors.danger : colors.textPrimary)
            .padding(.horizontal, compact ? DesignTokens.Spacing.s12 : DesignTokens.Spacing.s16)
            .padding(.vertical, compact ? DesignTokens.Spacing.s8 : DesignTokens.Spacing.s12)
            .liquidGlass(GlassStyle(
                material: .systemThinMaterial,
                surfaceAlpha: DesignTokens.Glass.BackgroundAlpha.min,
                cornerRadius: compact ? DesignTokens.Radius.small : DesignTokens.Radius.card,
                outerShadow: false
            ))
        }
        .buttonStyle(.plain)
    }
}

// MARK: - 玻璃开关（自绘，非 UISwitch）

struct GlassSwitch: View {
    @Environment(\.colors) private var colors
    @Binding var isOn: Bool
    var tint: Color? = nil

    var body: some View {
        Button {
            withAnimation(DesignTokens.Motion.Emphasized.easing) {
                isOn.toggle()
            }
        } label: {
            ZStack(alignment: isOn ? .trailing : .leading) {
                Capsule()
                    .fill(isOn ? (tint ?? colors.accent).opacity(0.35) : colors.surfaceElevated.opacity(0.6))
                Circle()
                    .fill(isOn ? (tint ?? colors.accent) : colors.textSecondary)
                    .padding(3)
                    .frame(width: 26, height: 26)
            }
            .frame(width: 52, height: 32)
            .liquidGlass(GlassStyle(
                material: .systemThinMaterial,
                surfaceAlpha: DesignTokens.Glass.BackgroundAlpha.min,
                cornerRadius: DesignTokens.Radius.pill,
                outerShadow: false
            ))
        }
        .buttonStyle(.plain)
        .accessibilityValue(isOn ? "开" : "关")
    }
}

// MARK: - 玻璃滑块（自绘，非 UISlider）

struct GlassSlider: View {
    @Environment(\.colors) private var colors
    @Binding var value: Double
    var range: ClosedRange<Double>
    var steps: Int = 0
    /// 显示文本（左侧）。
    var label: String? = nil
    /// 数值文本（右侧，由调用方格式化）。
    var valueText: String

    var body: some View {
        VStack(alignment: .leading, spacing: DesignTokens.Spacing.s8) {
            if let label {
                HStack {
                    Text(label)
                        .font(Typography.labelLarge(.medium))
                        .foregroundStyle(colors.textSecondary)
                    Spacer()
                    Text(valueText)
                        .font(Typography.mono(13, .medium))
                        .foregroundStyle(colors.textPrimary)
                }
            }
            GeometryReader { geo in
                let width = geo.size.width
                let thumb: CGFloat = 24
                let fraction = range.spanFraction(value)
                ZStack(alignment: .leading) {
                    // 轨道
                    Capsule()
                        .fill(colors.surfaceElevated.opacity(0.8))
                        .frame(height: 6)
                    // 已填充
                    Capsule()
                        .fill(colors.accent)
                        .frame(width: max(0, width * fraction), height: 6)
                    // 滑块
                    Circle()
                        .fill(colors.textPrimary)
                        .frame(width: thumb, height: thumb)
                        .shadow(color: .black.opacity(0.25), radius: 4, y: 2)
                        .offset(x: (width - thumb) * fraction)
                }
                .frame(height: thumb)
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { drag in
                            let raw = range.lowerBound + (range.upperBound - range.lowerBound)
                                * Double((drag.location.x - thumb / 2) / max(1, width - thumb)).clamped(to: 0...1)
                            value = steps > 0 ? raw.quantized(steps: steps, range: range) : raw
                        }
                )
                .accessibilityValue(valueText)
            }
            .frame(height: 24)
        }
    }
}

extension ClosedRange where Bound == Double {
    /// 归一化到 0...1。
    func spanFraction(_ value: Double) -> Double {
        guard upperBound > lowerBound else { return 0 }
        return (min(max(value, lowerBound), upperBound) - lowerBound) / (upperBound - lowerBound)
    }
}

extension Double {
    func clamped(to range: ClosedRange<Double>) -> Double {
        min(max(self, range.lowerBound), range.upperBound)
    }

    /// 按步数量化（steps 为总档位数 - 1）。
    func quantized(steps: Int, range: ClosedRange<Double>) -> Double {
        guard steps > 0, range.upperBound > range.lowerBound else { return self }
        let stepValue = (range.upperBound - range.lowerBound) / Double(steps)
        let index = ((self - range.lowerBound) / stepValue).rounded()
        return range.lowerBound + index * stepValue
    }
}

// MARK: - 玻璃分段标签页（自绘，非 UISegmentedControl）

struct GlassTabs: View {
    @Environment(\.colors) private var colors
    var options: [String]
    @Binding var selectedIndex: Int

    var body: some View {
        HStack(spacing: DesignTokens.Spacing.s4) {
            ForEach(Array(options.enumerated()), id: \.offset) { index, option in
                Button {
                    withAnimation(DesignTokens.Motion.Emphasized.easing) {
                        selectedIndex = index
                    }
                } label: {
                    Text(option)
                        .font(Typography.labelLarge(selectedIndex == index ? .semibold : .regular))
                        .foregroundStyle(selectedIndex == index ? colors.textPrimary : colors.textSecondary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, DesignTokens.Spacing.s8)
                        .background(
                            Group {
                                if selectedIndex == index {
                                    RoundedRectangle(cornerRadius: DesignTokens.Radius.small, style: .continuous)
                                        .fill(colors.accent.opacity(0.22))
                                }
                            }
                        )
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(DesignTokens.Spacing.s4)
        .liquidGlass(GlassStyle(
            material: .systemThinMaterial,
            surfaceAlpha: DesignTokens.Glass.BackgroundAlpha.min,
            cornerRadius: DesignTokens.Radius.small,
            outerShadow: false
        ))
    }
}

// MARK: - 分区标题

struct GlassSectionTitle: View {
    @Environment(\.colors) private var colors
    var text: String

    var body: some View {
        Text(text)
            .font(Typography.labelLarge(.semibold))
            .foregroundStyle(colors.textSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, DesignTokens.Spacing.s8)
    }
}

// MARK: - 等宽数值（缓动打断策略）

/// 数值缓动：时长 = min(300ms, 刷新间隔 - 50ms)，从当前显示值继续插值。
struct MonoNumber: View {
    var text: String
    var color: Color
    var font: Font

    init(text: String, color: Color, font: Font = Typography.headlineMedium(.bold)) {
        self.text = text
        self.color = color
        self.font = font
    }

    var body: some View {
        Text(text)
            .font(font)
            .foregroundStyle(color)
            .monospacedDigit()
            .contentTransition(.numericText(value: Double(text) ?? 0))
            .animation(.snappy(duration: 0.2), value: text)
    }
}

/// 带动画的数值显示（数值变化时按 design-tokens 规范缓动）。
struct AnimatedNumber: View {
    var value: Double
    var decimals: Int
    var color: Color
    var font: Font
    /// 采集刷新间隔（ms），用于计算缓动时长。
    var intervalMs: Double = 1000

    @State private var displayValue: Double = 0
    @State private var lastStart: Date = .distantPast

    var body: some View {
        MonoNumber(
            text: Self.format(displayValue, decimals: decimals),
            color: color,
            font: font
        )
        .onAppear {
            displayValue = value
        }
        .onChange(of: value) { newValue in
            let duration = DesignTokens.Number.duration(intervalMs: intervalMs)
            withAnimation(.easeOut(duration: duration)) {
                displayValue = newValue
            }
        }
    }

    static func format(_ value: Double, decimals: Int) -> String {
        if decimals <= 0 { return String(Int(value.rounded())) }
        return String(format: "%.\(decimals)f", value)
    }
}
