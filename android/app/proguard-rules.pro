# ---------------------------------------------------------------------------
# R8 / ProGuard 规则
# 目标：release 包体与符号最小化，同时保证 Compose / 图表 / 液态玻璃 / Shizuku
# 的反射与 JNI 入口不被移除。
# ---------------------------------------------------------------------------

# Compose 运行时
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class androidx.compose.ui.** { *; }

# Vico 图表（内部通过工厂/Provider 反射装配图层）
-keep class com.patrykandpatrick.vico.** { *; }

# Kyant0 Backdrop 液态玻璃（1.0.25 起源码 vendor 进 app 模块，仍无条件保留）：
# RuntimeShader / RenderEffect 的装配路径有 native 与内部缓存逻辑，
# 按 Compose 库的常规做法保守保留（代价约 8KB dex）。
-keep class com.kyant.backdrop.** { *; }

# Shizuku：Provider / AIDL 生成的 Stub 必须保留
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# Kotlin 协程：必须完整保留、禁止重打包/改名/改可见性。
# 否则其内部 SynchronizedObject 锁字段在部分 ART（如华为 API36）上会被 R8 优化成
# null，导致启动即崩：StateFlowImpl.setValue 抛 "Null reference used for
# synchronization (monitor-enter)"。此规则须放在 -repackageclasses 之前。
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }
-keep interface kotlinx.coroutines.** { *; }
-keep class kotlinx.coroutines.internal.** { *; }
-keep class kotlinx.coroutines.flow.** { *; }

# 保留行号以便崩溃归因，但隐藏源文件名
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 我们的数据类不混淆（仅本项目包名，便于日志阅读）
-keep class com.devperf.monitor.core.** { *; }

# BuildConfig：保留反馈 token 字段（值是 base64，配合 R8 混淆；不混淆字段名
# 以便运行时稳定读取，字符串内容本身已是 base64 不直接可读）
-keep class com.devperf.monitor.BuildConfig { *; }

# ===========================================================================
# 最强 R8 混淆（应用自身代码层，进一步提高逆向成本）
# 注意：被上方 -keep 的类（core / BuildConfig / kyant / vico / shizuku / compose
# runtime）不会被重包名；其余业务类全部重打包到单一短包并允许同名重载。
# ===========================================================================
-repackageclasses com.dp.a
-allowaccessmodification
-overloadaggressively

# 编译期移除日志（不进 DEX，降低字符串可分析面）
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
}
