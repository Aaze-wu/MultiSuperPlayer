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

    testOptions {
        // 文件系统的字幕发现（FileSystemSubtitleLocator）只依赖 `java.io.File`，
        // 所以它能用真的目录树做单测——但它会写日志，而日志最终走到
        // `android.util.Log`。默认情况下 JVM 单测里调它直接抛
        // `Method println in android.util.Log not mocked`，于是一条
        // 「读不到目录时返回 Invisible」的测试反而被日志本身搞崩了。
        // 这里让 android.* 的方法返回默认值，和其余模块保持一致。
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
    implementation(project(":core:common"))
    // 外挂字幕的读取与解析。解析器本身在 core:subtitle（纯逻辑、可单测），
    // 这里只负责「找到文件 → 读字节 → 交给解析器」。
    implementation(project(":core:subtitle"))
    // 翻译设置的持久化要用它的类型：服务商预设、目标语言、术语表、引擎配置。
    api(project(":core:translate"))
    // 语音识别的偏好（选哪条模型、从哪个源下载）要把模型清单类型传给界面：
    // 设置页要显示模型名、体积、语言，这些都定义在 core:asr。
    api(project(":core:asr"))
    // 设备上的翻译模型同理：清单/位置/下载/安装器的类型都要传到设置页
    // （模型名、体积、下载进度），所以是 api 而不是 implementation。
    api(project(":core:llm"))
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
    // 封面的取图与落盘缓存（ArtworkFetcher / ArtworkLoader）。
    // 用 `coil-core` 而不是 `coil-compose`：这里只需要 Fetcher / Keyer / ImageSource，
    // 界面的那部分（AsyncImage）在 core:ui。
    implementation(libs.coil.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.koin.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
