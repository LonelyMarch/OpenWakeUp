// 根构建脚本：仅把插件加入 classpath（apply false），由各子模块自行应用
// 插件与依赖版本统一维护在 gradle/libs.versions.toml。

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
