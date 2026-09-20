import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :parser 是纯 JVM 解析器模块，不依赖 Android API。

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// 使用 JDK 21 编译工具链，但输出 Java 17 字节码以供 Android 模块消费。
kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(libs.jsoup)
    // 新 URP 的课表响应是结构化 JSON；仅使用 JSON DOM，不引入反射或 Android 依赖。
    implementation(libs.kotlinx.serialization.json)
    // Parser 是纯 JVM 模块，使用 Kotlin Test 固化各学校原始输入到 CoursePreview 的转换契约。
    testImplementation(kotlin("test"))
}
