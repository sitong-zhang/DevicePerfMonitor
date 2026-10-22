//
//  LiquidGlass.swift
//  DevicePerfMonitor
//
//  液态玻璃材质：iOS 原生实现（与 Android 端 1:1 同款质感）。
//
//  图层构成（对应 design-tokens.json glass 参数，禁止纯色半透明冒充）：
//   1. UIVisualEffectView 系统毛玻璃（真实模糊，非假效果）；
//   2. 半透明色底（backgroundAlpha 18%~28%，随告警态/主题取值）；
//   3. 1px 半透明白高光描边（顶亮底暗渐变）；
//   4. 顶部内高光（highlightAlphaTop 0.55 → 0 渐变，裁剪在圆角内）；
//   5. 底部极淡内阴影（innerShadowAlpha 0.10）；
//   6. 柔和弥散外阴影（outerShadowRadius 20 / offsetY 8 / alpha 0.22）；
//   7. 程序化噪点（noiseAlpha 0.035，seeded 生成 64×64 tile 缓存复用，
//      overlay 混合模式 —— 真实纹理，非预置贴图）。
//
//  降级说明（README 同步记录）：
//   - iOS 系统毛玻璃档位固定（UIVisualEffectView 无公开半径 API），
//     无法像 Android backdrop 那样精确指定 28~36pt 半径，取最接近档位；
//   - 系统级悬浮窗在 iOS 不存在（平台限制），应用内悬浮窗同样走本材质，
//     因窗口层级在 App 内，UIVisualEffectView 可真实采样背后画面。
//

import SwiftUI

// MARK: - 玻璃样式参数

struct GlassStyle {
    /// 系统毛玻璃档位（半径由系统决定，见文件头降级说明）。
    var material: UIBlurEffect.Style = .systemThinMaterial
    /// 底色透明度（design-tokens glass.backgroundAlpha）。
    var surfaceAlpha: CGFloat = (DesignTokens.Glass.BackgroundAlpha.min + DesignTokens.Glass.BackgroundAlpha.max) / 2
    /// 圆角。
    var cornerRadius: CGFloat = DesignTokens.Radius.card
    /// 告警态整体染色（nil = 不染色）。
    var alertTint: Color? = nil
    /// 是否绘制外阴影（嵌入父卡片时通常关闭）。
    var outerShadow: Bool = true

    static let card = GlassStyle()
    static let overlay = GlassStyle(
        material: .systemThinMaterial,
        surfaceAlpha: DesignTokens.Glass.BackgroundAlpha.min,
        cornerRadius: DesignTokens.Radius.card
    )
}

// MARK: - 原生毛玻璃（UIVisualEffectView 封装）

struct VisualEffectBlur: UIViewRepresentable {
    var material: UIBlurEffect.Style

    func makeUIView(context: Context) -> UIVisualEffectView {
        UIVisualEffectView(effect: UIBlurEffect(style: material))
    }

    func updateUIView(_ uiView: UIVisualEffectView, context: Context) {
        // 样式变化时重建 effect（UIVisualEffectView 要求 effect 整体替换）
        if uiView.effect == nil || !effectEquals(uiView.effect, material) {
            uiView.effect = UIBlurEffect(style: material)
        }
    }

    private func effectEquals(_ effect: UIVisualEffect?, _ style: UIBlurEffect.Style) -> Bool {
        guard let blur = effect as? UIBlurEffect else { return false }
        return blur.style == style
    }
}

// MARK: - 程序化噪点（seeded 生成 + 缓存）

enum NoiseTexture {
    /// 64×64 灰度噪点 tile，seeded 生成保证每次运行一致。
    static let tile: UIImage = {
        let side = 64
        var generator = SeededGenerator(seed: 20_260_102)
        UIGraphicsBeginImageContextWithOptions(CGSize(width: side, height: side), false, 0)
        for y in 0..<side {
            for x in 0..<side {
                // -1.0 ~ 1.0 的确定性伪随机灰度
                let v = CGFloat(generator.next() % 256) / 255.0
                UIColor(white: v, alpha: 1).setFill()
                UIRectFill(CGRect(x: x, y: y, width: 1, height: 1))
            }
        }
        let image = UIGraphicsGetImageFromCurrentImageContext() ?? UIImage()
        UIGraphicsEndImageContext()
        return image
    }()
}

