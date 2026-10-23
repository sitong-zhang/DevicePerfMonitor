//
//  DesignTokens.generated.swift
//
//  由 ios/Tools/generate_tokens.py 从 design-tokens.json 自动生成，请勿手工修改。
//  真源：design-tokens.json（双端共用）。
//

import SwiftUI

enum DesignTokens {

    // MARK: - 语义色板（Catppuccin Mocha / Latte）

    enum Dark {
        /// Material 3 Expressive（种子色 Catppuccin Mauve #CBA6F7）
        static let background = Color(red: 23/255.0, green: 16/255.0, blue: 31/255.0)
        static let surface = Color(red: 44/255.0, green: 37/255.0, blue: 53/255.0)
        static let surfaceElevated = Color(red: 55/255.0, green: 47/255.0, blue: 64/255.0)
        static let textPrimary = Color(red: 240/255.0, green: 223/255.0, blue: 255/255.0)
        static let textSecondary = Color(red: 212/255.0, green: 199/255.0, blue: 221/255.0)
        static let accent = Color(red: 222/255.0, green: 192/255.0, blue: 255/255.0)
        static let accentSecondary = Color(red: 157/255.0, green: 209/255.0, blue: 197/255.0)
        static let success = Color(red: 166/255.0, green: 227/255.0, blue: 161/255.0)
        static let warning = Color(red: 249/255.0, green: 226/255.0, blue: 175/255.0)
        static let danger = Color(red: 253/255.0, green: 111/255.0, blue: 133/255.0)
        static let surfaceLow = Color(red: 34/255.0, green: 26/255.0, blue: 42/255.0)
        static let surfaceContainer = Color(red: 44/255.0, green: 37/255.0, blue: 53/255.0)
        static let surfaceHigh = Color(red: 55/255.0, green: 47/255.0, blue: 64/255.0)
        static let surfaceHighest = Color(red: 67/255.0, green: 58/255.0, blue: 75/255.0)
        static let surfaceDim = Color(red: 15/255.0, green: 8/255.0, blue: 23/255.0)
        static let surfaceBright = Color(red: 81/255.0, green: 72/255.0, blue: 90/255.0)
        static let tertiary = Color(red: 157/255.0, green: 209/255.0, blue: 197/255.0)
        static let onTertiary = Color(red: 0/255.0, green: 55/255.0, blue: 48/255.0)
        static let tertiaryContainer = Color(red: 26/255.0, green: 79/255.0, blue: 70/255.0)
        static let onTertiaryContainer = Color(red: 190/255.0, green: 243/255.0, blue: 230/255.0)
        static let outline = Color(red: 157/255.0, green: 146/255.0, blue: 166/255.0)
        static let outlineVariant = Color(red: 95/255.0, green: 86/255.0, blue: 104/255.0)
        static let scrim = Color(red: 0/255.0, green: 0/255.0, blue: 0/255.0)
        static let inverseSurface = Color(red: 255/255.0, green: 247/255.0, blue: 255/255.0)
    }

    enum Light {
        /// Material 3 Expressive（种子色 Catppuccin Mauve #CBA6F7）
        static let background = Color(red: 255/255.0, green: 247/255.0, blue: 255/255.0)
        static let surface = Color(red: 232/255.0, green: 224/255.0, blue: 232/255.0)
        static let surfaceElevated = Color(red: 223/255.0, green: 216/255.0, blue: 223/255.0)
        static let textPrimary = Color(red: 60/255.0, green: 42/255.0, blue: 82/255.0)
        static let textSecondary = Color(red: 83/255.0, green: 79/255.0, blue: 85/255.0)
        static let accent = Color(red: 114/255.0, green: 77/255.0, blue: 160/255.0)
        static let accentSecondary = Color(red: 53/255.0, green: 103/255.0, blue: 93/255.0)
        static let success = Color(red: 64/255.0, green: 160/255.0, blue: 43/255.0)
        static let warning = Color(red: 223/255.0, green: 142/255.0, blue: 29/255.0)
        static let danger = Color(red: 172/255.0, green: 49/255.0, blue: 73/255.0)
        static let surfaceLow = Color(red: 243/255.0, green: 235/255.0, blue: 243/255.0)
        static let surfaceContainer = Color(red: 232/255.0, green: 224/255.0, blue: 232/255.0)
        static let surfaceHigh = Color(red: 223/255.0, green: 216/255.0, blue: 223/255.0)
        static let surfaceHighest = Color(red: 215/255.0, green: 207/255.0, blue: 215/255.0)
        static let surfaceDim = Color(red: 203/255.0, green: 196/255.0, blue: 204/255.0)
        static let surfaceBright = Color(red: 255/255.0, green: 251/255.0, blue: 255/255.0)
        static let tertiary = Color(red: 53/255.0, green: 103/255.0, blue: 93/255.0)
        static let onTertiary = Color(red: 229/255.0, green: 255/255.0, blue: 247/255.0)
        static let tertiaryContainer = Color(red: 184/255.0, green: 237/255.0, blue: 224/255.0)
        static let onTertiaryContainer = Color(red: 0/255.0, green: 55/255.0, blue: 48/255.0)
        static let outline = Color(red: 102/255.0, green: 98/255.0, blue: 104/255.0)
        static let outlineVariant = Color(red: 186/255.0, green: 180/255.0, blue: 187/255.0)
        static let scrim = Color(red: 0/255.0, green: 0/255.0, blue: 0/255.0)
        static let inverseSurface = Color(red: 19/255.0, green: 10/255.0, blue: 30/255.0)
    }

