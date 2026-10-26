# 安全模块混淆规则
# JNI 入口：TokenVault 的类名与方法名必须保持，否则 C 侧 Java_com_molecule_security_TokenVault_* 找不到。
-keep class com.molecule.security.TokenVault { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# guards 类保留（逻辑清晰 + 防止被意外优化掉关键校验分支）
-keep class com.molecule.security.IntegrityGuard { *; }
-keep class com.molecule.security.EnvironmentGuard { *; }
-keep class com.molecule.security.generated.DexShard { *; }
