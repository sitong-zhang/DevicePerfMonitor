import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------
// 管理端 App（:manager）com.molecule.manager
//
// 功能（2.0 重写）：账号管控 + 全局/单独公告，数据全部来自自建后端
//       （backend/ Flask + SQLite，账号/验证码/同步/管控 API）。
//       - 密码锁：管理密码 421083，输入一次本地永久记住（除非卸载）；
//       - 用户列表 / 详情：注册用户及其设备数据、操作记录、单独公告；
//       - 管控：禁用功能、限制账号（正常/受限/禁用）、单独发公告、全局公告。
// 安全：集成 :security 全套加固（token 分片 + SO 虚拟化 + 反调试/防篡改），
//       并启用最强 R8 混淆（fullMode + repackageclasses + 混淆字典 + 删日志）。
// 分发：仅限管理人员下载（发布说明标注「仅限管理人员下载」）。
// ---------------------------------------------------------------------------
android {
    namespace = "com.molecule.manager"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.molecule.manager"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "2.0.0"
        vectorDrawables.useSupportLibrary = true

        // 后端服务地址（账号 / 管控 / 公告）：构建可用 -PdevperfServerUrl=... 或
        // 环境变量 DEVPERF_SERVER_URL 覆盖；默认指向本机联调地址。
        val serverUrl = (project.findProperty("devperfServerUrl") as String?)
            ?: System.getenv("DEVPERF_SERVER_URL")
            ?: "http://10.0.2.2:5000"
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
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
