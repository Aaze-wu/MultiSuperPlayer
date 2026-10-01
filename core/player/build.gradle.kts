plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.multisuperplayer.core.player"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    api(project(":core:common"))
    api(project(":core:model"))

    // Media3：播放内核 + 网络协议 + 会话
    api(libs.media3.common)
    api(libs.media3.exoplayer)
    api(libs.media3.exoplayer.hls)
    api(libs.media3.exoplayer.dash)
    api(libs.media3.exoplayer.rtsp)
    api(libs.media3.datasource.okhttp)
    api(libs.media3.ui)
    api(libs.media3.session)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // 供 di/PlayerModule 里的 androidContext() 使用（core:common 只暴露了 koin-core）。
    implementation(libs.koin.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
