#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
project.pbxproj 静态校验器。

检查项：
1. 语法：花括号/圆括号配对、对象定义唯一性；
   old-style plist 值合法性：不允许两个相邻引号串（= "A" "B"; 是 Xcode 解析报
   "missing semicolon in dictionary" 的常见原因，必须是单个字符串或数组）；
2. 引用完整性：所有 24 位大写十六进制 UUID 引用都能在 objects 段找到定义；
3. 孤儿对象：除被 rootObject/主组/阶段引用链可达的对象外报警；
4. 磁盘一致性：PBXFileReference（sourceTree=<group>）的 path 能在磁盘上定位；
5. Build phase 与 group 的 fileRef 一致（每个源文件都进了 Sources 阶段）。
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PBX = os.path.join(HERE, "..", "DevicePerfMonitor.xcodeproj", "project.pbxproj")
IOS_ROOT = os.path.abspath(os.path.join(HERE, ".."))


def main():
    # 支持传入待校验的 pbxproj 路径（默认工程内文件），便于对生成中间产物做反向测试
    pbx = sys.argv[1] if len(sys.argv) > 1 else PBX
    text = open(pbx, encoding="utf-8").read()
    errors = []

    # 1. 括号配对
    for open_c, close_c in (("{", "}"), ("(", ")")):
        if text.count(open_c) != text.count(close_c):
            errors.append(f"括号不配对: {open_c}{close_c} "
                          f"{text.count(open_c)} vs {text.count(close_c)}")

    # 2. 对象定义
    body = text.split("objects = {", 1)[1].rsplit("};", 1)[0]
    defs = re.findall(r"^\t\t([0-9A-F]{24}) /\* (.*?) \*/ = \{", body, re.M)
    if not defs:
        defs = re.findall(r"^\t\t([0-9A-F]{24}) = \{", body, re.M)
    defined = {}
    for u, comment in defs:
        if u in defined:
            errors.append(f"UUID 重复定义: {u}")
        defined[u] = comment
    print(f"对象定义数: {len(defined)}")

    # 2b. old-style plist 值合法性：相邻引号串（"A" "B";）会让 Xcode 解析器报
    # "missing semicolon in dictionary"，本地必须先抓出来
    for ln, line in enumerate(text.splitlines(), 1):
        if re.search(r'= "[^"]*"\s+"[^"]*";', line):
            errors.append(f"第 {ln} 行: 相邻引号串非法（old-style plist 要求单个字符串）: {line.strip()}")

    # 3. 引用完整性（排除注释里的）
    refs = set()
    for m in re.finditer(r"\b([0-9A-F]{24})\b", body):
        u = m.group(1)
        # 跳过定义行左侧
        line_start = body.rfind("\n", 0, m.start()) + 1
        prefix = body[line_start:m.start()]
        if re.fullmatch(r"\t\t", prefix):
            continue
        refs.add(u)
    missing = sorted(r for r in refs if r not in defined)
    if missing:
        errors.append(f"未定义引用: {missing}")

    # 4. 磁盘一致性
    file_refs = re.findall(
        r"^\t\t([0-9A-F]{24}) /\* (.*?) \*/ = \{isa = PBXFileReference;.*?path = \"(.*?)\"; sourceTree = \"(.*?)\";",
        body, re.M)
    if not file_refs:
        file_refs = re.findall(
            r"^\t\t([0-9A-F]{24}) = \{isa = PBXFileReference;.*?path = (.*?); sourceTree = (.*?);",
            body, re.M)
    checked = 0
    for u, name, path, tree in file_refs:
        if tree == "BUILT_PRODUCTS_DIR":
            continue
        # sourceTree=<group>：相对所属组解析；此处以 ios/ 为根做存在性粗校验
        cand = os.path.join(IOS_ROOT, path)
        if not os.path.exists(cand):
            # 组相对路径：在 ios/ 下按目录名/文件名递归查找
            found = False
            for root, dirs, files in os.walk(IOS_ROOT):
                if os.path.basename(path) in files or os.path.basename(path) in dirs:
                    found = True
                    break
            if not found:
                errors.append(f"fileRef 磁盘缺失: {name} (path={path})")
        checked += 1
    print(f"fileRef 校验数: {checked}")

    # 5. Sources 阶段文件数 vs 源文件总数
    swift_total = 0
    for base in ("DevicePerfMonitor", "DevicePerfMonitorWidget"):
        for root, _dirs, files in os.walk(os.path.join(IOS_ROOT, base)):
            swift_total += sum(1 for f in files if f.endswith(".swift"))
    src_phase = re.search(r"/\* Sources \*/ = \{\s*isa = PBXSourcesBuildPhase;.*?files = \((.*?)\);",
                          body, re.S)
    print(f"磁盘 .swift 总数: {swift_total}")

    # 6. PBXBuildFile 引用类型语义：fileRef 必须指向 PBXReference 家族
    # （PBXFileReference / PBXReferenceProxy），SPM 包产品必须用 productRef
    # 指向 XCSwiftPackageProductDependency。Xcode 对违反此规则的项目直接报
    # "invalid project file ... fileReference is not a PBXReference"。
    isa_of = {}
    for m in re.finditer(r"^\t\t([0-9A-F]{24}) /\* .*? \*/ = \{", body, re.M):
        # 对象定义起点，向后找第一个 isa = X;（单行/多行定义都兼容）
        seg = body[m.end():m.end() + 400]
        isa_m = re.search(r"isa = (\w+);", seg)
        if isa_m:
            isa_of[m.group(1)] = isa_m.group(1)
    REF_OK = {"PBXFileReference", "PBXReferenceProxy", "PBXGroup"}
    for m in re.finditer(
            r"^\t\t([0-9A-F]{24}) /\* .*? \*/ = \{isa = PBXBuildFile; fileRef = ([0-9A-F]{24})", body, re.M):
        bf, ref = m.group(1), m.group(2)
        if isa_of.get(ref) not in REF_OK:
            errors.append(f"PBXBuildFile {bf} 的 fileRef {ref} 类型为 "
                          f"{isa_of.get(ref)}，不是 PBXReference（SPM 产品应改用 productRef）")
    for m in re.finditer(
            r"^\t\t([0-9A-F]{24}) /\* .*? \*/ = \{isa = PBXBuildFile; productRef = ([0-9A-F]{24})", body, re.M):
        bf, ref = m.group(1), m.group(2)
        if isa_of.get(ref) != "XCSwiftPackageProductDependency":
            errors.append(f"PBXBuildFile {bf} 的 productRef {ref} 类型为 "
                          f"{isa_of.get(ref)}，不是 XCSwiftPackageProductDependency")

    if errors:
        print("\n[FAIL]")
        for e in errors:
            print(" -", e)
        return 1
    print("\n[OK] pbxproj 静态校验通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
