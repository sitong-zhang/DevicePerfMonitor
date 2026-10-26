import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// ---------------------------------------------------------------------------
// 安全加固模块（:security）
//
// 职责：把「管理端写权限 token」以「分片异地存储 + 运行时组合还原」的方式保护起来，
// 并对调用方（仅 :manager）提供受控的 TokenVault.getToken()。
//
// 设计要点（对应需求里的 Dex 层 / Native 层 / 反逆向辅助）：
//   - token 明文绝不进 git、绝不进源码常量表：构建期由 generateTokenShards 从
//     local.properties 的 MANAGER_GITHUB_TOKEN 读取，拆成两半——
//       · 前半 A 在 DEX 侧（Kotlin 常量），XOR 加密，key 由 SO 提供；
//       · 后半 B 在 SO 侧，以自定义虚拟机(VM)字节码形式存放，运行时由 VM 解释还原
//         （代码虚拟化，静态看不到明文）。
//   - 运行时 TokenVault 组合：A_xor XOR K → A；SO VM 还原 → B；token = A + B；
//     还原后立即用 CharArray 持有、用后清零。
//   - 受 EnvironmentGuard（Root/Frida/Xposed/模拟器/调试器检测）与 IntegrityGuard
//     （签名校验）约束：环境不纯或 APK 被篡改时软失败，不解密、返回 null。
//
// 注意：客户端本地混淆只能提高逆向成本，无法 100% 防提取。本模块是「提高成本」
// 的工程实现；真正的安全边界是「token 的最小权限」——强烈建议 :manager 使用
// 仅授权本仓库 Contents:write + Issues:read 的最小权限 fine-grained token，而非
// 全权限 classic token（详见交付说明）。
// ---------------------------------------------------------------------------

