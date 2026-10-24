# ---------------------------------------------------------------------------
# glass flavor 专用 R8 规则（1.0.13 双包）
#
# 由 build.gradle.kts 的 glass flavor proguardFile 引入，只对 glass 变体生效：
# plain flavor 完全不引用 Kyant0 Backdrop，共享的 proguard-rules.pro 里不写
# 这条 keep，R8 才能把整个库（classes + 资源引用）从 plain 包里裁干净。
#
# 保留原因：库里 RuntimeShader / RenderEffect 的装配路径有 native 与内部
# 缓存逻辑，混缩入口按 Compose 库的常规做法保守保留（代价约 8KB dex）。
# ---------------------------------------------------------------------------

-keep class com.kyant.backdrop.** { *; }
