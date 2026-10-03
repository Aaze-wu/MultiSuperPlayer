
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
//
// 允许带预发行后缀（`0.6.1-alpha.1`）：**versionCode 只看数值三段**，
// 后缀原样进入 versionName、tag 和界面。
// 同数值段的 alpha/beta/正式版共用同一个 versionCode 是有意的——它们本来就是
// 「同一个版本」，而 Android 只在**降级**时拒绝安装，同 code 覆盖安装是允许的。
// 反过来说：升到下一个数值段（0.6.2）时 code 必须跟着涨，否则预发行版会变成
// 用户永远装不上的「降级包」。
val appVersionName: String = "0.6.1-alpha.1"

/** 去掉预发行后缀的数值部分（`0.6.1-alpha.1` → `0.6.1`）。 */
val appVersionCore: String = appVersionName.substringBefore('-').trim()

/**
 * 预发行通道（`alpha` / `beta` / `rc`），稳定版是空串。
 *
 * 单独拎出来是因为界面要说人话：「预览版」这三个字不该靠客户端去切 versionName
 * ——切字符串的代码会在某个 `1.0.0-rc.1+build.7` 上悄悄失灵，而这里失败是看得见的。
 */
val appVersionChannel: String = run {
    val suffix = appVersionName.substringAfter('-', "").trim()
    if (suffix.isEmpty()) {
        ""
    } else {
        val channel = suffix.substringBefore('.').trim()
        require(channel.matches(Regex("[A-Za-z]+"))) {
            "预发行后缀必须以字母通道名开头（如 alpha.1 / beta.2 / rc.1），当前是 \"$suffix\""
        }
        channel.lowercase()
    }
}

val appVersionCode: Int = run {
    val parts = appVersionCore.split('.')
    require(parts.size == 3) { "版本号必须是 x.y.z 三段（可带 -预发行后缀），当前是 \"$appVersionName\"" }
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

// 把「本次 release 到底用了哪个签名」打一行在构建日志里。
//
// 起因：`keystore.properties` 里那四行如果**值填对了、行首却还留着 `#`**，`storeFile`
// 这个键就读不到，于是静默回退 debug 签名——构建成功、包装得上、单测全绿，唯一的异常
// 是签名是错的（后果被推迟到用户升级时才爆发：系统以证书不一致为由拒绝安装）。
// 这类故障没有任何其他征兆，所以判定结果必须由构建自己报出来。
//
// 只打印文件名，绝不打印口令。
if (hasReleaseKeystore) {
    logger.lifecycle("[签名] release 使用正式密钥：${keystoreProperties.getProperty("storeFile")}")
} else {
    logger.lifecycle(
        "[签名] 未读到 keystore.properties 的 storeFile（文件缺失，或该行仍是注释）——" +
            "release 将回退 debug 签名，该包不可用于分发。"
    )
}

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
        // 空串 = 正式版。界面据此决定要不要挂「预览版」标记。
        buildConfigField("String", "VERSION_CHANNEL", quoted(appVersionChannel))

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

                // 签名方案必须在这里显式定下来，而且**第一个正式包就是唯一的机会**：
                //
                // - v1（JAR 签名）对我们是死的：它只为 Android 7.0 以下（API < 24）服务，
                //   而 minSdk 是 26。AGP 的默认值本来就是 false，写出来是防止以后有人
                //   以为「没写就是没签」。
                // - v2 是 API 24+ 的验签依据，必须有。
                // - v3 的用途是**密钥更换（key rotation）**：Android 9+ 换签名密钥时，要靠
                //   新包里的 lineage 证明「新密钥由旧密钥授权」，而这个 lineage 只能被
                //   旧包自己携带的 v3 签名背书。也就是说，**如果第一个发布包只签了 v2，
                //   以后就再也换不了密钥**——只能换包名重新发布，用户数据全丢。
                //   打开它不花任何代价，关掉它则是不可逆的。
                // - v4 是配合 adb 增量安装（.idsig）用的，侧载分发用不到。
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
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
    implementation(project(":core:asr"))
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