    // MARK: - 圆角（pt）
    enum Radius {
        static let card: CGFloat = 24
        static let small: CGFloat = 16
        static let large: CGFloat = 28
        static let pill: CGFloat = 999
    }

    // MARK: - 间距（pt）
    enum Spacing {
        static let s4: CGFloat = 4
        static let s8: CGFloat = 8
        static let s12: CGFloat = 12
        static let s16: CGFloat = 16
        static let s24: CGFloat = 24
        static let s32: CGFloat = 32
    }

    // MARK: - 模糊半径（pt）
    enum Blur {
        static let glass: CGFloat = 32
        static let subtle: CGFloat = 24
        static let overlayMin: CGFloat = 28
        static let overlayMax: CGFloat = 36
    }

    // MARK: - 液态玻璃材质参数
    enum Glass {
        enum BackgroundAlpha {
            static let min: CGFloat = 0.18
            static let max: CGFloat = 0.28
        }
        static let borderWidth: CGFloat = 1
        static let borderAlpha: CGFloat = 0.35
        static let highlightAlphaTop: CGFloat = 0.55
        static let highlightAlphaBottom: CGFloat = 0.12
        static let innerShadowAlpha: CGFloat = 0.1
        static let outerShadowAlpha: CGFloat = 0.22
        static let outerShadowRadius: CGFloat = 20
        static let outerShadowOffsetY: CGFloat = 8
        static let saturationBoost: CGFloat = 1.12
        static let noiseAlpha: CGFloat = 0.035
    }

    // MARK: - 卡片/面板视觉层次
    enum Surface {
        static let cardPadding: CGFloat = 16
        static let gap: CGFloat = 14
        static let borderWidth: CGFloat = 1
        static let borderAlpha: CGFloat = 0.22
        static let highlightAlpha: CGFloat = 0.05
    }

    // MARK: - 三层投影（1=卡片 2=底栏 3=弹层）
    enum Elevation {
        enum Level1 {
            static let shadowY: CGFloat = 2
            static let radius: CGFloat = 8
            static let alpha: CGFloat = 0.3
        }
        enum Level2 {
            static let shadowY: CGFloat = 6
            static let radius: CGFloat = 18
            static let alpha: CGFloat = 0.36
        }
        enum Level3 {
            static let shadowY: CGFloat = 12
            static let radius: CGFloat = 32
            static let alpha: CGFloat = 0.44
        }
    }

    // MARK: - 浮动胶囊底栏几何
    enum BottomBar {
        static let height: CGFloat = 62
        static let marginHorizontal: CGFloat = 24
        static let marginBottom: CGFloat = 24
        static let indicatorInset: CGFloat = 4
        static let iconSize: CGFloat = 24
        static let labelSize: CGFloat = 11
        static let indicatorAlpha: CGFloat = 0.18
        static let selectedScale: CGFloat = 1.08
    }

