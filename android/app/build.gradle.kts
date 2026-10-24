import com.devperf.monitor.buildlogic.GenerateDesignTokensTask
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------
// design-tokens.json -> DesignTokens.kt 代码生成
// 双端视觉唯一真源：根目录 design-tokens.json。
// 该任务在每次构建前重新生成 DesignTokens.kt，确保业务代码中的色值/圆角/间距/
// 模糊/动效/健康分常量与 JSON 完全一致；业务代码禁止硬编码视觉参数。
//
// 实现放在 buildSrc（编译后的 Task 类），与 Gradle 配置缓存完全兼容：
// 若把生成逻辑写成构建脚本闭包，配置缓存会因「cannot serialize Gradle script
// object references」在每次构建时丢弃缓存条目。
// ---------------------------------------------------------------------------
val designTokensFile = rootProject.file("../design-tokens.json")
val generatedTokenFile = file("src/main/java/com/devperf/monitor/ui/theme/DesignTokens.kt")

val generateDesignTokens by tasks.registering(GenerateDesignTokensTask::class) {
    group = "deviceperf"
    description = "从 design-tokens.json 生成 DesignTokens.kt（请勿手改生成文件）"
    tokensFile.set(designTokensFile)
    outputFile.set(generatedTokenFile)
}

// 生成任务必须先于编译执行
tasks.named("preBuild") { dependsOn(generateDesignTokens) }

android {
    namespace = "com.devperf.monitor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.devperf.monitor"
        minSdk = 24
        targetSdk = 36
        versionCode = 18
        versionName = "1.0.17"
        vectorDrawables.useSupportLibrary = true

        // 液态玻璃开关。1.0.13~1.0.15 曾用 productFlavors 出「玻璃 / 无玻璃」双包，
        // 1.0.16 起用户要求只保留液态玻璃版（单一发布包），这里恒为 true；
        // 保留常量是为了不动各 UI 组件里已有的 BuildConfig.LIQUID_GLASS 分支判定。
        buildConfigField("boolean", "LIQUID_GLASS", "true")
    }

    // 说明：1.0.16 起不再有 productFlavors（原 glass / plain 双包已合并为单包），
    // 产物固定为 app-release.apk，发版命名为 DevicePerfMonitor-<版本>-release.apk。
    // 玻璃实现：Kyant0 Backdrop（io.github.kyant0:backdrop 2.0.0-alpha03），
    // 参数取自库教程既有值（vibrancy + blur + lens），UI 控件仍是 MD3 标准件。

    androidResources {
        // 仅保留中英文资源（AGP 8.5+ 新 API，替代已废弃的 resourceConfigurations）
        localeFilters += setOf("zh-rCN", "en")
    }

    signingConfigs {
        // 签名配置来自 android/keystore.properties（见 keystore.properties.template）。
        // 文件不存在时自动降级为 debug 签名，保证任何环境下 assembleRelease 都不会失败。
        val keystorePropertiesFile = rootProject.file("keystore.properties")
        if (keystorePropertiesFile.exists()) {
            val props = Properties().apply {
                keystorePropertiesFile.inputStream().use { load(it) }
            }
            create("release") {
                storeFile = file(props.getProperty("storeFile"))
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
            // 无 keystore.properties 时降级 debug 签名，并在 README 说明
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        // 关于页版本号直接读 BuildConfig，杜绝手写版本号与打包版本不一致
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
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    // 底栏 NavigationBar 的图标走 material-icons-core（bom 管版本，仅 Home/Settings 两个，
    // R8 + 资源压缩会把其余图标裁掉；material-icons-extended 体积过大，不引）
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    // ServiceViewTreeOwnerTest 需要给 LifecycleRegistry 的主线程校验设委托
    testImplementation(libs.androidx.arch.core.runtime)
    testImplementation(libs.androidx.arch.core.common)

    // 图表（Apache-2.0）
    implementation(libs.vico.compose.m3)
    // 底栏图标（compose-bom 管版本；R8 会裁掉未用图标，不引 extended）
    implementation(libs.compose.material.icons.core)
    // Shizuku（可选提权通道，用于全局 FPS / 后台内存）
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // Kyant0 液态玻璃（1.0.16 起是唯一发布版本，不再有「无玻璃」变体）
    // 版本锁 2.0.0-alpha03 的理由见 libs.versions.toml（2.0.0 正式版要 compileSdk 37 / AGP 9.1+）
    implementation(libs.backdrop)
    implementation(libs.kyant.shapes)
}
