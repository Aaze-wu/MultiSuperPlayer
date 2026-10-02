plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.multisuperplayer.feature.player"
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
        // 纯 JVM 单测不去真正加载 Android 框架：没桩的方法返回默认值。
        // 这里测的是状态合成规则（纯函数），碰不到这些方法，但保持和其他模块一致。
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
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:ui"))
    implementation(project(":core:subtitle"))
    implementation(project(":core:player"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // koinViewModel()：把 ViewModel 与依赖容器的绑定做成一次调用。
    implementation(libs.koin.androidx.compose)
    // viewModel { } DSL（本模块自己注册自己的 ViewModel）。
    implementation(libs.koin.android)

    testImplementation(libs.junit)
}
