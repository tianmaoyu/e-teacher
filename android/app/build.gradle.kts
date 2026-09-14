import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    // 不再应用 org.jetbrains.kotlin.android：AGP 9.0+ 内置 Kotlin 支持，
    // 重复应用会因 kotlin extension 冲突直接构建失败。
    alias(libs.plugins.kotlin.compose)
}

/**
 * 网关地址可在构建期注入，避免把生产域名硬编码进源码：
 *   ./gradlew assembleRelease -Pspeakmate.gateway=https://api.yourdomain.com
 * 未注入时用默认值（用户也能在 App 设置页里手动改）。
 */
val gatewayUrl: String =
    (project.findProperty("speakmate.gateway") as String?)?.trim().takeUnless { it.isNullOrEmpty() }
        ?: "https://api.speakmate.example"

/**
 * Release 签名只在密钥齐全时才启用。
 * 这样 fork / PR 在没有 Secrets 的情况下也能正常构建，不会因为缺密钥而失败。
 */
val keystorePath: String? = System.getenv("SPEAKMATE_KEYSTORE_PATH")
val signingReady: Boolean = !keystorePath.isNullOrBlank() && File(keystorePath).exists()

android {
    namespace = "com.tianmaoyu.speakmate"
    // Compose BOM 2026.09 起，AndroidX 依赖要求 compileSdk 37
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tianmaoyu.speakmate"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        vectorDrawables { useSupportLibrary = true }
        buildConfigField("String", "DEFAULT_GATEWAY", "\"$gatewayUrl\"")
    }

    if (signingReady) {
        signingConfigs {
            create("release") {
                storeFile = File(keystorePath!!)
                storePassword = System.getenv("SPEAKMATE_KEYSTORE_PASSWORD").orEmpty()
                keyAlias = System.getenv("SPEAKMATE_KEY_ALIAS").orEmpty()
                keyPassword = System.getenv("SPEAKMATE_KEY_PASSWORD").orEmpty()
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (signingReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/INDEX.LIST"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)

    implementation(libs.okhttp)

    debugImplementation(libs.compose.ui.tooling)
}
