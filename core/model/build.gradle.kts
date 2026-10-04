plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.multisuperplayer.core.model"
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

// core:model 在**模块图上**保持零依赖（不依赖任何其它 project），任何层都能安全引用。
// 唯一的第三方依赖是 androidx.annotation：它只有注解、没有代码，是 MspText.Res 的
// @StringRes 用的，换掉的是 lint 对「传了个 drawable id」的拦截。
dependencies {
    api(libs.androidx.annotation)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
