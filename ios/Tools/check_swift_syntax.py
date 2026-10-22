#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Swift 源码轻量静态检查（无 Swift 工具链环境的兜底校验）。

不替代编译器，只抓「明显结构性错误」：
1. 花括号 / 圆括号 / 方括号配对（跳过字符串字面量、注释、字符串插值）；
2. 每文件 import 清单汇总；
3. 常见笔误模式（如 `Font.custom` 使用 PostScript 名等由人工规则校验）。
"""
import glob
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
IOS_ROOT = os.path.abspath(os.path.join(HERE, ".."))


def strip_noise(src: str) -> str:
    """去掉字符串字面量与注释，保留结构字符。"""
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        two = src[i:i + 2]
        three = src[i:i + 3]
        if three == '"""':
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append(" " * 0)
            i = j
            continue
        if two == "//":
            j = src.find("\n", i)
            i = n if j < 0 else j
            continue
        if two == "/*":
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        if c == '"':
            # 普通字符串；处理 \" 转义与 \( 插值（插值内允许嵌套括号）
            i += 1
            depth = 0
            while i < n:
                if src[i] == "\\":
                    i += 2
                    continue
                if src[i] == "(" and depth == 0 and src[i:i + 2] == "\\(":
                    depth = 1
                    i += 2
                    continue
                if depth > 0:
                    if src[i] == "(":
                        depth += 1
                    elif src[i] == ")":
                        depth -= 1
                    i += 1
                    continue
                if src[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        if c == "'" and src[i:i + 2] == "'(":  # 不存在，防御
            i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


def check_file(path):
    src = open(path, encoding="utf-8").read()
    clean = strip_noise(src)
    errors = []
    pairs = {"}": "{", ")": "(", "]": "["}
    stack = []
    line = 1
    for ch in clean:
        if ch == "\n":
            line += 1
        elif ch in "({[":
            stack.append((ch, line))
        elif ch in ")}]":
            if not stack:
                errors.append(f"多余的 '{ch}'（约第 {line} 行）")
            else:
                op, ol = stack.pop()
                if pairs[ch] != op:
                    errors.append(f"括号不匹配：'{op}'(第{ol}行) vs '{ch}'(第{line}行)")
    if stack:
        op, ol = stack[-1]
        errors.append(f"未闭合的 '{op}'（第 {ol} 行）")
    return errors


def main():
    files = sorted(glob.glob(os.path.join(IOS_ROOT, "DevicePerfMonitor", "**", "*.swift"),
                             recursive=True) +
                   glob.glob(os.path.join(IOS_ROOT, "DevicePerfMonitorWidget", "*.swift")))
    imports = {}
    failed = False
    for f in files:
        errs = check_file(f)
        rel = os.path.relpath(f, IOS_ROOT)
        if errs:
            failed = True
            print(f"[FAIL] {rel}")
            for e in errs:
                print("       -", e)
        else:
            print(f"[ OK ] {rel}")
        for m in re.finditer(r"^import\s+(\w+)", open(f, encoding="utf-8").read(), re.M):
            imports.setdefault(m.group(1), []).append(rel)

    print(f"\n文件数: {len(files)}")
    print("import 汇总:")
    for k in sorted(imports):
        print(f"  {k}: {len(imports[k])} 个文件")

    if failed:
        print("\n[FAIL] 存在结构错误")
        return 1
    print("\n[OK] 括号结构全部配对")
    return 0


if __name__ == "__main__":
    sys.exit(main())
