plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.multisuperplayer.feature.library"
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

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // rememberLauncherForActivityResult（运行时权限请求）在当前版本的 androidx.activity 里。
    implementation(libs.androidx.activity.compose)
    // koinViewModel()：把 ViewModel 与依赖容器的绑定做成一次调用，
    // 否则每个页面都要自己写 ViewModelProvider.Factory。
    implementation(libs.koin.androidx.compose)
    // viewModel { } DSL（本模块自己注册自己的 ViewModel）。
    implementation(libs.koin.android)

    testImplementation(libs.junit)
}
