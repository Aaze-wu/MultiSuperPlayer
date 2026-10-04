plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.multisuperplayer.core.ui"
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
    api(project(":core:common"))
    api(project(":core:model"))

    implementation(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    api(libs.compose.foundation)
    api(libs.compose.material3)
    api(libs.compose.material.icons.extended)
    api(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // 封面取色在 core:data 里做（Palette 是数据层的活），这里不再声明依赖。
    //
    // 画封面则必须在这里：列表行（feature:library）与播放页（feature:player）都要画
    // 同一张封面，而它们唯一的共同依赖就是这个设计系统模块。模型类型
    // （`ArtworkRequest`）在 core:model——所以这里**不需要**依赖 core:data：
    // 取图的 Fetcher 是应用级 ImageLoader 注册的（见 `artworkImageLoader`）。
    implementation(libs.coil.compose)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
