//
//  LiquidGlass.swift
//  DevicePerfMonitor
//
//  液态玻璃材质：照搬开源库 LiquidGlassKit（MIT，muhittincamdali/LiquidGlassKit，
//  SPM 依赖，版本见 generate_xcodeproj.py 的 LIQUIDGLASS_MIN_VERSION）。
//
//  为什么换掉自绘实现：旧版是 UIVisualEffectView + 7 层自绘叠加（半透明底 /
//  高光渐变 / 描边 / 噪点 / 内阴影 / 外阴影），有两个绕不开的短板——
//  1. UIVisualEffectView 没有公开的模糊半径 API，只能取系统固定档位，
//     与 design-tokens 的 28~36pt 对不上；
//  2. iOS 26 起苹果官方推出真正的液态玻璃（glassEffect：折射 + 高光 +
//     交互形变），自绘叠加永远追不平原生质感。
//
//  库的行为（照搬，不重造）：
//  - iOS 26+：走 SwiftUI 原生 glassEffect（库内 #available 判断，自动）；
//  - iOS 17~25：走库的纯 SwiftUI fallback（Material blur + tint + 边缘高光 + 阴影）；
//  - 无障碍：Reduce Transparency 自动换不透明底、Increase Contrast 加强边缘、
//    Reduce Motion 停高光动画——这些旧版自绘全都没有。
//
//  本文件只做三件事：把我们的语义参数（GlassStyle）映射到库的
//  GlassMaterial / GlassShape，保留 GlassPanel 容器 API，让 19 处
//  @Environment(\.colors) 调用方与全部 Screens/UIKit/Overlay 零改动。
//
//  归因（MIT 要求）：随附库的 LICENSE 见 SPM 检出目录；本文件为适配层，
//  非库代码衍生。
//

import SwiftUI
import LiquidGlassKit

// MARK: - 玻璃样式参数

struct GlassStyle {
    /// 玻璃材质（库的四种档位：clear / frosted / tinted / dark）。
    var material: GlassMaterial = .frosted
    /// 染色基色透明度（tinted 材质使用，取值 design-tokens glass.backgroundAlpha）。
    var surfaceAlpha: CGFloat = DesignTokens.Glass.BackgroundAlpha.min
    /// 圆角（design-tokens radius；>= 999 映射为胶囊形）。
    var cornerRadius: CGFloat = DesignTokens.Radius.card
    /// 告警态整体染色（nil = 不染色，用语义 surface 色）。
    var alertTint: Color? = nil
    /// 是否绘制外阴影（嵌入父卡片时通常关闭）。
    var outerShadow: Bool = true

    static let card = GlassStyle()

    /// 悬浮窗预设：最透明的 clear 档，透视后方应用。
    static let overlay = GlassStyle(
        material: .clear,
        surfaceAlpha: DesignTokens.Glass.BackgroundAlpha.min,
        cornerRadius: DesignTokens.Radius.card
    )
}

/// Radius.pill（999）→ 胶囊形；其余 → 连续圆角矩形。与旧自绘版的取形逻辑一致。
private func glassShape(cornerRadius: CGFloat) -> GlassShape {
    cornerRadius >= DesignTokens.Radius.pill - 1 ? .capsule : .roundedRectangle(cornerRadius: cornerRadius)
}

// MARK: - 液态玻璃表面（View 扩展，API 与旧版兼容）

extension View {
    /// 套用液态玻璃材质（库驱动：iOS 26 原生 glassEffect，旧系统 Material fallback）。
    func liquidGlass(_ style: GlassStyle = .card, tint: Color? = nil) -> some View {
        let base = tint ?? DesignTokens.Dark.surface
        let material: GlassMaterial
        if let alert = style.alertTint {
            material = .tinted(alert)
        } else {
            material = .tinted(base.opacity(style.surfaceAlpha))
        }
        return self
            .glassSurface(material, in: glassShape(cornerRadius: style.cornerRadius))
            .shadow(
                color: Color.black.opacity(style.outerShadow ? DesignTokens.Glass.outerShadowAlpha : 0),
                radius: style.outerShadow ? DesignTokens.Glass.outerShadowRadius : 0,
                x: 0,
                y: style.outerShadow ? DesignTokens.Glass.outerShadowOffsetY : 0
            )
    }
}

// MARK: - 玻璃面板（最常用容器）

struct GlassPanel<Content: View>: View {
    @Environment(\.colors) private var colors
    var style: GlassStyle = .card
    var alert: Bool = false
    @ViewBuilder var content: Content

    init(style: GlassStyle = .card, alert: Bool = false, @ViewBuilder content: () -> Content) {
        self.style = style
        self.alert = alert
        self.content = content()
    }

    var body: some View {
        content
            .padding(DesignTokens.Spacing.s16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .liquidGlass(alert
                         ? GlassStyle(material: .tinted(colors.danger),
                                      surfaceAlpha: DesignTokens.Glass.BackgroundAlpha.max,
                                      cornerRadius: style.cornerRadius,
                                      outerShadow: style.outerShadow)
                         : style,
                         tint: alert ? colors.danger : colors.surface)
    }
}
