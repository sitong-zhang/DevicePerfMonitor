plugins {
    `kotlin-dsl`
}

// kotlin-dsl 插件会引入 kotlin-stdlib，下面的 groovy-json 也是外部依赖，
// buildSrc 是独立构建、不继承主工程 settings 里的仓库配置，必须自己声明，
// 否则 CI 上报 "Cannot resolve external dependency ... no repositories are defined"。
repositories {
    mavenCentral()
}

// buildSrc：承载 design-tokens 代码生成任务。
// 用编译后的 Task 类（而非构建脚本闭包）实现，使 generateDesignTokens
// 完全兼容 Gradle 配置缓存（configuration cache），避免每次构建都
// 丢弃缓存条目并打印「cannot serialize Gradle script object references」。
dependencies {
    // Gradle API（DefaultTask / TaskAction / RegularFileProperty 等）
    implementation(gradleApi())
    // JsonSlurper 来自 groovy-json（Gradle 运行时自带）
    implementation("org.codehaus.groovy:groovy-json:3.0.22")
}

