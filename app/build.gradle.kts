plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.myapplicationfirstapp"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.myapplicationfirstapp"
        minSdk = 24
        targetSdk = 37
        versionCode = 2
        versionName = "2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // sherpa-onnx 的 .so 只带 arm64 就够（目标机全是 64 位 ARM），
        // 不裁的话 APK 会白白多出 v7a / x86 / x86_64 三份 30MB
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // 本地 AAR：sherpa-onnx 没有发布到 Maven Central，从 GitHub Release 取回后随工程走
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    // 成套的官方图标（Mic / Pause / Stop / Loop / Description 等），
    // 比手写 vector path 的描边和视觉重心稳得多；release 会被 R8 裁到只留用到的
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.exifinterface)
    // IMG-1A: local-image Compose integration; explicit shared ImageLoader.
    implementation(libs.coil.compose.base)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    // IMG-1A 本地修正：BOM 原先只加在 implementation，不会传递到 androidTest 配置，
    // 导致 ui-test-junit4 无版本、测试 APK 无法解析（此前从未构建过 androidTest）。
    // 仅给测试类路径补同一份 BOM，不改变任何版本号。
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}