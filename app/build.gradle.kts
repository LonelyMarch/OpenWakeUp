import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :app Android 主工程
// 支持 Android 13 及以上版本，目标行为版本为 Android 16。

plugins {
    alias(libs.plugins.android.application)
    // 为备份与导入模型生成 kotlinx-serialization 序列化代码。
    alias(libs.plugins.kotlin.serialization)
    // 通过 KSP 生成 Room 数据库实现。
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.openwakeup.schedule"
    // Android 16 的 36.1 属于 minor API：主 API 级别为 36，次 API 级别为 1。
    compileSdk = 36
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "com.openwakeup.schedule"
        minSdk = 33
        targetSdk = 36
        // Android 要求 versionCode 为正整数
        versionCode = 1
        versionName = "0.0.1"
    }

    androidResources {
        // 仅保留中英资源，砍掉库携带的多语言 arsc 表项（zh 族统一由 values-zh 承载）。
        localeFilters += listOf("en", "zh")
    }

    // 轻量化：剥离 Play 依赖元数据与 VCS 信息（纯体积项，无功能影响）
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            // 这些文件只服务于依赖识别、许可证查阅或调试工具，不会被应用运行时代码读取。
            // 从 APK 中排除它们不会改变库行为，同时可避免每个依赖各自携带一份重复元数据。
            excludes += setOf(
                "/DebugProbesKt.bin",
                "/META-INF/*.version",
                "/META-INF/**/LICENSE*",
                "/META-INF/**/verification.properties",
                // 工程未引入 kotlin-reflect；内建声明元数据仅供反射和编译工具读取。
                "/kotlin/**/*.kotlin_builtins"
            )
        }
    }

    buildTypes {
        release {
            // 发布包启用代码压缩与资源收缩，减少 APK 体积。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            vcsInfo {
                include = false
            }
        }
    }

    buildFeatures {
        viewBinding = true
        // 全工程未引用 BuildConfig，关闭以省去生成与打包
        buildConfig = false
    }

    lint {
        // 发布构建跳过 lintVital，正式分发前可手动跑 lint
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    // 仅声明所需的 JDK 主版本，不绑定任何开发者机器上的安装路径。
    jvmToolchain(21)
    compilerOptions {
        // 使用 JDK 21 编译，但与 Android 的 Java 编译目标保持一致，继续输出 Java 17 字节码。
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 纯 JVM 课表解析器模块。
    implementation(project(":parser"))

    // Android UI 基础组件。
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // WebView GET 代发使用 OkHttp 的双栈快速回退、HTTP/2 与连接池。
    implementation(libs.okhttp)

    // Room 数据库。
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    // 序列化与协程。
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
}