android {
    namespace = "com.molecule.security"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        ndkVersion = "26.1.10909125"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c11"
                // 关闭 NDK 自带的符号/调试便利，减小可分析面
                arguments += "-DANDROID_STL=none"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            externalNativeBuild {
                cmake {
                    // debug 也编 SO，方便本地联调
                    cppFlags += "-O2"
                }
            }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            externalNativeBuild {
                cmake {
                    cppFlags += "-O3"
                }
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

// ---------------------------------------------------------------------------
// generateTokenShards：构建期生成 token 分片（gitignored，明文不进仓库）
//
// 读取 rootProject/../local.properties 的 MANAGER_GITHUB_TOKEN：
//   - 空：写占位（空数组），TokenVault 软失败，:manager 仍可编译运行（只是不能发公告）。
//   - 非空：拆两半，生成 DexShard.kt（A 的 XOR 密文）与 generated_shard.h（XOR key +
//     B 的 VM 字节码）。
//
// 该 task 在 preBuild 之前执行；不声明 token 为 input（避免写入构建缓存/配置缓存），
// 每次构建都重算，确保 local.properties 改动立即生效。
// ---------------------------------------------------------------------------
val tokenShardDir = file("src/main/cpp")
val dexShardFile = file("src/main/java/com/molecule/security/generated/DexShard.kt")

val generateTokenShards by tasks.registering {
    group = "security"
    description = "从 local.properties 生成 token 分片（DEX XOR 密文 + SO VM 字节码）"
    // 该任务在 doLast 内引用 rootProject 等脚本对象，无法序列化到配置缓存，
    // 显式声明不兼容配置缓存（每次构建实时重算，符合「token 改动立即生效」设计）。
    notCompatibleWithConfigurationCache("token 分片生成任务在 doLast 内引用 rootProject 等脚本对象，无法序列化到配置缓存")
    outputs.upToDateWhen { false }

    doLast {
        val rootPropsFile = rootProject.file("local.properties")
        val token = if (rootPropsFile.exists()) {
            val p = Properties().apply { rootPropsFile.inputStream().use { load(it) } }
            (p.getProperty("MANAGER_GITHUB_TOKEN") ?: "")
        } else ""

        tokenShardDir.mkdirs()
        val genDir = file("src/main/java/com/molecule/security/generated")
        genDir.mkdirs()

        if (token.isBlank()) {
            // 占位：无 token 时 DexShard 为空，TokenVault 直接返回 null
            dexShardFile.writeText(
                """
                // 自动生成（generateTokenShards）——请勿手改。
                // 未检测到 local.properties 的 MANAGER_GITHUB_TOKEN，分片为空。
                package com.molecule.security.generated

                internal object DexShard {
                    val encA: ByteArray = byteArrayOf()
                    val hasShard: Boolean = false
                }
                """.trimIndent(),
            )
            file("$tokenShardDir/generated_shard.h").writeText(
                """
                /* 自动生成（generateTokenShards）——请勿手改。占位：无 token。 */
                #pragma once
                static const unsigned char g_xor_key[] = {0};
                static const int g_xor_key_len = 0;
                static const unsigned char g_b_vm[] = {0};
                static const int g_b_vm_len = 0;
                """.trimIndent(),
            )
            logger.lifecycle("[security] 未配置 MANAGER_GITHUB_TOKEN，token 分片为空（:manager 发公告功能将不可用，但可编译运行）。")
            return@doLast
        }

        val bytes = token.toByteArray(Charsets.UTF_8)
        val half = (bytes.size + 1) / 2
        val a = bytes.copyOfRange(0, half)
        val b = bytes.copyOfRange(half, bytes.size)

        // XOR key：与 token 无关的固定常量（编译期混入 SO，运行时取回）。
        // 用固定字节而非 RNG，保证每次构建确定的 key，且 key 由本任务同时写入
        // SO（g_xor_key）与 DEX（encA = A XOR key），两侧必须一致。
        val key = byteArrayOf(
            0x3C, 0x91.toByte(), 0xA7.toByte(), 0x1E, 0x5B.toByte(), 0xC4.toByte(),
            0x08, 0xF2.toByte(), 0x6D, 0x97.toByte(), 0x2A, 0xE5.toByte(), 0x4F, 0x13,
            0x89.toByte(), 0x70,
        )

        val encA = ByteArray(a.size) { (a[it].toInt() and 0xFF xor (key[it % key.size].toInt() and 0xFF)).toByte() }

        // B 以自定义 VM 字节码存放：每条指令 3 字节（op, arg0, arg1）。
        // VM 程序把 B 的每个字节做 (byte + 7) XOR 0x5A 的逆运算后 EMIT 出来。
        // 静态看不到 B 明文，只能看到一段「程序」。
        val vm = mutableListOf<Int>()
        for (i in b.indices) {
            val raw = b[i].toInt() and 0xFF
            val stored = (raw - 7 + 256) and 0xFF xor 0x5A // 编码：enc = (raw-7) xor 0x5A
            vm.add(0x01) // LOAD_IMM
            vm.add(stored)
            vm.add(0x00)
            vm.add(0x02) // VM_XOR_CONST 0x5A
            vm.add(0x5A)
            vm.add(0x00)
            vm.add(0x03) // VM_ADD 7
            vm.add(0x07)
            vm.add(0x00)
            vm.add(0x04) // EMIT
            vm.add(0x00)
            vm.add(0x00)
        }
        vm.add(0xFF) // HALT
        vm.add(0x00)
        vm.add(0x00)

        val encABytes = encA.joinToString(",") { "(0x%02X).toByte()".format(it.toInt() and 0xFF) }
        dexShardFile.writeText(
            """
            // 自动生成（generateTokenShards）——请勿手改。token 前半(A)的 XOR 密文。
            package com.molecule.security.generated

            internal object DexShard {
                // A XOR key（key 由 SO 提供）。明文 token 不在此处。
                val encA: ByteArray = byteArrayOf($encABytes)
                val hasShard: Boolean = true
            }
            """.trimIndent(),
        )

        val keyBytes = key.joinToString(",") { "0x%02X".format(it.toInt() and 0xFF) }
        val vmBytes = vm.joinToString(",") { "0x%02X".format(it) }
        file("$tokenShardDir/generated_shard.h").writeText(
            """
            /* 自动生成（generateTokenShards）——请勿手改。token 后半(B)以 VM 字节码存放。 */
            #pragma once
            #include <stddef.h>
            static const unsigned char g_xor_key[] = {$keyBytes};
            static const int g_xor_key_len = (int)(sizeof(g_xor_key)/sizeof(g_xor_key[0]));
            static const unsigned char g_b_vm[] = {$vmBytes};
            static const int g_b_vm_len = (int)(sizeof(g_b_vm)/sizeof(g_b_vm[0]));
            """.trimIndent(),
        )
        logger.lifecycle("[security] token 分片已生成（DEX XOR 密文 + SO VM 字节码），明文未写入仓库。")
    }
}

// 生成任务必须先于编译与 native 构建
tasks.named("preBuild") { dependsOn(generateTokenShards) }
afterEvaluate {
    tasks.matching { it.name.startsWith("externalNativeBuild") || it.name == "generateJsonModel" }.configureEach {
        dependsOn(generateTokenShards)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
