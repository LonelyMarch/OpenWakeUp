// OpenWakeUp Android 工程设置

pluginManagement {
    repositories {
        // 官方仓库优先（Gradle 按"元数据归属"取工件，镜像缺件会导致解析失败），阿里云仅兜底
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
    }
}

dependencyResolutionManagement {
    // 禁止各模块自行声明仓库，依赖仓库统一在此管理
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
    }
}

rootProject.name = "OpenWakeUp"

// :app 是 Android 主工程，:parser 是不依赖 Android API 的纯 JVM 解析器库。
include(":app", ":parser")
