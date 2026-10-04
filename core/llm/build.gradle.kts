plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.multisuperplayer.core.llm"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        // 同 core:asr：这里**刻意不写** ndk { abiFilters }，最终打进 APK 的 ABI
        // 由 app 模块一处决定（`app/build.gradle.kts`）。LiteRT-LM 的 AAR 里只有
        // arm64-v8a 与 x86_64 这两套 .so，正好落在 app 的 abiFilters 里。
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
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
    api(project(":core:model"))
    // api 而不是 implementation：LlmTextGenerator 的装配签名里有 core:common 的
    // DispatcherProvider（还有 MspLog / TimeFormat），消费方（core:data / feature:settings）
    // 得能看见它们。MspText 原来的家也是 core:common，现已随它搬去 core:model。
    api(project(":core:common"))

    // -----------------------------------------------------------------------
    // 设备上推理引擎：LiteRT-LM 官方 AAR，**直接依赖**（不解包）
    // -----------------------------------------------------------------------
    // 与 core:asr 的 sherpa-onnx 不同，这里不需要解包成 jar + jniLibs：
    //
    // 1. 它发布在 **Google Maven** 上，而 `settings.gradle.kts` 的仓库列表里
    //    本来就有它（阿里云 google 镜像 + `google()`），所以能作为普通仓库依赖解析。
    //    sherpa-onnx 之所以要解包，是因为它不在任何一个已配置的仓库里。
    // 2. 解包的另一个好处是「把二进制放进版本控制、能用 sha256 回答『引擎到底是哪一份』」。
    //    代价是仓库永久变大：这个 AAR 里的原生库未压缩合计约 47.8 MB（两套 ABI），
    //    而 app 的 abiFilters 正好只要这两套，解包省不下一个字节。
    //    版本由 `libs.versions.toml` 写死（不是 `latest.release`），可复现性由版本目录保证。
    // 3. AAR 里没有 `aar-metadata.properties`（已核对），所以不受
    //    `minCompileSdk` / `minAndroidGradlePluginVersion` 约束。
    //
    // ⚠️ 它会带进 gson 2.14.0 与 kotlin-reflect 2.4.0（POM 里的 compile scope）。
    // ⚠️ release 包开了 R8，而这个 AAR **不带 proguard.txt / consumer rules**，
    //    所以 keep 规则在 `app/proguard-rules.pro` 里，理由见那里。
    implementation(libs.litertlm.android)

    implementation(libs.kotlinx.coroutines.core)

    // 注意这里**没有** Koin：引擎的构造函数是 internal 的，装配留在 core:data 的
    // DataModule 里（与 core:asr 的 AsrTranscriber.create 同一套做法）。

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
