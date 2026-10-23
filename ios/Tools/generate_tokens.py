#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
design-tokens.json -> DesignTokens.generated.swift 代码生成器（iOS 端）。

真源：仓库根目录 design-tokens.json（双端共用，Android 端由
android/app/build.gradle.kts 的 generateDesignTokens 任务生成 DesignTokens.kt）。
本脚本由人工/CI 执行（macOS 自带 python3，无需额外依赖）：

    python3 ios/Tools/generate_tokens.py

生成 ios/DevicePerfMonitor/Theme/DesignTokens.generated.swift。
生成的文件应提交入库，保证不跑脚本也能编译；禁止手工修改生成物。
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
TOKENS = os.path.join(ROOT, "design-tokens.json")
OUT = os.path.join(HERE, "..", "DevicePerfMonitor", "Theme", "DesignTokens.generated.swift")


def hex_to_swift(value: str) -> str:
    """'#RRGGBB' -> Swift Color(red:green:blue:) 表达式。"""
    h = value.lstrip("#")
    r, g, b = int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)
    return f"Color(red: {r}/255.0, green: {g}/255.0, blue: {b}/255.0)"


def camel(name: str) -> str:
    """overlayMin -> overlayMin（Swift 属性名保持 camelCase 原样）。"""
    return name


def main() -> int:
    with open(TOKENS, "r", encoding="utf-8") as f:
        data = json.load(f)

    lines = []
    lines.append("//")
    lines.append("//  DesignTokens.generated.swift")
    lines.append("//")
    lines.append("//  由 ios/Tools/generate_tokens.py 从 design-tokens.json 自动生成，请勿手工修改。")
    lines.append("//  真源：design-tokens.json（双端共用）。")
    lines.append("//")
    lines.append("")
    lines.append("import SwiftUI")
    lines.append("")
    lines.append("enum DesignTokens {")
    lines.append("")

    # ---- 语义色板 ----
    lines.append("    // MARK: - 语义色板（Catppuccin Mocha / Latte）")
    for scheme, tag in (("dark", "Dark"), ("light", "Light")):
        palette = data["palette"][scheme]
        lines.append("")
        lines.append(f"    enum {tag} {{")
        lines.append(f"        /// {palette['name']}")
        for key, value in palette["semantic"].items():
            lines.append(f"        static let {key} = {hex_to_swift(value)}")
        lines.append("    }")

    # ---- Radius / Spacing / Blur ----
    lines.append("")
    lines.append("    // MARK: - 圆角（pt）")
    lines.append("    enum Radius {")
    for key, value in data["radius"].items():
        if key.startswith("$"):
            continue
        lines.append(f"        static let {key}: CGFloat = {value}")
    lines.append("    }")

    lines.append("")
    lines.append("    // MARK: - 间距（pt）")
    lines.append("    enum Spacing {")
    for value in data["spacing"]:
        lines.append(f"        static let s{value}: CGFloat = {value}")
    lines.append("    }")

    lines.append("")
    lines.append("    // MARK: - 模糊半径（pt）")
    lines.append("    enum Blur {")
    for key, value in data["blur"].items():
        if key.startswith("$"):
            continue
        lines.append(f"        static let {key}: CGFloat = {value}")
    lines.append("    }")

    # ---- Glass 参数 ----
    lines.append("")
    lines.append("    // MARK: - 液态玻璃材质参数")
    lines.append("    enum Glass {")
    for key, value in data["glass"].items():
        if key.startswith("$"):
            continue
        if isinstance(value, dict):
            lines.append(f"        enum {camel(key[0].upper() + key[1:])} {{")
            for k2, v2 in value.items():
                if k2.startswith("$"):
                    continue
                suffix = "" if isinstance(v2, int) and not isinstance(v2, bool) else ""
                if isinstance(v2, float):
                    lines.append(f"            static let {k2}: CGFloat = {v2}")
                else:
                    lines.append(f"            static let {k2}: CGFloat = {v2}")
            lines.append("        }")
        elif isinstance(value, float):
            lines.append(f"        static let {key}: CGFloat = {value}")
        else:
            lines.append(f"        static let {key}: CGFloat = {value}")
    lines.append("    }")

    # ---- Motion ----
    lines.append("")
    lines.append("    // MARK: - 动效曲线（cubic-bezier 四参数 + 时长 ms）")
    lines.append("    enum Motion {")
    for key in ("enter", "exit", "emphasized"):
        m = data["motion"][key]
        a, b, c, d = m["cubicBezier"]
        lines.append(f"        enum {key[0].upper() + key[1:]} {{")
        lines.append(f"            static let controlPoints: (CGFloat, CGFloat, CGFloat, CGFloat) = ({a}, {b}, {c}, {d})")
        lines.append(f"            static let durationMs: Double = {m['durationMs']}")
        lines.append(f"            static var easing: Animation {{")
        lines.append(f"                let (x1, y1, x2, y2) = controlPoints")
        lines.append(f"                return .timingCurve(x1, y1, x2, y2)")
        lines.append(f"            }}")
        lines.append(f"            static var duration: Double {{ durationMs / 1000.0 }}")
        lines.append("        }")

    # Material 3 Expressive 空间弹簧（androidx ExpressiveMotionTokens 同参数）。
    # SwiftUI 的 spring 用 response/dampingFraction，由 stiffness/dampingRatio 换算：
    # stiffness = (2π/response)² ⟹ response = 2π/√stiffness；
    # dampingRatio 与 dampingFraction 是同一无量纲量（ζ），原样互换、不除 2
    # （多个跨端规范实证：RunAnywhere/Tday motion 规范、nandha.design 弹簧实验室）。
    spring = data["motion"].get("spring", {})
    if spring:
        # 与 Kotlin DesignTokens.Motion.Spring 同构：先套一层 Spring 容器
        lines.append("")
        lines.append("        // MARK: - Material 3 Expressive 空间弹簧（与 Android DesignTokens.Motion.Spring 同构）")
        lines.append("        enum Spring {")
    for name, spec in spring.items():
        if name.startswith("$"):
            continue
        enum_name = name[0].upper() + name[1:]
        lines.append("")
        lines.append(f"            // {spec.get('$comment', '')}" if "$comment" in spec else "")
        lines.append(f"            enum {enum_name} {{")
        lines.append(f"                static let dampingRatio: CGFloat = {spec['dampingRatio']}")
        lines.append(f"                static let stiffness: CGFloat = {spec['stiffness']}")
        lines.append(f"                /// SwiftUI response = 2π/√stiffness（秒）")
        lines.append(f"                static var response: Double {{ 2 * Double.pi / Double(stiffness).squareRoot() }}")
        lines.append(f"                /// SwiftUI dampingFraction = dampingRatio（同一 ζ，原样互换）")
        lines.append(f"                static var dampingFraction: Double {{ Double(dampingRatio) }}")
        lines.append("            }")
    if spring:
        lines.append("        }")
    lines.append("    }")

    # ---- Number ----
    lines.append("")
    lines.append("    // MARK: - 数值缓动")
    lines.append("    enum Number {")
    lines.append(f"        static let maxDurationMs: Double = {data['number']['maxDurationMs']}")
    lines.append(f"        static let intervalMarginMs: Double = {data['number']['intervalMarginMs']}")
    lines.append("        /// 时长 = min(300ms, 刷新间隔 - 50ms)（design-tokens.json 规范）")
    lines.append("        static func duration(intervalMs: Double) -> Double {")
    lines.append("            min(maxDurationMs, max(0, intervalMs - intervalMarginMs)) / 1000.0")
    lines.append("        }")
    lines.append("    }")

    # ---- Gesture ----
    lines.append("")
    lines.append("    // MARK: - 手势参数")
    lines.append("    enum Gesture {")
    lines.append(f"        static let doubleTapWindowMs: Double = {data['gesture']['doubleTapWindowMs']}")
    lines.append(f"        static let touchSlop: CGFloat = {data['gesture']['touchSlopDp']}")
    lines.append(f"        static let edgeSnapThreshold: CGFloat = {data['gesture']['edgeSnapThresholdDp']}")
    lines.append(f"        static let trashZoneHeight: CGFloat = {data['gesture']['trashZoneHeightDp']}")
    lo, hi = data["gesture"]["scaleRange"]
    lines.append(f"        static let scaleRange: ClosedRange<CGFloat> = {lo}...{hi}")
    lines.append("    }")

    # ---- Health ----
    lines.append("")
    lines.append("    // MARK: - 健康分权重与分段（与 analysis 模块共用）")
    lines.append("    enum Health {")
    w = data["health"]["weights"]
    lines.append("        enum Weights {")
    for k, v in w.items():
        lines.append(f"            static let {k}: Double = {v}")
    lines.append("        }")
    lines.append("")
    lines.append("        /// 温度分段：[(上限℃, 起始分, 结束分)]，null 上限表示 +∞")
    lines.append("        static let temperatureBreakpoints: [(max: Double?, from: Double, to: Double)] = [")
    for bp in data["health"]["temperatureBreakpoints"]:
        mx = "nil" if bp["max"] is None else str(bp["max"])
        if "score" in bp:
            lines.append(f"            (max: {mx}, from: {bp['score']}, to: {bp['score']}),")
        else:
            lines.append(f"            (max: {mx}, from: {bp['from']}, to: {bp['to']}),")
    lines.append("        ]")
    lines.append("")
    lines.append("        static let grades: [(min: Double, label: String)] = [")
    for g in data["health"]["grades"]:
        lines.append(f"            (min: {g['min']}, label: \"{g['label']}\"),")
    lines.append("        ]")
    lines.append("    }")

    lines.append("}")
    lines.append("")

    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print(f"generated: {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
