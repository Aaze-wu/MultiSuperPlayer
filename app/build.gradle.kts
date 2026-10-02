
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---------------------------------------------------------------------------
// 版本号：唯一来源
// ---------------------------------------------------------------------------
// 只在这里写一次版本号，`versionCode` 与 BuildConfig 都由它推出来。
//
// 改成这个样子是有教训的：以前 versionCode/versionName 是手写的两个常量，结果
// git tag 已经打到 v0.5.3 了，包里还写着 0.4.0 —— 「关于」页（或安装器）看到的版本
// 与实际代码不是一回事，而且**不会有任何东西报错**。
//
// versionCode 编码：major*10000 + minor*100 + patch。
//   0.5.4 →   504      0.6.0 →   600      1.0.0 → 10000
// 注意 **major 是 0**，所以现在这一串是三位数——别再照着「0.5.4 → 50400」去核对，
// 那是我一开始写错的示例值（把 minor 当成了 major）。
// 约束：必须严格递增（否则安装器拒绝覆盖），且小于 2100000000（Google Play 的硬上限）。
val appVersionName: String = "0.5.12"
val appVersionCode: Int = run {
    val parts = appVersionName.split('.')
    require(parts.size == 3) { "版本号必须是 x.y.z 三段，当前是 \"$appVersionName\"" }
    val numbers = parts.map { part ->
        part.toIntOrNull() ?: error("版本号里 \"$part\" 不是数字（$appVersionName）")
    }
    require(numbers[1] < 100 && numbers[2] < 100) {
        "minor/patch 必须小于 100，否则会和 major*10000+minor*100+patch 的编码撞车"
    }
    numbers[0] * 10_000 + numbers[1] * 100 + numbers[2]
}

// ---------------------------------------------------------------------------
// 构建信息注入
// ---------------------------------------------------------------------------
// 「关于」页要显示 commit 和构建时间，而它们只有构建时才知道。
//
// 用 git 直接问，而不是手工维护常量——手工的那份必然忘记更新，而且**看不出来**。
// git 不在 PATH 上（CI 镜像、导出成 zip 的源码）时全部降级成空串，
// 由 `AppBuildInfo.Unknown` 在界面上显示成「未知」，不会构建失败。
//
// 注：本项目的 gradle.properties 没有开启配置缓存，所以配置阶段跑外部进程是安全的；
// 将来若开启，这里必须改成 `providers.exec {}`。
val repositoryDir = rootProject.projectDir

fun gitOutput(vararg args: String): String = runCatching {
    val process = ProcessBuilder(listOf("git") + args)
        .directory(repositoryDir)
        .redirectErrorStream(true)
        .start()
    val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return@runCatching ""
    }
    if (process.exitValue() == 0) text else ""
}.getOrDefault("")

// `--always`：没有可达的 tag 时退回短 sha；`--dirty`：有未提交改动时加后缀。
val gitDescribe: String = gitOutput("describe", "--tags", "--always", "--dirty")
val gitCommit: String = gitOutput("rev-parse", "--short", "HEAD")
val gitDirty: Boolean = gitDescribe.endsWith("-dirty")
val gitTag: String = gitDescribe.removeSuffix("-dirty")

// 构建时间在**构建机**上就格式化好，并把时区偏移一起写进去。
// 只存 epoch 毫秒、到设备上再格式化的话，构建机与设备时区不同就会显示成一个偏移几小时的假时间。
//
// 注：这里只能写 `ZonedDateTime`，不能写 `java.time.ZonedDateTime` —— 在 Gradle Kotlin DSL
// 里 `java` 会被解析成 `Project.java` 扩展，拿包名写会报 “Unresolved reference: time”。
val buildTime: String = ZonedDateTime.now()
    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm XXX"))

fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// ---------------------------------------------------------------------------
// Release 签名（模板）
// ---------------------------------------------------------------------------
// 把根目录的 keystore.properties.example 复制为 keystore.properties 并填入真实值。
// 该文件已被 .gitignore 忽略。文件缺失时 release 自动回退到 debug 签名，
// 保证新克隆的仓库 / CI 依然能产出可安装的包，而不是直接构建失败。
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseKeystore = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "com.multisuperplayer.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.multisuperplayer.player"
        minSdk = 26
        targetSdk = 36
        // 版本号由文件顶部的 appVersionName 推导，不要再在这里写死数字（见那里的注释）。
        versionCode = appVersionCode
        versionName = appVersionName

        vectorDrawables { useSupportLibrary = true }

        // 「关于」页与日志抬头要用的构建信息（见 core:common 的 AppBuildInfo）。
        buildConfigField("String", "GIT_COMMIT", quoted(gitCommit))
        buildConfigField("String", "GIT_TAG", quoted(gitTag))
        buildConfigField("boolean", "GIT_DIRTY", gitDirty.toString())
        buildConfigField("String", "BUILD_TIME", quoted(buildTime))

        // FFmpeg 软件解码扩展带四个 ABI 的原生库，全打进一个包会让 APK 无谓地大一倍：
        // 实测 arm64-v8a 7.5MB + x86_64 11.1MB（未压缩），而 armeabi-v7a/x86 这两个
        // 32 位架构在 2016 年之后就基本见不到了。只保留主流 64 位 + 模拟器架构。
        //
        // 少打两个 ABI 的**代价**是：32 位设备上装不上（不是装上了用不了，是安装器
        // 直接拒绝）。若以后要覆盖老设备，正确做法是加 ABI split 或改发 AAB，
        // 而不是把四个全塞回一个包里。
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // 打开 BuildConfig：它在 AGP 8 里默认是**关**的，而「关于」页要靠它拿构建信息。
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
            )
        }
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
    implementation(project(":core:subtitle"))
    implementation(project(":core:player"))
    implementation(project(":core:ui"))
    implementation(project(":feature:library"))
    implementation(project(":feature:player"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    testImplementation(libs.junit)
}
