plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.multisuperplayer.core.asr"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        // 这里**刻意不写** ndk { abiFilters }：最终打进 APK 的 ABI 由 app 模块决定
        // （`app/build.gradle.kts` 已有一处 abiFilters，和 FFmpeg 共用同一条策略）。
        // 在这里再写一遍就会出现两个「哪里说了算」的答案，而且改了这里不生效。
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
    // api 而不是 implementation：公开签名里用到 core:common 的 MspText，
    // 消费方（feature:player / feature:settings）得能看见它。
    api(project(":core:common"))

    // -----------------------------------------------------------------------
    // 识别引擎：官方 sherpa-onnx 的 AAR **解包**成「一个 jar + jniLibs」再引
    // -----------------------------------------------------------------------
    // 上游发布的是 `sherpa-onnx-1.13.8.aar`（50,129,134 字节），而它的成员**只有**
    //   classes.jar（238,706 字节，122 个类）+ jni/<abi>/*.so（4 个 ABI × 4 个）
    //   + 一个 203 字节的 AndroidManifest + 空的 R.txt / proguard.txt
    // 没有 assets、没有 res、没有 consumer-rules —— 也就是说「解包」和「直接依赖
    // AAR」是**逐字节等价**的，但解包之后可以走 `files(...)`：
    //
    //   settings.gradle.kts 里 `repositoriesMode` 是 FAIL_ON_PROJECT_REPOS，
    //   子模块里既不能加 `repositories {}`，也没有 flatDir 把它指过去。
    //   `files(...)` 不是仓库依赖，不受这条限制。
    //
    // 顺带一个好处：`libs/` 里的 jar 和 `src/main/jniLibs/` 都在版本控制里，
    // 「引擎到底是不是这一份」可以用 sha256 直接回答，不用去猜 Gradle 缓存里
    // 那份 AAR 是哪个版本（缓存里同时躺着好几个 1.11.x 的 media3 就是前车之鉴）。
    //
    // ⚠️ jniLibs 里**只有 `arm64-v8a` 与 `x86_64`**，不是上游那四个 ABI：
    // app 的 abiFilters 只放这两个，所以 `armeabi-v7a` / `x86` 那两套（约 57 MB）
    // 一个字节都不会进 APK，却会让仓库永久变大。要支持 32 位设备时再从
    // `sherpa-onnx-1.13.8.aar` 的 `jni/<abi>/` 里解出来放进这里即可
    // （目录名与文件名必须与上游一致，JNI 是按名字加载的）。
    implementation(files("libs/sherpa-onnx-1.13.8.jar"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.koin.android)

    // 云端识别的响应是 JSON（`verbose_json` 的 segments / 纯 json 的 text）。
    // 用 kotlinx-serialization-json 而不是 android 自带的 `org.json`：后者的
    // 单元测试实现是**空的**（`testOptions.unitTests.isReturnDefaultValues = true`
    // 会让每个方法返回 null/0），也就是解析器一测就「全部读不出来」——
    // 那不是在测解析器，那是在测一个什么都不做的桩。
    // 只用到 `Json.parseToJsonElement`，所以**不需要** kotlin 的序列化编译器插件。
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
