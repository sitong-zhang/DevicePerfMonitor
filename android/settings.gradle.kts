pluginManagement {
    repositories {
        // AGP / AndroidX 只在 google 仓库解析，其余走 mavenCentral，避免无关仓库拖慢构建
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }

    // 集中声明 Android Gradle 插件版本：app(application) 与 security(library) 共用同一
    // AGP 工件，版本在 settings 统一声明后，模块侧以无版本号 alias 引用，可避免
    // 「plugin already on classpath with unknown version」冲突。
    plugins {
        id("com.android.application") version "8.13.2"
        id("com.android.library") version "8.13.2"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DevicePerfMonitor"
include(":app")
include(":security")
include(":manager")
