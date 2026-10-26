# 管理端最强 R8 混淆规则
# 目标：在应用自身代码层把逆向成本拉满（名称混淆 + 重包名 + 激进重载 + 删日志）。
# 注意：客户端混淆只能提高成本，真正的安全边界仍是 token 最小权限。

# 1) 全量重命名：把所有类重打包到单一短包，并允许修改访问修饰符、同名重载
-repackageclasses com.m.a
-allowaccessmodification
-overloadaggressively

# 2) 删除日志调用（assumenosideeffects → 编译期移除，不进 DEX）
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
}
-assumenosideeffects class java.io.PrintStream {
    public void println(...);
}

# 3) JNI / 安全模块：类名与方法名必须保持（C 侧按 Java_com_molecule_security_TokenVault_* 查找）
-keep class com.molecule.security.TokenVault { *; }
-keep class com.molecule.security.IntegrityGuard { *; }
-keep class com.molecule.security.EnvironmentGuard { *; }
-keep class com.molecule.security.generated.DexShard { *; }

# 4) Android 入口与 Compose / ViewModel 运行时所需保留
-keep class com.molecule.manager.MainActivity { *; }
-keep class com.molecule.manager.ManagerViewModel { *; }
-keep class org.json.** { *; }

# 5) 抑制无关告警
-dontwarn org.json.**
