rootProject.name = "palette-gen"

// 离线配色生成工具（1.0.9 起）：从种子色生成 Material 3 Expressive 色板，
// 写回仓库根的 design-tokens.json。
//
// 刻意**不**被 android/settings.gradle.kts 或任何 CI workflow 引用：
// 色值生成一次就固化进 design-tokens.json 并签入，运行时零依赖、构建期零依赖。
// 需要换种子色时才人工跑一次：
//   cd android && ./gradlew -p ../Tools/palette-gen run --args="--write"
dependencyResolutionManagement {
    repositories { mavenCentral() }
}
