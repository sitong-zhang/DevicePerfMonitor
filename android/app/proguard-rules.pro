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

# Kyant0 Backdrop 液态玻璃（含 RenderEffect 相关 native 入口）
-keep class com.kyant.backdrop.** { *; }

# Shizuku：Provider / AIDL 生成的 Stub 必须保留
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# Kotlin 协程
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# 保留行号以便崩溃归因，但隐藏源文件名
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 我们的数据类不混淆（仅本项目包名，便于日志阅读）
-keep class com.devperf.monitor.core.** { *; }
