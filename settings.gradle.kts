pluginManagement {
    repositories {
        // 国内镜像优先（阿里云），官方源兜底。Gradle 会按顺序尝试：
        // 前一个仓库里找不到模块时会继续往后找，所以“镜像缺新版 AGP”不会导致失败。
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
    // 这里**不需要** jitpack.io：唯一需要外部仓库的依赖是 FFmpeg 软件解码扩展
    // （`io.github.anilbeesetti:nextlib-media3ext`），而它发布在 Maven Central 上。
    // 仓库列表越短，构建越不容易因为某个第三方仓库变慢/失效而失败——
    // 所以别为了「以后可能用得上」提前加。
}

rootProject.name = "MultiSuperPlayer"

// ---------------------------------------------------------------------------
// 应用层
// ---------------------------------------------------------------------------
include(":app")

// ---------------------------------------------------------------------------
// 核心层（core:*）：不含 UI 业务，可被任意 feature 复用
// ---------------------------------------------------------------------------
include(":core:common")   // 工具、日志、Result、调度器、格式化
include(":core:model")    // 领域模型（媒体条目、字幕、轨道、播放状态）
include(":core:data")     // 数据来源（MediaStore 扫描、SAF、设置持久化）
include(":core:subtitle") // 字幕/歌词解析引擎（纯逻辑、可单测）
include(":core:translate") // 字幕翻译（厂商预设、批量/缓存/术语表、导出，纯逻辑 + 无依赖 HTTP）
include(":core:asr")       // 语音识别（sherpa-onnx 引擎封装、模型下载与管理、切分与成句）
include(":core:llm")       // 设备上推理（LiteRT-LM 引擎封装、模型下载与管理）——本地翻译用它
include(":core:ui")       // 设计系统与主题（动态取色、预设、自定义）
include(":core:player")   // Media3 播放内核封装与播放服务

// ---------------------------------------------------------------------------
// 业务层（feature:*）：每个模块一个用户可感知的功能域
// ---------------------------------------------------------------------------
include(":feature:library") // 媒体库浏览
include(":feature:player")  // 播放页（音/视频 + 歌词字幕）
include(":feature:settings") // 设置（主题、字幕、翻译、网络）
