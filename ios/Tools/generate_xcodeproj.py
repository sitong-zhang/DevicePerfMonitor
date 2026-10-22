#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 ios/DevicePerfMonitor.xcodeproj/project.pbxproj。

双 target：
  1. DevicePerfMonitor（App，iOS 16.1+，依赖 Charts SPM）
  2. DevicePerfMonitorWidget（Widget Extension，承载 Live Activity）

设计说明：
- 经典显式 Group（objectVersion 54），文件 path 相对所属 group，
  sourceTree 显式 "<group>"；共享文件（MonitorActivityAttributes.swift）
  使用同一个 PBXFileReference + 两个 PBXBuildFile（分别编入两个 target），
  与 Xcode「Add to Targets」多选行为一致；
- Charts 通过 XCRemoteSwiftPackageReference 引入（danielgindi/Charts，
  Apache-2.0）；
- 字体（Inter / JetBrains Mono，OFL-1.1）以资源方式打入 App；
- 生成后提交入库；增删源文件后重跑本脚本。
"""
import os
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
IOS_ROOT = os.path.abspath(os.path.join(HERE, ".."))
APP_DIR = os.path.join(IOS_ROOT, "DevicePerfMonitor")
WIDGET_DIR = os.path.join(IOS_ROOT, "DevicePerfMonitorWidget")
PROJECT_DIR = os.path.join(IOS_ROOT, "DevicePerfMonitor.xcodeproj")
OUT = os.path.join(PROJECT_DIR, "project.pbxproj")

CHARTS_REPO = "https://github.com/danielgindi/Charts.git"
CHARTS_MIN_VERSION = "5.0.0"


def uid(name: str) -> str:
    """确定性 UUID（同名同值，避免无意义 diff）。"""
    return uuid.uuid5(uuid.NAMESPACE_URL, "devperf:" + name).hex[:24].upper()


objects = []


def add(name, type_, lines):
    u = uid(name)
    objects.append((u, type_, lines))
    return u


def fileref(key, display, path, ftype, source_tree="<group>"):
    """PBXFileReference：path 相对所属 group（sourceTree=<group>）。"""
    lines = [
        f"\t\t{uid(key)} /* {display} */ = {{isa = PBXFileReference; lastKnownFileType = {ftype}; name = \"{display}\"; path = \"{path}\"; sourceTree = \"{source_tree}\"; }};"
    ]
    return add(key, "PBXFileReference", lines)


def buildfile(key, fileref_uuid, display, note="in Sources", ref_key="fileRef"):
    """PBXBuildFile。普通文件用 fileRef（须指向 PBXReference）；
    SPM 包产品（XCSwiftPackageProductDependency）必须用 productRef——
    Xcode 校验 fileRef 必须是 PBXReference，否则报项目文件无效。"""
    lines = [
        f"\t\t{uid('bf:' + key)} /* {display} {note} */ = {{isa = PBXBuildFile; {ref_key} = {fileref_uuid} /* {display} */; }};"
    ]
    return add("bf:" + key, "PBXBuildFile", lines)


def group(name, display, children, path=None):
    """PBXGroup：path 为空表示与父组同目录（相对解析）。"""
    lines = [f"\t\t{uid('grp:' + name)} /* {display} */ = {{",
             "\t\t\tisa = PBXGroup;",
             "\t\t\tchildren = ("]
    lines += [f"\t\t\t\t{c}," for c in children]
    lines += ["\t\t\t);"]
    if path is not None:
        lines.append(f"\t\t\tpath = {path};")
    if display not in (None, "."):
        lines.append(f"\t\t\tname = {display};")
    lines.append("\t\t\tsourceTree = \"<group>\";")
    lines.append("\t\t};")
    return add("grp:" + name, "PBXGroup", lines)


def collect_sources(dirpath):
    """返回 [(组名, [文件名...])]，按目录结构；根目录归一化为 ""。"""
    result = []
    for root, dirs, files in os.walk(dirpath):
        dirs.sort()
        swifts = sorted(f for f in files if f.endswith(".swift"))
        if swifts:
            rel = os.path.relpath(root, dirpath)
            if rel == ".":
                rel = ""
            result.append((rel, swifts))
    return result


def write_scheme(name: str, target_name: str, product: str, buildable: bool):
    """生成 xcshareddata/xcschemes/<name>.xcscheme。

    Xcode 新建工程会自动建 scheme，但脚本生成的 pbxproj 不带 scheme，
    导致命令行 xcodebuild -scheme 与 CI 直接失败。这里补齐共享 scheme，
    BlueprintIdentifier 用确定性 target UUID（uid('target:app/widget')）。
    """
    target_uuid = uid("target:app" if target_name == "DevicePerfMonitor" else "target:widget")
    entries = ""
    if buildable:
        entries = f"""
      <BuildActionEntries>
         <BuildActionEntry
            buildForTesting = "YES"
            buildForRunning = "YES"
            buildForProfiling = "YES"
            buildForArchiving = "YES"
            buildForAnalyzing = "YES">
            <BuildableReference
               BuildableIdentifier = "primary"
               BlueprintIdentifier = "{target_uuid}"
               BuildableName = "{product}"
               BlueprintName = "{target_name}"
               ReferencedContainer = "container:DevicePerfMonitor.xcodeproj">
            </BuildableReference>
         </BuildActionEntry>
      </BuildActionEntries>"""
    xml = f"""<?xml version="1.0" encoding="UTF-8"?>
