// AGP 9.0 起 Kotlin 支持内置，不再需要（也不允许）应用 org.jetbrains.kotlin.android。
// AGP 9.4.0 内置的 KGP 是 2.2.10，而 Compose BOM 2026.09 与该版本并不同期，
// 因此按官方推荐在顶层显式抬升 KGP 版本，保证 编译器 与 Compose runtime 匹配。
// 版本号须与 gradle/libs.versions.toml 中的 kotlin 保持一致。
buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
