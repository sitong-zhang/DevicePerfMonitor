plugins {
    kotlin("jvm") version "2.4.21"
    application
}

dependencies {
    // Google 官方 Material Color Utilities 的 Kotlin 移植（MIT）。
    // 只依赖 kotlin-stdlib，**没有任何 Compose / Android 依赖**——
    // 所以它只在构建期跑，不会被打进 APK。
    implementation("com.materialkolor:material-color-utilities:5.0.1")
}

application {
    mainClass.set("MainKt")
}
