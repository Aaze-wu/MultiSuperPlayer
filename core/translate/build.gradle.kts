plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.multisuperplayer.core.translate"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
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
    // HttpURLConnection 是 JDK/android.jar 自带的，所以这个模块**不引入任何 HTTP 依赖**：
    // 只多一个 kotlinx-serialization-json（版本目录里已经有）用来读写请求体与缓存。
    // api 而不是 implementation：TranslationEngine 的构造签名里有 core:common 的
    // DispatcherProvider，消费方（core:data 在 DI 里装配它）得能看见它。
    // （TranslationService / FailureText / SubtitleExportFormat 里的 MspText 现在由
    // 上面那条 core:model 提供——它俩原来是同一条 api 在兼顾。）
    api(project(":core:common"))
    // 设备上的那一路。本模块**不认识** LiteRT，只认识 `:core:llm` 的公开接口
    // (LlmTextGenerator / LlmModelCatalog)，真实现由 core:data 在 DI 里装配。
    // implementation 而不是 api：本模块的公开签名里没有任何 core:llm 的类型
    // （TranslationConfig 上那个「是否跑在设备上」只是一个 Boolean），
    // 消费方不需要把 21 MB 的原生库也拉进自己的编译类路径。
    implementation(project(":core:llm"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.koin.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
