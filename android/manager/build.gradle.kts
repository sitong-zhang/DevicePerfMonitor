import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------
// 管理端 App（:manager）com.molecule.manager
//
// 功能：① 公告管理（读线上 announcement.json + 编辑发布，唯一写路径）；
//       ② 反馈收件箱（GET issues?labels=feedback，纯读）。
// 安全：集成 :security 全套加固（token 分片 + SO 虚拟化 + 反调试/防篡改），
//       并启用最强 R8 混淆（fullMode + repackageclasses + 混淆字典 + 删日志）。
// 分发：仅本地交付，不挂公开 Release（写权限 token 不外泄）。
// ---------------------------------------------------------------------------
android {
    namespace = "com.molecule.manager"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.molecule.manager"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        val keystorePropertiesFile = rootProject.file("keystore.properties")
        if (keystorePropertiesFile.exists()) {
            val props = Properties().apply {
                keystorePropertiesFile.inputStream().use { load(it) }
            }
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
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
}

dependencies {
    implementation(project(":security"))

    // 液态玻璃：Kyant0/AndroidLiquidGlass 的 shapes 配套库（Capsule 等形状）；
    // backdrop 库本体（2.0.1）原样 vendor 进 src/main/java/com/kyant/backdrop/，
    // 与用户端 app 模块同一份源码，效果参数与官方 demo 一致。
    implementation(libs.kyant.shapes)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
