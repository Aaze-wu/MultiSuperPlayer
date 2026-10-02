plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.multisuperplayer.core.data"
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
    api(project(":core:model"))
    implementation(project(":core:common"))
    // 外挂字幕的读取与解析。解析器本身在 core:subtitle（纯逻辑、可单测），
    // 这里只负责「找到文件 → 读字节 → 交给解析器」。
    implementation(project(":core:subtitle"))
    // 翻译设置的持久化要用它的类型：服务商预设、目标语言、术语表、引擎配置。
    api(project(":core:translate"))
    // 续播位置的实现要实现 core:player 的 PlaybackPositionStore。
    // 方向是 data → player 而不是反过来：内核不该知道 DataStore 的存在。
    // 用 `implementation`：PlaybackPositionStore 里没有 Media3 类型，
    // 不需要把它传给更上层。
    implementation(project(":core:player"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.datastore.preferences)

    // 从封面里量化出种子色（ArtworkPaletteRepository）
    implementation(libs.androidx.palette.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.koin.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