<Scheme
   LastUpgradeVersion = "1500"
   version = "1.7">
   <BuildAction
      parallelizeBuildables = "YES"
      buildImplicitDependencies = "YES">{entries}
   </BuildAction>
   <TestAction
      buildConfiguration = "Debug"
      selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB"
      selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB"
      shouldUseLaunchSchemeArgsEnv = "YES">
      <Testables>
      </Testables>
   </TestAction>
   <LaunchAction
      buildConfiguration = "Debug"
      selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB"
      selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB"
      launchStyle = "0"
      useCustomWorkingDirectory = "NO"
      ignoresPersistentStateOnLaunch = "NO"
      debugDocumentVersion = "1"
      debugServiceVersion = "1"
      allowLocationSimulation = "YES">
      <BuildableProductRunnable
         runnableDebuggingMode = "0">
         <BuildableReference
            BuildableIdentifier = "primary"
            BlueprintIdentifier = "{target_uuid}"
            BuildableName = "{product}"
            BlueprintName = "{target_name}"
            ReferencedContainer = "container:DevicePerfMonitor.xcodeproj">
         </BuildableReference>
      </BuildableProductRunnable>
   </LaunchAction>
   <ProfileAction
      buildConfiguration = "Release"
      shouldUseLaunchSchemeArgsEnv = "YES"
      savedToolIdentifier = ""
      useCustomWorkingDirectory = "NO"
      debugDocumentVersion = "1">
      <BuildableProductRunnable
         runnableDebuggingMode = "0">
         <BuildableReference
            BuildableIdentifier = "primary"
            BlueprintIdentifier = "{target_uuid}"
            BuildableName = "{product}"
            BlueprintName = "{target_name}"
            ReferencedContainer = "container:DevicePerfMonitor.xcodeproj">
         </BuildableReference>
      </BuildableProductRunnable>
   </ProfileAction>
   <AnalyzeAction
      buildConfiguration = "Debug">
   </AnalyzeAction>
   <ArchiveAction
      buildConfiguration = "Release"
      revealArchiveInOrganizer = "YES">
   </ArchiveAction>