/// xorshift 确定性随机源（避免每次采样变化导致噪点闪烁）。
struct SeededGenerator: RandomNumberGenerator {
    private var state: UInt64

    init(seed: UInt64) {
        state = seed == 0 ? 0x9E3779B97F4A7C15 : seed
    }

    mutating func next() -> UInt64 {
        state ^= state << 13
        state ^= state >> 7
        state ^= state << 17
        return state
    }
}

// MARK: - 液态玻璃表面（ViewModifier）

struct LiquidGlassSurface: ViewModifier {
    let style: GlassStyle
    /// 染色基色（默认 surface 语义色；告警态由调用方传 danger）。
    var tint: Color

    init(style: GlassStyle, tint: Color? = nil) {
        self.style = style
        self.tint = tint ?? DesignTokens.Dark.surface
    }

    func body(content: Content) -> some View {
        let base = tint
        let radius = style.cornerRadius
        let g = DesignTokens.Glass.self

        content
            .background(
                ZStack {
                    // 1. 原生毛玻璃（真实模糊）
                    VisualEffectBlur(material: style.material)
                        .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))

                    // 2. 半透明色底
                    base.opacity(style.surfaceAlpha)
                        .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))

                    // 3 + 4 + 5. 描边 / 内高光 / 内阴影（裁剪在圆角内）
                    RoundedRectangle(cornerRadius: radius, style: .continuous)
                        .fill(
                            LinearGradient(
                                stops: [
                                    .init(color: Color.white.opacity(g.HighlightAlphaTop), location: 0),
                                    .init(color: Color.white.opacity(g.HighlightAlphaTop * 0.25), location: 0.35),
                                    .init(color: Color.clear, location: 0.6),
                                    .init(color: Color.black.opacity(g.InnerShadowAlpha), location: 1),
                                ],
                                startPoint: .top,
                                endPoint: .bottom
                            )
                        )
                        .allowsHitTesting(false)

                    RoundedRectangle(cornerRadius: radius, style: .continuous)
                        .strokeBorder(
                            LinearGradient(
                                stops: [
                                    .init(color: Color.white.opacity(g.BorderAlpha), location: 0),
                                    .init(color: Color.white.opacity(g.BorderAlpha * 0.25), location: 0.5),
                                    .init(color: Color.black.opacity(g.BorderAlpha * 0.3), location: 1),
                                ],
                                startPoint: .top,
                                endPoint: .bottom
                            ),
                            lineWidth: g.BorderWidth
                        )
                        .allowsHitTesting(false)

                    // 7. 程序化噪点（overlay 混合，真实纹理）
                    Image(uiImage: NoiseTexture.tile)
                        .resizable(resizingMode: .tile)
                        .opacity(g.NoiseAlpha)
                        .blendMode(.overlay)
                        .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
                        .allowsHitTesting(false)
                }
            )
            .compositingGroup()
            .shadow(
                color: Color.black.opacity(style.outerShadow ? g.OuterShadowAlpha : 0),
                radius: style.outerShadow ? g.OuterShadowRadius : 0,
                x: 0,
                y: style.outerShadow ? g.OuterShadowOffsetY : 0
            )
    }
}

extension View {
    /// 套用液态玻璃材质。
    func liquidGlass(_ style: GlassStyle = .card, tint: Color? = nil) -> some View {
        modifier(LiquidGlassSurface(style: style, tint: tint))
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
            .liquidGlass(alert ? GlassStyle(material: style.material,
                                            surfaceAlpha: DesignTokens.Glass.BackgroundAlpha.max,
                                            cornerRadius: style.cornerRadius,
                                            alertTint: colors.danger,
                                            outerShadow: style.outerShadow) : style,
                         tint: alert ? colors.danger : colors.surface)
    }
}
