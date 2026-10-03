plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.multisuperplayer.feature.settings"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
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
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:ui"))
    // 「强制软件解码」那一行要如实告诉用户「本安装包到底带没带 FFmpeg」：
    // 不带的时候开关会被置灰并说明原因，而不是拨了没反应。
    // 只用到 `SoftwareDecoderSupport` 这一个接口（Koin 在 playerModule 里绑定），
    // 不会把 nextlib 暴露到设置模块。
    implementation(project(":core:player"))
    // 「语音识别」子页要真的管模型：读体积（`AsrModelLocator.statusOf`）、下载
    // （`AsrModelInstaller`）、删除（`remove`），还要用 `AsrModelCatalog` 这两条
    // 候选列出模型和体积。这些都是 `:core:asr` 自己的公开类型——`:core:data`
    // 虽然也依赖它，但**不要**靠别的模块把依赖顺过来：哪天 `:core:data` 不再需要
    // ASR，这个模块就会莫名其妙编译不过。
    implementation(project(":core:asr"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // 「关于」页导出日志要用 `rememberLauncherForActivityResult` +
    // `ActivityResultContracts.CreateDocument`（SAF 让用户自己选保存位置）。
    // 不用 FileProvider + 分享：那个要在 `:app` 的清单里加一个 provider，
    // 而 SAF 只需要这一个依赖，且文件落点由用户决定（Android 11 起
    // `Android/data` 不可浏览，写到自己的外部私有目录等于写进黑洞）。
    implementation(libs.androidx.activity.compose)
    implementation(libs.koin.androidx.compose)
    implementation(libs.koin.android)

    testImplementation(libs.junit)
}
