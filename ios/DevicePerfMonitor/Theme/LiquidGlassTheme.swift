//
//  LiquidGlassTheme.swift
//  DevicePerfMonitor
//
//  主题：液态玻璃视觉体系（与 Android 端 1:1 同源）。
//
//  设计原则（对应需求「禁止系统默认控件与默认蓝」）：
//  1. 配色完全来自 design-tokens.json（Catppuccin Mocha / Latte），
//     由 Tools/generate_tokens.py 生成 DesignTokens.generated.swift；
//  2. 禁用系统动态取色与默认蓝；所有视觉表现由 UIKit 层自封装组件承载；
//  3. 字体：Inter（界面）+ JetBrains Mono（数值），均随工程打包（OFL-1.1，
//     许可证见仓库 licenses/）。字体文件放在 Resources/Fonts，
//     由 Info.plist 的 UIAppFonts 注册。
//

import SwiftUI
import CoreText

/// 深色 / 浅色 / 跟随系统。
enum ThemeMode: String, CaseIterable, Identifiable {
    case dark, light, auto

    var id: String { rawValue }

    var label: String {
        switch self {
        case .dark: return "深色"
        case .light: return "浅色"
        case .auto: return "跟随系统"
        }
    }
}

// MARK: - 语义色值包

/// 语义色（对应 Android 端 TokenColors）。值类型，便于存入 Environment。
///
/// 注意：这个 struct 是**手写**的，不是生成物——新增色 role 时必须同步补字段
/// 和下面两个构造，漏一个 iOS 直接编译不过。字段顺序与
/// `DesignTokens.Dark/Light`（由 generate_tokens.py 生成）保持一致。
struct TokenPalette {
    let background: Color
    let surface: Color
    let surfaceElevated: Color
    let textPrimary: Color
    let textSecondary: Color
    let accent: Color
    let accentSecondary: Color
    let success: Color
    let warning: Color
    let danger: Color
    // 1.0.9 新增：层次与描边（撑起卡片 / 底栏 / 分隔线的视觉层次）
    let surfaceLow: Color
    let surfaceContainer: Color
    let surfaceHigh: Color
    let surfaceHighest: Color
    let surfaceDim: Color
    let surfaceBright: Color
    let tertiary: Color
    let onTertiary: Color
    let tertiaryContainer: Color
    let onTertiaryContainer: Color
    let outline: Color
    let outlineVariant: Color
    let scrim: Color
    let inverseSurface: Color

    static let dark = TokenPalette(
        background: DesignTokens.Dark.background,
        surface: DesignTokens.Dark.surface,
        surfaceElevated: DesignTokens.Dark.surfaceElevated,
        textPrimary: DesignTokens.Dark.textPrimary,
        textSecondary: DesignTokens.Dark.textSecondary,
        accent: DesignTokens.Dark.accent,
        accentSecondary: DesignTokens.Dark.accentSecondary,
        success: DesignTokens.Dark.success,
        warning: DesignTokens.Dark.warning,
        danger: DesignTokens.Dark.danger,
        surfaceLow: DesignTokens.Dark.surfaceLow,
        surfaceContainer: DesignTokens.Dark.surfaceContainer,
        surfaceHigh: DesignTokens.Dark.surfaceHigh,
        surfaceHighest: DesignTokens.Dark.surfaceHighest,
        surfaceDim: DesignTokens.Dark.surfaceDim,
        surfaceBright: DesignTokens.Dark.surfaceBright,
        tertiary: DesignTokens.Dark.tertiary,
        onTertiary: DesignTokens.Dark.onTertiary,
        tertiaryContainer: DesignTokens.Dark.tertiaryContainer,
        onTertiaryContainer: DesignTokens.Dark.onTertiaryContainer,
        outline: DesignTokens.Dark.outline,
        outlineVariant: DesignTokens.Dark.outlineVariant,
        scrim: DesignTokens.Dark.scrim,
        inverseSurface: DesignTokens.Dark.inverseSurface
    )

    static let light = TokenPalette(
        background: DesignTokens.Light.background,
        surface: DesignTokens.Light.surface,
        surfaceElevated: DesignTokens.Light.surfaceElevated,
        textPrimary: DesignTokens.Light.textPrimary,
        textSecondary: DesignTokens.Light.textSecondary,
        accent: DesignTokens.Light.accent,
        accentSecondary: DesignTokens.Light.accentSecondary,
        success: DesignTokens.Light.success,
        warning: DesignTokens.Light.warning,
        danger: DesignTokens.Light.danger,
        surfaceLow: DesignTokens.Light.surfaceLow,
        surfaceContainer: DesignTokens.Light.surfaceContainer,
        surfaceHigh: DesignTokens.Light.surfaceHigh,
        surfaceHighest: DesignTokens.Light.surfaceHighest,
        surfaceDim: DesignTokens.Light.surfaceDim,
        surfaceBright: DesignTokens.Light.surfaceBright,
        tertiary: DesignTokens.Light.tertiary,
        onTertiary: DesignTokens.Light.onTertiary,
        tertiaryContainer: DesignTokens.Light.tertiaryContainer,
        onTertiaryContainer: DesignTokens.Light.onTertiaryContainer,
        outline: DesignTokens.Light.outline,
        outlineVariant: DesignTokens.Light.outlineVariant,
        scrim: DesignTokens.Light.scrim,
        inverseSurface: DesignTokens.Light.inverseSurface
    )
}

// MARK: - 字体族