    // MARK: - 动效曲线（cubic-bezier 四参数 + 时长 ms）
    enum Motion {
        enum Enter {
            static let controlPoints: (CGFloat, CGFloat, CGFloat, CGFloat) = (0.05, 0.7, 0.1, 1.0)
            static let durationMs: Double = 320
            static var easing: Animation {
                let (x1, y1, x2, y2) = controlPoints
                return .timingCurve(x1, y1, x2, y2)
            }
            static var duration: Double { durationMs / 1000.0 }
        }
        enum Exit {
            static let controlPoints: (CGFloat, CGFloat, CGFloat, CGFloat) = (0.3, 0.0, 0.8, 0.15)
            static let durationMs: Double = 240
            static var easing: Animation {
                let (x1, y1, x2, y2) = controlPoints
                return .timingCurve(x1, y1, x2, y2)
            }
            static var duration: Double { durationMs / 1000.0 }
        }
        enum Emphasized {
            static let controlPoints: (CGFloat, CGFloat, CGFloat, CGFloat) = (0.2, 0.0, 0.0, 1.0)
            static let durationMs: Double = 300
            static var easing: Animation {
                let (x1, y1, x2, y2) = controlPoints
                return .timingCurve(x1, y1, x2, y2)
            }
            static var duration: Double { durationMs / 1000.0 }
        }

        // MARK: - Material 3 Expressive 空间弹簧（与 Android DesignTokens.Motion.Spring 同构）
        enum Spring {


            enum FastSpatial {
                static let dampingRatio: CGFloat = 0.6
                static let stiffness: CGFloat = 800
                /// SwiftUI response = 2π/√stiffness（秒）
                static var response: Double { 2 * Double.pi / Double(stiffness).squareRoot() }
                /// SwiftUI dampingFraction = dampingRatio（同一 ζ，原样互换）
                static var dampingFraction: Double { Double(dampingRatio) }
            }


            enum DefaultSpatial {
                static let dampingRatio: CGFloat = 0.8
                static let stiffness: CGFloat = 380
                /// SwiftUI response = 2π/√stiffness（秒）
                static var response: Double { 2 * Double.pi / Double(stiffness).squareRoot() }
                /// SwiftUI dampingFraction = dampingRatio（同一 ζ，原样互换）
                static var dampingFraction: Double { Double(dampingRatio) }
            }


            enum SlowSpatial {
                static let dampingRatio: CGFloat = 0.8
                static let stiffness: CGFloat = 200
                /// SwiftUI response = 2π/√stiffness（秒）
                static var response: Double { 2 * Double.pi / Double(stiffness).squareRoot() }
                /// SwiftUI dampingFraction = dampingRatio（同一 ζ，原样互换）
                static var dampingFraction: Double { Double(dampingRatio) }
            }
        }
    }

    // MARK: - 数值缓动
    enum Number {
        static let maxDurationMs: Double = 300
        static let intervalMarginMs: Double = 50
        /// 时长 = min(300ms, 刷新间隔 - 50ms)（design-tokens.json 规范）
        static func duration(intervalMs: Double) -> Double {
            min(maxDurationMs, max(0, intervalMs - intervalMarginMs)) / 1000.0
        }
    }

    // MARK: - 手势参数
    enum Gesture {
        static let doubleTapWindowMs: Double = 250
        static let touchSlop: CGFloat = 8
        static let edgeSnapThreshold: CGFloat = 96
        static let trashZoneHeight: CGFloat = 140
        static let scaleRange: ClosedRange<CGFloat> = 0.8...1.6
    }

    // MARK: - 健康分权重与分段（与 analysis 模块共用）
    enum Health {
        enum Weights {
            static let temperature: Double = 0.4
            static let frameStability: Double = 0.3
            static let battery: Double = 0.2
            static let memory: Double = 0.1
        }

        /// 温度分段：[(上限℃, 起始分, 结束分)]，null 上限表示 +∞
        static let temperatureBreakpoints: [(max: Double?, from: Double, to: Double)] = [
            (max: 40, from: 100, to: 100),
            (max: 50, from: 100, to: 70),
            (max: 60, from: 70, to: 35),
            (max: 70, from: 35, to: 10),
            (max: nil, from: 0, to: 0),
        ]

        static let grades: [(min: Double, label: String)] = [
            (min: 90, label: "优秀"),
            (min: 70, label: "良好"),
            (min: 50, label: "一般"),
            (min: 0, label: "警告"),
        ]
    }
}