</Scheme>
"""
    out = os.path.join(PROJECT_DIR, "xcshareddata", "xcschemes", f"{name}.xcscheme")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        f.write(xml)
    print(f"generated: {out}")


def main():
    # ------------------------------------------------------------------
    # 收集文件
    # ------------------------------------------------------------------
    app_groups = collect_sources(APP_DIR)          # [("", [...]), ("Theme", [...]), ...]
    widget_groups = collect_sources(WIDGET_DIR)

    file_refs = {}   # key -> uuid
    build_files = {} # key -> buildfile uuid
    group_ids = {}   # 组名 -> group uuid

    def register(rel_dir, fname, key_prefix, note="in Sources"):
        display = fname
        key = f"{key_prefix}:{rel_dir}/{fname}"
        u = fileref(key, display, fname, "sourcecode.swift")
        file_refs[key] = u
        build_files[key] = buildfile(key, u, display, note)
        return key

    # App 源文件（按组分）
    app_group_file_keys = {}  # rel_dir -> [key]
    for rel_dir, swifts in app_groups:
        keys = []
        for f in swifts:
            keys.append(register(rel_dir, f, "app-src"))
        app_group_file_keys[rel_dir] = keys

    # Widget 源文件
    widget_group_file_keys = {}
    for rel_dir, swifts in widget_groups:
        keys = []
        for f in swifts:
            keys.append(register(rel_dir, f, "widget-src"))
        widget_group_file_keys[rel_dir] = keys

    # 共享文件：LiveActivity 目录下的 MonitorActivityAttributes.swift
    shared_key = "app-src:LiveActivity/MonitorActivityAttributes.swift"
    assert shared_key in build_files, "共享文件缺失"
    bf_shared_widget = buildfile("shared-widget", file_refs[shared_key],
                                 "MonitorActivityAttributes.swift", "in Sources")

    # 字体资源
    font_keys = []
    fonts_dir = os.path.join(APP_DIR, "Resources", "Fonts")
    for f in sorted(os.listdir(fonts_dir)):
        if f.endswith((".ttf", ".otf")):
            key = f"app-res:Fonts/{f}"
            u = fileref(key, f, f, "file")
            file_refs[key] = u
            build_files[key] = buildfile(key, u, f, "in Resources")
            font_keys.append(key)

    # Assets
    assets_key = "app-res:Assets.xcassets"
    u = fileref(assets_key, "Assets.xcassets", "Assets.xcassets", "folder.assetcatalog")
    file_refs[assets_key] = u
    build_files[assets_key] = buildfile(assets_key, u, "Assets.xcassets", "in Resources")

    # Info.plist（构建设置引用，非资源）
    app_plist = fileref("app:Info.plist", "Info.plist", "Info.plist", "text.plist.xml")
    widget_plist = fileref("widget:Info.plist", "Info.plist", "Info.plist", "text.plist.xml")

    # ------------------------------------------------------------------
    # Charts SPM
    # ------------------------------------------------------------------
    pkg_ref = add("pkg:charts", "XCRemoteSwiftPackageReference", [
        f"\t\t{uid('pkg:charts')} /* XCRemoteSwiftPackageReference \"Charts\" */ = {{",
        "\t\t\tisa = XCRemoteSwiftPackageReference;",
        f"\t\t\trepositoryURL = \"{CHARTS_REPO}\";",
        "\t\t\trequirement = {",
        "\t\t\t\tkind = upToNextMajorVersion;",
        f"\t\t\t\tminimumVersion = {CHARTS_MIN_VERSION};",
        "\t\t\t};",
        "\t\t};",
    ])
    charts_product = add("prod:charts", "XCSwiftPackageProductDependency", [
        f"\t\t{uid('prod:charts')} /* Charts */ = {{",
        "\t\t\tisa = XCSwiftPackageProductDependency;",
        f"\t\t\tpackage = {pkg_ref} /* XCRemoteSwiftPackageReference \"Charts\" */;",
        "\t\t\tproductName = Charts;",
        "\t\t};",
    ])
    bf_charts = buildfile("charts", charts_product, "Charts", "in Frameworks", ref_key="productRef")

    # ------------------------------------------------------------------
    # Groups（子组优先）
    # ------------------------------------------------------------------
    def make_app_group(rel_dir):
        if rel_dir == "":
            return None
        # 只支持一层嵌套（Theme/UIKit/... + Collection/Collectors）
        if "/" in rel_dir:
            parent, child = rel_dir.split("/", 1)
            keys = app_group_file_keys.get(rel_dir, [])
            children = [file_refs[k] for k in keys]
            gid = group("app:" + rel_dir, child, children, path=None)
            group_ids["app:" + rel_dir] = gid
            group_ids[rel_dir] = gid
            return gid
        keys = app_group_file_keys.get(rel_dir, [])
        children = [file_refs[k] for k in keys]
        gid = group("app:" + rel_dir, rel_dir, children, path=rel_dir)
        group_ids[rel_dir] = gid
        group_ids["app:" + rel_dir] = gid
        return gid

    # 先建所有子组
    for rel_dir in sorted(app_group_file_keys):
        make_app_group(rel_dir)

    # Fonts / Assets 资源组
    grp_fonts = group("app:Resources/Fonts", "Fonts", [file_refs[k] for k in font_keys], path=None)
    grp_assets = group("app:Resources/Assets.xcassets", "Assets.xcassets", [file_refs[assets_key]], path=None)
    grp_resources = group("app:Resources", "Resources",
                          [grp_fonts, grp_assets], path="Resources")
    group_ids["Resources"] = grp_resources

    # App 根组
    root_keys = app_group_file_keys.get("", [])
    root_children = [file_refs[k] for k in root_keys]
    for rel_dir in sorted(app_group_file_keys):
        if rel_dir == "":
            continue
        if "/" in rel_dir:
            parent, child = rel_dir.split("/", 1)
            root_children.append(group_ids["app:" + rel_dir])
        else:
            root_children.append(group_ids[rel_dir])
    root_children.append(grp_resources)
    root_children.append(app_plist)
    grp_app = group("app", "DevicePerfMonitor", root_children, path="DevicePerfMonitor")

    # Widget 组（含共享文件 MonitorActivityAttributes.swift 的同一 fileRef）
    widget_children = []
    for rel_dir, keys in widget_group_file_keys.items():
        for k in keys:
            if k == "widget-src:MonitorActivityAttributes.swift":
                # 共享文件：复用 App 侧 fileRef（不重复创建）
                continue
            widget_children.append(file_refs[k])
    widget_children.insert(0, file_refs[shared_key])
    widget_children.append(widget_plist)
    grp_widget = group("widget", "DevicePerfMonitorWidget", widget_children, path="DevicePerfMonitorWidget")

    # Products
    app_product = fileref("prod:app", "DevicePerfMonitor.app", "DevicePerfMonitor.app",
                          "wrapper.application", source_tree="BUILT_PRODUCTS_DIR")
    widget_product = fileref("prod:widget", "DevicePerfMonitorWidget.appex", "DevicePerfMonitorWidget.appex",
                             "wrapper.app-extension", source_tree="BUILT_PRODUCTS_DIR")
    grp_products = group("products", "Products",
                         [app_product, widget_product], path=None)

    grp_main = group("main", None, [grp_app, grp_widget, grp_products], path=None)

    # ------------------------------------------------------------------
    # Build Phases
    # ------------------------------------------------------------------
    app_src_keys = [k for rel_dir, keys in app_group_file_keys.items() for k in keys]
    app_res_keys = font_keys + [assets_key]
    widget_src_keys = []
    for rel_dir, keys in widget_group_file_keys.items():
        for k in keys:
            if k == "widget-src:MonitorActivityAttributes.swift":
                continue
            widget_src_keys.append(k)

    def phase(name, display, isa, entries):
        lines = [f"\t\t{uid('phase:' + name)} /* {display} */ = {{",
                 f"\t\t\tisa = {isa};",
                 "\t\t\tbuildActionMask = 2147483647;",
                 "\t\t\tfiles = ("]
        lines += [f"\t\t\t\t{e}," for e in entries]
        lines += ["\t\t\t);"]
        if isa == "PBXCopyFilesBuildPhase":
            lines.append("\t\t\tdstSubfolderSpec = 13;")
            lines.append("\t\t\tname = \"Embed App Extensions\";")
            lines.append("\t\t\trunOnlyForDeploymentPostprocessing = 0;")
        else:
            lines.append("\t\t\trunOnlyForDeploymentPostprocessing = 0;")
        lines.append("\t\t};")
        return add("phase:" + name, isa, lines)

    app_sources_phase = phase("app-sources", "Sources", "PBXSourcesBuildPhase",
                              [build_files[k] for k in app_src_keys])
    app_frameworks_phase = phase("app-frameworks", "Frameworks", "PBXFrameworksBuildPhase",
                                 [bf_charts])
    app_resources_phase = phase("app-resources", "Resources", "PBXResourcesBuildPhase",
                                [build_files[k] for k in app_res_keys])

    bf_embed = buildfile("embed", widget_product, "DevicePerfMonitorWidget.appex", "in Embed App Extensions")
    embed_phase = phase("app-embed", "Embed App Extensions", "PBXCopyFilesBuildPhase",
                        [bf_embed])

    widget_sources_phase = phase("widget-sources", "Sources", "PBXSourcesBuildPhase",
                                 [bf_shared_widget] + [build_files[k] for k in widget_src_keys
                                                       if k != "widget-src:MonitorActivityAttributes.swift"])
    widget_frameworks_phase = phase("widget-frameworks", "Frameworks", "PBXFrameworksBuildPhase", [])
    widget_resources_phase = phase("widget-resources", "Resources", "PBXResourcesBuildPhase", [])

    # ------------------------------------------------------------------
    # Targets
    # ------------------------------------------------------------------
    target_app = add("target:app", "PBXNativeTarget", [
        f"\t\t{uid('target:app')} /* DevicePerfMonitor */ = {{",
        "\t\t\tisa = PBXNativeTarget;",
        f"\t\t\tbuildConfigurationList = {uid('cl:app')} /* Build configuration list for PBXNativeTarget \"DevicePerfMonitor\" */;",
        "\t\t\tbuildPhases = (",
        f"\t\t\t\t{app_sources_phase} /* Sources */,",
        f"\t\t\t\t{app_frameworks_phase} /* Frameworks */,",
        f"\t\t\t\t{app_resources_phase} /* Resources */,",
        f"\t\t\t\t{embed_phase} /* Embed App Extensions */,",
        "\t\t\t);",
        "\t\t\tbuildRules = (",
        "\t\t\t);",
        "\t\t\tdependencies = (",
        f"\t\t\t\t{uid('dep:widget')} /* PBXTargetDependency */,",
        "\t\t\t);",
        "\t\t\tname = DevicePerfMonitor;",
        "\t\t\tpackageProductDependencies = (",
        f"\t\t\t\t{charts_product} /* Charts */,",
        "\t\t\t);",
        "\t\t\tproductName = DevicePerfMonitor;",
        f"\t\t\tproductReference = {app_product} /* DevicePerfMonitor.app */;",
        "\t\t\tproductType = \"com.apple.product-type.application\";",
        "\t\t};",
    ])

    target_widget = add("target:widget", "PBXNativeTarget", [
        f"\t\t{uid('target:widget')} /* DevicePerfMonitorWidget */ = {{",
        "\t\t\tisa = PBXNativeTarget;",
        f"\t\t\tbuildConfigurationList = {uid('cl:widget')} /* Build configuration list for PBXNativeTarget \"DevicePerfMonitorWidget\" */;",
        "\t\t\tbuildPhases = (",
        f"\t\t\t\t{widget_sources_phase} /* Sources */,",
        f"\t\t\t\t{widget_frameworks_phase} /* Frameworks */,",
        f"\t\t\t\t{widget_resources_phase} /* Resources */,",
        "\t\t\t);",
        "\t\t\tbuildRules = (",
        "\t\t\t);",
        "\t\t\tdependencies = (",
        "\t\t\t);",
        "\t\t\tname = DevicePerfMonitorWidget;",
        "\t\t\tproductName = DevicePerfMonitorWidget;",
        f"\t\t\tproductReference = {widget_product} /* DevicePerfMonitorWidget.appex */;",
        "\t\t\tproductType = \"com.apple.product-type.app-extension\";",
        "\t\t};",
    ])

    dep_widget = add("dep:widget", "PBXTargetDependency", [
        f"\t\t{uid('dep:widget')} /* PBXTargetDependency */ = {{",
        "\t\t\tisa = PBXTargetDependency;",
        f"\t\t\ttarget = {uid('target:widget')} /* DevicePerfMonitorWidget */;",
        f"\t\t\ttargetProxy = {uid('proxy:widget')} /* PBXContainerItemProxy */;",
        "\t\t};",
    ])

    proxy_widget = add("proxy:widget", "PBXContainerItemProxy", [
        f"\t\t{uid('proxy:widget')} /* PBXContainerItemProxy */ = {{",
        "\t\t\tisa = PBXContainerItemProxy;",
        f"\t\t\tcontainerPortal = {uid('proj')} /* Project object */;",
        "\t\t\tproxyType = 1;",
        f"\t\t\tremoteGlobalIDString = {uid('target:widget')};",
        "\t\t\tremoteInfo = DevicePerfMonitorWidget;",
        "\t\t};",
    ])

    # ------------------------------------------------------------------
    # Build Configurations
    # ------------------------------------------------------------------
    def build_config(name, variant, extra):
        base = {
            "ALWAYS_SEARCH_USER_PATHS": "NO",
            "CLANG_ANALYZER_NONNULL": "YES",
            "CLANG_ANALYZER_NUMBER_OBJECT_CONVERSION": "YES_AGGRESSIVE",
            "CLANG_ENABLE_MODULES": "YES",
            "CLANG_ENABLE_OBJC_ARC": "YES",
            "CLANG_ENABLE_OBJC_WEAK": "YES",
            "CLANG_WARN_BLOCK_CAPTURE_AUTORELEASING": "YES",
            "CLANG_WARN_BOOL_CONVERSION": "YES",
            "CLANG_WARN_COMMA": "YES",
            "CLANG_WARN_CONSTANT_CONVERSION": "YES",
            "CLANG_WARN_DEPRECATED_OBJC_IMPLEMENTATIONS": "YES",
            "CLANG_WARN_DIRECT_OBJC_ISA_USAGE": "YES_ERROR",
            "CLANG_WARN_DOCUMENTATION_COMMENTS": "YES",
            "CLANG_WARN_EMPTY_BODY": "YES",
            "CLANG_WARN_ENUM_CONVERSION": "YES",
            "CLANG_WARN_INFINITE_RECURSION": "YES",
            "CLANG_WARN_NON_LITERAL_NULL_CONVERSION": "YES",
            "CLANG_WARN_OBJC_IMPLICIT_RETAIN_SELF": "YES",
            "CLANG_WARN_OBJC_LITERAL_CONVERSION": "YES",
            "CLANG_WARN_OBJC_ROOT_CLASS": "YES_ERROR",
            "CLANG_WARN_QUOTED_INCLUDE_IN_FRAMEWORK_HEADER": "YES",
            "CLANG_WARN_RANGE_LOOP_ANALYSIS": "YES",
            "CLANG_WARN_STRICT_PROTOTYPES": "YES",
            "CLANG_WARN_SUSPICIOUS_MOVE": "YES",
            "CLANG_WARN_UNGUARDED_AVAILABILITY": "YES_AGGRESSIVE",
            "CLANG_WARN_UNREACHABLE_CODE": "YES",
            "CLANG_WARN__DUPLICATE_METHOD_MATCH": "YES",
            "COPY_PHASE_STRIP": "NO",
            "ENABLE_STRICT_OBJC_MSGSEND": "YES",
            "ENABLE_USER_SCRIPT_SANDBOXING": "YES",
            "GCC_C_LANGUAGE_STANDARD": "gnu17",
            "GCC_NO_COMMON_BLOCKS": "YES",
            "GCC_WARN_64_TO_32_BIT_CONVERSION": "YES",
            "GCC_WARN_ABOUT_RETURN_TYPE": "YES_ERROR",
            "GCC_WARN_UNDECLARED_SELECTOR": "YES",
            "GCC_WARN_UNINITIALIZED_AUTOS": "YES_AGGRESSIVE",
            "GCC_WARN_UNUSED_FUNCTION": "YES",
            "GCC_WARN_UNUSED_VARIABLE": "YES",
            "IPHONEOS_DEPLOYMENT_TARGET": "16.1",
            "MTL_FAST_MATH": "YES",
            "SDKROOT": "iphoneos",
            "SWIFT_VERSION": "5.0",
        }
        base.update(extra)
        lines = [f"\t\t{uid('cfg:' + name)} /* {variant} */ = {{",
                 "\t\t\tisa = XCBuildConfiguration;",
                 "\t\t\tbuildSettings = {"]
        for k in sorted(base):
            v = base[k]
            if v == "":
                continue
            lines.append(f"\t\t\t\t{k} = {v};")
        lines += ["\t\t\t};", f"\t\t\tname = {variant};", "\t\t};"]
        return add("cfg:" + name, "XCBuildConfiguration", lines)

    debug_extra = {
        "GCC_DYNAMIC_NO_PIC": "NO",
        "GCC_OPTIMIZATION_LEVEL": "0",
        # old-style plist 里不允许两个相邻引号串（"A" "B" 非法，必须是单个字符串或数组）
        "GCC_PREPROCESSOR_DEFINITIONS": "\"DEBUG=1 $(inherited)\"",
        "MTL_ENABLE_DEBUG_INFO": "INCLUDE_SOURCE",
        "ONLY_ACTIVE_ARCH": "YES",
        "SWIFT_ACTIVE_COMPILATION_CONDITIONS": "DEBUG",
        "SWIFT_OPTIMIZATION_LEVEL": "-Onone",
    }
    release_extra = {
        "GCC_OPTIMIZATION_LEVEL": "s",
        "MTL_ENABLE_DEBUG_INFO": "NO",
        "ONLY_ACTIVE_ARCH": "NO",
        "SWIFT_COMPILATION_MODE": "wholemodule",
        "SWIFT_OPTIMIZATION_LEVEL": "-Owholemodule",
    }

    app_debug_extra = {
        "ASSETCATALOG_COMPILER_APPICON_NAME": "AppIcon",
        "CODE_SIGN_ENTITLEMENTS": "DevicePerfMonitor/DevicePerfMonitor.entitlements",
        "CODE_SIGN_STYLE": "Automatic",
        "CURRENT_PROJECT_VERSION": "1",
        "GENERATE_INFOPLIST_FILE": "NO",
        "INFOPLIST_FILE": "DevicePerfMonitor/Info.plist",
        "MARKETING_VERSION": "1.0.0",
        "PRODUCT_BUNDLE_IDENTIFIER": "com.devperf.monitor",
        "PRODUCT_NAME": "\"$(TARGET_NAME)\"",
        "TARGETED_DEVICE_FAMILY": "\"1,2\"",
    }
    widget_debug_extra = {
        "CODE_SIGN_ENTITLEMENTS": "DevicePerfMonitorWidget/DevicePerfMonitorWidget.entitlements",
        "CODE_SIGN_STYLE": "Automatic",
        "CURRENT_PROJECT_VERSION": "1",
        "GENERATE_INFOPLIST_FILE": "NO",
        "INFOPLIST_FILE": "DevicePerfMonitorWidget/Info.plist",
        "MARKETING_VERSION": "1.0.0",
        "PRODUCT_BUNDLE_IDENTIFIER": "com.devperf.monitor.widget",
        "PRODUCT_NAME": "\"$(TARGET_NAME)\"",
        "SKIP_INSTALL": "YES",
        "TARGETED_DEVICE_FAMILY": "\"1,2\"",
    }

    cfg_app_debug = build_config("app-debug", "Debug", {**debug_extra, **app_debug_extra})
    cfg_app_release = build_config("app-release", "Release", {**release_extra, **app_debug_extra})
    cfg_widget_debug = build_config("widget-debug", "Debug", {**debug_extra, **widget_debug_extra})
    cfg_widget_release = build_config("widget-release", "Release", {**release_extra, **widget_debug_extra})

    cl_app = add("cl:app", "XCConfigurationList", [
        f"\t\t{uid('cl:app')} /* Build configuration list for PBXNativeTarget \"DevicePerfMonitor\" */ = {{",
        "\t\t\tisa = XCConfigurationList;",
        "\t\t\tbuildConfigurations = (",
        f"\t\t\t\t{cfg_app_debug} /* Debug */,",
        f"\t\t\t\t{cfg_app_release} /* Release */,",
        "\t\t\t);",
        "\t\t\tdefaultConfigurationIsVisible = 0;",
        "\t\t\tdefaultConfigurationName = Release;",
        "\t\t};",
    ])
    cl_widget = add("cl:widget", "XCConfigurationList", [
        f"\t\t{uid('cl:widget')} /* Build configuration list for PBXNativeTarget \"DevicePerfMonitorWidget\" */ = {{",
        "\t\t\tisa = XCConfigurationList;",
        "\t\t\tbuildConfigurations = (",
        f"\t\t\t\t{cfg_widget_debug} /* Debug */,",
        f"\t\t\t\t{cfg_widget_release} /* Release */,",
        "\t\t\t);",
        "\t\t\tdefaultConfigurationIsVisible = 0;",
        "\t\t\tdefaultConfigurationName = Release;",
        "\t\t};",
    ])

    proj_debug = build_config("proj-debug", "Debug", {
        **debug_extra,
        "LD_RUNPATH_SEARCH_PATHS": "\"@executable_path/Frameworks\"",
    })
    proj_release = build_config("proj-release", "Release", {
        **release_extra,
        "LD_RUNPATH_SEARCH_PATHS": "\"@executable_path/Frameworks\"",
    })
    cl_project = add("cl:project", "XCConfigurationList", [
        f"\t\t{uid('cl:project')} /* Build configuration list for PBXProject \"DevicePerfMonitor\" */ = {{",
        "\t\t\tisa = XCConfigurationList;",
        "\t\t\tbuildConfigurations = (",
        f"\t\t\t\t{proj_debug} /* Debug */,",
        f"\t\t\t\t{proj_release} /* Release */,",
        "\t\t\t);",
        "\t\t\tdefaultConfigurationIsVisible = 0;",
        "\t\t\tdefaultConfigurationName = Release;",
        "\t\t};",
    ])

    # ------------------------------------------------------------------
    # Project
    # ------------------------------------------------------------------
    add("proj", "PBXProject", [
        f"\t\t{uid('proj')} /* Project object */ = {{",
        "\t\t\tisa = PBXProject;",
        "\t\t\tattributes = {",
        "\t\t\t\tBuildIndependentTargetsInParallel = 1;",
        "\t\t\t\tLastSwiftUpdateCheck = 1500;",
        "\t\t\t\tLastUpgradeCheck = 1500;",
        "\t\t\t\tTargetAttributes = {",
        f"\t\t\t\t\t{uid('target:app')} = {{CreatedOnToolsVersion = 15.0;}};",
        f"\t\t\t\t\t{uid('target:widget')} = {{CreatedOnToolsVersion = 15.0;}};",
        "\t\t\t\t};",
        "\t\t\t};",
        f"\t\t\tbuildConfigurationList = {cl_project} /* Build configuration list for PBXProject \"DevicePerfMonitor\" */;",
        "\t\t\tcompatibilityVersion = \"Xcode 14.0\";",
        "\t\t\tdevelopmentRegion = en;",
        "\t\t\thasScannedForEncodings = 0;",
        "\t\t\tknownRegions = (",
        "\t\t\t\ten,",
        "\t\t\t\tBase,",
        "\t\t\t\t\"zh-Hans\",",
        "\t\t\t);",
        f"\t\t\tmainGroup = {grp_main};",
        f"\t\t\tpackageReferences = ({pkg_ref} /* XCRemoteSwiftPackageReference \"Charts\" */,);",
        f"\t\t\tproductRefGroup = {grp_products} /* Products */;",
        "\t\t\tprojectDirPath = \"\";",
        "\t\t\tprojectRoot = \"\";",
        "\t\t\ttargets = (",
        f"\t\t\t\t{uid('target:app')} /* DevicePerfMonitor */,",
        f"\t\t\t\t{uid('target:widget')} /* DevicePerfMonitorWidget */,",
        "\t\t\t);",
        "\t\t};",
    ])

    # ------------------------------------------------------------------
    # 输出
    # ------------------------------------------------------------------
    lines = [
        "// !$*UTF8*$!",
        "{",
        "\tarchiveVersion = 1;",
        "\tclasses = {",
        "\t};",
        "\tobjectVersion = 54;",
        "\tobjects = {",
        "",
    ]
    for u, type_, body in objects:
        lines += body
        lines.append("")
    lines += [
        "\t};",
        f"\trootObject = {uid('proj')} /* Project object */;",
        "}",
    ]

    os.makedirs(PROJECT_DIR, exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print(f"generated: {OUT}")
    print(f"objects: {len(objects)}")

    # 共享 scheme（命令行 xcodebuild 与 CI 必需；Xcode 新建工程会自动建，
    # 脚本生成的 pbxproj 不带，必须补齐）
    write_scheme("DevicePerfMonitor", "DevicePerfMonitor", "DevicePerfMonitor.app", True)
    write_scheme("DevicePerfMonitorWidget", "DevicePerfMonitorWidget", "DevicePerfMonitorWidget.appex", False)
    return 0


if __name__ == "__main__":
    sys.exit(main())