enum AppFont {
    /// 界面字体：Inter。
    /// 注意：这里是**字体文件内 name 表 nameID 6（PostScript 名称）**，
    /// 必须与 Resources/Fonts/InterVariable.ttf 中的实际值一致
    /// （该可变字体注册名为 "Inter-Regular"，而非文件名）。
    /// UIAppFonts 注册成功后，`UIFont(name:)` / `Font.custom` 才能命中。
    static let sans = "Inter-Regular"
    /// Inter 可变轴名（fvar 表实测为 opsz / wght，此处只用 wght）。
    static let sansVariationAxis = "wght"
    /// 数值字体：JetBrains Mono（等宽，保证数值跳动不晃动）。
    /// 按字重分发三个静态文件，PostScript 名与文件名一致。
    enum Mono {
        static let regular = "JetBrainsMono-Regular"
        static let medium = "JetBrainsMono-Medium"
        static let bold = "JetBrainsMono-Bold"
    }
}

/// 统一取字体；字体缺失时回退系统字体（不崩溃，README 已说明）。
enum Typography {
    /// JetBrains Mono 为静态字重文件，直接按名取用。
    static func mono(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        let name: String
        switch weight {
        case .bold, .heavy, .black: name = AppFont.Mono.bold
        case .medium, .semibold: name = AppFont.Mono.medium
        default: name = AppFont.Mono.regular
        }
        return .custom(name, size: size)
    }

    /// Inter 为**可变字体**（fvar: wght 100...900，默认 400）。
    /// SwiftUI 的 `Font.custom` 无法指定可变轴，因此这里经 CoreText
    /// `kCTFontVariationAttribute` 取指定字重（公开 API）；
    /// 若字体未注册成功，回退 `.custom`（系统会再退到默认字体），不崩溃。
    static func sans(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        Font(sansUIFont(size, weight))
    }

    static func sansUIFont(_ size: CGFloat, _ weight: Font.Weight) -> UIFont {
        let wght = Self.sansWeightValue(weight)
        let base = CTFontDescriptorCreateWithNameAndSize(AppFont.sans as CFString, size)
        let attrs: [CFString: Any] = [kCTFontVariationAttribute: [AppFont.sansVariationAxis: wght]]
        let varied = CTFontDescriptorCreateCopyWithAttributes(base, attrs as CFDictionary)
        let font = CTFontCreateWithFontDescriptor(varied, size, nil)
        // CTFont 没有 familyName 属性，取字体族要用 CTFontCopyFamilyName
        let family = CTFontCopyFamilyName(font) as String? ?? ""
        if family.isEmpty {
            // 字体缺失（未注册）：回退系统字体，保证 UI 可用
            return UIFont.systemFont(ofSize: size, weight: Self.uiWeight(weight))
        }
        return font
    }

    /// SwiftUI Font.Weight -> Inter wght 轴数值（100...900）。
    static func sansWeightValue(_ weight: Font.Weight) -> CGFloat {
        switch weight {
        case .ultraLight: return 100
        case .thin: return 200
        case .light: return 300
        case .regular: return 400
        case .medium: return 500
        case .semibold: return 600
        case .bold: return 700
        case .heavy: return 800
        case .black: return 900
        default: return 400
        }
    }

    static func uiWeight(_ weight: Font.Weight) -> UIFont.Weight {
        switch weight {
        case .bold, .heavy, .black: return .bold
        case .medium, .semibold: return .semibold
        case .light, .thin, .ultraLight: return .light
        default: return .regular
        }
    }

    // 语义字号（与 Android Typography 对齐）
    static func displayLarge(_ weight: Font.Weight = .bold) -> Font { mono(52, weight) }
    static func displayMedium(_ weight: Font.Weight = .bold) -> Font { mono(36, weight) }
    static func headlineMedium(_ weight: Font.Weight = .bold) -> Font { mono(28, weight) }
    static func headlineSmall(_ weight: Font.Weight = .medium) -> Font { mono(22, weight) }
    static func titleLarge(_ weight: Font.Weight = .medium) -> Font { sans(20, weight) }
    static func titleMedium(_ weight: Font.Weight = .medium) -> Font { sans(16, weight) }
    static func bodyLarge(_ weight: Font.Weight = .regular) -> Font { sans(15, weight) }
    static func bodyMedium(_ weight: Font.Weight = .regular) -> Font { sans(13, weight) }
    static func labelLarge(_ weight: Font.Weight = .medium) -> Font { sans(13, weight) }
    static func labelMedium(_ weight: Font.Weight = .medium) -> Font { sans(11, weight) }
    static func labelSmall(_ weight: Font.Weight = .regular) -> Font { mono(10, weight) }
}

// MARK: - 主题容器

struct LiquidGlassTheme<Content: View>: View {
    @Environment(\.colorScheme) private var systemScheme
    let mode: ThemeMode
    @ViewBuilder let content: Content

    init(mode: ThemeMode = .dark, @ViewBuilder content: () -> Content) {
        self.mode = mode
        self.content = content()
    }

    private var isDark: Bool {
        switch mode {
        case .dark: return true
        case .light: return false
        case .auto: return systemScheme == .dark
        }
    }

    var body: some View {
        content
            .environment(\.colorScheme, isDark ? .dark : .light)
            .environment(\.colors, isDark ? .dark : .light)
    }
}

private struct ColorsKey: EnvironmentKey {
    static let defaultValue: TokenPalette = .dark
}

extension EnvironmentValues {
    /// 当前主题语义色。
    var colors: TokenPalette {
        get { self[ColorsKey.self] }
        set { self[ColorsKey.self] = newValue }
    }
}

// MARK: - 指标语义色（按 colorKey 取色，禁止业务代码硬编码色值）

extension TokenPalette {
    func metricColor(_ key: String) -> Color {
        switch key {
        case "accent": return accent
        case "accentSecondary": return accentSecondary
        case "success": return success
        case "warning": return warning
        case "danger": return danger
        default: return accent
        }
    }
}
