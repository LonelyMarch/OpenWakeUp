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
}
