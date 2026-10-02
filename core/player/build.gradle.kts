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

    testOptions {
        // FFmpeg 探测的**失败路径**会写一条日志，而日志最终走到 `android.util.Log`。
        // 默认情况下 JVM 单测里调它直接抛 `Method ... not mocked`——于是一条
        // 「原生库缺失时不能崩」的测试，反而被日志本身搞崩了。
        // 这里让 android.* 的方法返回默认值，和 :feature:player 保持一致。
        unitTests.isReturnDefaultValues = true
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

    // FFmpeg 软件解码（GPL-3.0）。用 `implementation` 而不是 `api`：
    // 这是纯粹的实现细节，不该出现在 PlaybackController 的公开签名里——
    // 一旦 `api`，以后想换内核（换成自己编的 LGPL FFmpeg / VLC）时，
    // 上游模块会连带被牵动。
    implementation(libs.nextlib.media3ext)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // 供 di/PlayerModule 里的 androidContext() 使用（core:common 只暴露了 koin-core）。
    implementation(libs.koin.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
