#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
双端 design token 一致性校验。

校验 design-tokens.json（唯一真源）与两端生成产物是否一致：
  - Android: android/app/src/main/java/com/devperf/monitor/ui/theme/DesignTokens.kt
  - iOS:     ios/DevicePerfMonitor/Theme/DesignTokens.generated.swift

同时校验：
  1. 语义色 hex 三端一致；
  2. 动效三曲线四参数 + 时长一致；
  3. 健康分权重 / 温度分段一致；
  4. 双端字体文件 md5 一致（同一份 OFL 字体）。
"""
import hashlib
import json
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
TOKENS = os.path.join(ROOT, "design-tokens.json")
ANDROID_KT = os.path.join(ROOT, "android", "app", "src", "main", "java",
                          "com", "devperf", "monitor", "ui", "theme", "DesignTokens.kt")
IOS_SWIFT = os.path.join(ROOT, "ios", "DevicePerfMonitor", "Theme", "DesignTokens.generated.swift")


def md5(path):
    return hashlib.md5(open(path, "rb").read()).hexdigest()


def main():
    errors = []
    t = json.load(open(TOKENS, encoding="utf-8"))
    kt = open(ANDROID_KT, encoding="utf-8").read()
    sw = open(IOS_SWIFT, encoding="utf-8").read()

    # 1. 语义色
    for scheme, keys in (("dark", t["palette"]["dark"]["semantic"]),
                         ("light", t["palette"]["light"]["semantic"])):
        for name, hexv in keys.items():
            r, g, b = (int(hexv[i:i + 2], 16) for i in (1, 3, 5))
            if f"Color(0xFF{r:02X}{g:02X}{b:02X})" not in kt:
                errors.append(f"[Android] 缺少 {scheme}.{name} = {hexv}")
            ios_expect = (f"Color(red: {r}/255.0, green: {g}/255.0, blue: {b}/255.0)")
            if ios_expect not in sw:
                errors.append(f"[iOS] 缺少 {scheme}.{name} = {hexv}")

    # 2. 动效曲线
    import re as _re

    def upper_snake(name):
        return _re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", name).upper()

    for name, spec in t["motion"].items():
        if name.startswith("$"):
            continue
        bezier = spec["cubicBezier"]
        for tok in bezier:
            if str(tok) not in kt:
                errors.append(f"[Android] motion {name} 参数 {tok} 缺失")
            if str(tok) not in sw:
                errors.append(f"[iOS] motion {name} 参数 {tok} 缺失")
        if f"{spec['durationMs']})" not in kt:
            errors.append(f"[Android] motion {name} 时长缺失")
        if f"durationMs: Double = {spec['durationMs']}" not in sw:
            errors.append(f"[iOS] motion {name} 时长缺失")

    # 3. 健康分权重 / 分段 / 评级
    health = t["health"]
    for dim, w in health["weights"].items():
        if f"const val {upper_snake(dim)} = {w}f" not in kt:
            errors.append(f"[Android] health 权重 {dim}={w} 缺失")
        if f"static let {dim}: Double = {w}" not in sw:
            errors.append(f"[iOS] health 权重 {dim}={w} 缺失")
    for bp in health["temperatureBreakpoints"]:
        mx = "nil" if bp["max"] is None else str(bp["max"])
        mx_kt = "null" if bp["max"] is None else f"{bp['max']}f"
        fr = bp.get("from", bp.get("score"))
        to = bp.get("to", bp.get("score"))
        if f"TemperatureBreakpoint(maxTempC = {mx_kt}, fromScore = {fr}f, toScore = {to}f)" not in kt:
            errors.append(f"[Android] 温度分段点 max={mx} 缺失")
        if f"(max: {mx}, from: {fr}, to: {to})" not in sw:
            errors.append(f"[iOS] 温度分段点 max={mx} 缺失")
    for g in health["grades"]:
        if f"GradeSpec(minScore = {g['min']}f, label = \"{g['label']}\")" not in kt:
            errors.append(f"[Android] 评级档位 min={g['min']} 缺失")
        if f"(min: {g['min']}, label: \"{g['label']}\")" not in sw:
            errors.append(f"[iOS] 评级档位 min={g['min']} 缺失")

    # 4. 字体一致性
    pairs = [
        ("android/app/src/main/res/font/inter_variable.ttf",
         "ios/DevicePerfMonitor/Resources/Fonts/InterVariable.ttf"),
        ("android/app/src/main/res/font/jetbrains_mono_regular.ttf",
         "ios/DevicePerfMonitor/Resources/Fonts/JetBrainsMono-Regular.ttf"),
        ("android/app/src/main/res/font/jetbrains_mono_medium.ttf",
         "ios/DevicePerfMonitor/Resources/Fonts/JetBrainsMono-Medium.ttf"),
        ("android/app/src/main/res/font/jetbrains_mono_bold.ttf",
         "ios/DevicePerfMonitor/Resources/Fonts/JetBrainsMono-Bold.ttf"),
    ]
    for a, b in pairs:
        pa, pb = os.path.join(ROOT, a), os.path.join(ROOT, b)
        if not os.path.exists(pa) or not os.path.exists(pb):
            errors.append(f"字体缺失: {a} / {b}")
            continue
        if md5(pa) != md5(pb):
            errors.append(f"字体不一致: {a} != {b}")

    # 5. 玻璃参数
    for key in ("blurMin", "blurMax"):
        if key in t["glass"]:
            v = t["glass"][key]
            if str(v) not in kt:
                errors.append(f"[Android] glass.{key}={v} 缺失")
            if str(v) not in sw:
                errors.append(f"[iOS] glass.{key}={v} 缺失")

    print(f"design-tokens.json v{t.get('version')}")
    print(f"  Android DesignTokens.kt : {len(kt)} 字节")
    print(f"  iOS DesignTokens.generated.swift : {len(sw)} 字节")
    print(f"  语义色组: dark={len(t['palette']['dark']['semantic'])}, "
          f"light={len(t['palette']['light']['semantic'])}")
    print(f"  动效曲线: {list(t['motion'].keys())}")
    print(f"  健康分权重: {t['health']['weights']}")

    if errors:
        print("\n[FAIL]")
        for e in errors:
            print(" -", e)
        return 1
    print("\n[OK] 双端 token 与字体完全一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
