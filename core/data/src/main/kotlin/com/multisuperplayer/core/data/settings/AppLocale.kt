package com.multisuperplayer.core.data.settings

import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.core.content.edit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import java.util.Locale

/**
 * 应用支持的语言。
 *
 * ## 存储的是 [tag]，不是枚举名
 *
 * 枚举名会随重构改（`CHINESE_TRADITIONAL` 还是 `TRADITIONAL_CHINESE`？），
 * 而存储里躺的是**用户已经选过的值**。存枚举名意味着「重命名一个常量」会让
 * 所有老用户的设置静默失效——回落到默认语言，而且不会有任何报错。存 BCP-47
 * 标签，名字随便改。
 *
 * ## [endonym] 刻意不是一个字符串资源
 *
 * 语言名要在**当前界面语言恰好是用户看不懂的那一种**时仍然可用，所以业界做法是
 * 用自己的语言写自己的名字（endonym）：界面是英文时，「简体中文」这一行依然写
 * 简体中文。翻成 "Chinese (Simplified)" 之后，一个只会中文的用户在英文界面里
 * 就找不到自己的语言了——而这正是他会进这个设置页的唯一原因。
 *
 * [SYSTEM] 是唯一例外：它没有 endonym（「跟随系统」是个相对概念），所以它是唯一
 * 跟着界面语言翻译的一项。
 */
enum class AppLanguage(
    /** BCP-47 语言标签。[SYSTEM] 用空串表示「不覆盖系统语言」。 */
    val tag: String,
    /** 用自己的语言写自己的名字。null = 这一项要跟着界面语言翻译。 */
    val endonym: String?,
) {
    SYSTEM("", null),

    SIMPLIFIED_CHINESE("zh-Hans", "简体中文"),

    TRADITIONAL_CHINESE("zh-Hant", "繁體中文"),

    ENGLISH("en", "English"),
    ;

    companion object {
        /**
         * 默认值：跟随系统。
         *
         * 不做「按语言猜」的默认值。中文兜底已经写在 `values/` 里了，而一个日文
         * 用户第一次打开时会看到中文——他想切到英文只要点两下，但如果我们猜错了，
         * 并且猜错的那一项还被记进了设置，他得先发现哪里不对才能改回来。
         */
        val DEFAULT: AppLanguage = SYSTEM

        /**
         * 从存下来的标签还原。**不认识就回落到默认值**，不抛异常。
         *
         * 这条「宽容」是刻意的：标签可能来自老版本、来自手工改过的配置文件、
         * 或者来自将来被删掉的一种语言。任何一处都不值得让应用起不来。
         */
        fun fromTag(tag: String?): AppLanguage =
            entries.firstOrNull { it.tag == tag?.trim() } ?: DEFAULT

        /** 所有「真的是一种语言」的选项，不含 [SYSTEM]。 */
        val concrete: List<AppLanguage> = entries.filter { it != SYSTEM }

        /**
         * 兜底语言：`values/` 里放的就是它，所以它**没有** `values-xx` 目录。
         *
         * 用户选定简体中文兜底的理由：系统语言不在支持列表时（例如日语、阿拉伯语）
         * 显示中文，而不是英文。这同时让「系统语言就是中文」和「系统语言没人支持」
         * 落到同一份文案上，少一份需要同步的翻译。
         */
        val FALLBACK: AppLanguage = SIMPLIFIED_CHINESE

        /**
         * 语言 → `res` 下的目录名。
         *
         * 写成函数而不是枚举属性：这个映射里 **`SYSTEM` 和 `FALLBACK` 指向同一个
         * 目录**（`SYSTEM` 的当前语言不一定是兜底语言，但「该往哪个目录放文案」
         * 这个问题对它没有意义，返回什么都不影响构建）。真正常用的只有
         * [translated] 里那两项。
         */
        fun resourceDirName(language: AppLanguage): String = when (language) {
            // 这里必须列**枚举项本身**：`FALLBACK` 是一个 val，`when` 不认它，
            // 少列一项编译器就会报 "must be exhaustive"。
            SYSTEM, SIMPLIFIED_CHINESE -> "values"
            TRADITIONAL_CHINESE -> "values-b+zh+Hant"
            ENGLISH -> "values-en"
        }

        /** 真正需要单独一份 `values-*` 目录的语言（不含兜底）。 */
        val translated: List<AppLanguage> = concrete.filter { it != FALLBACK }
    }
}

/**
 * 语言设置的读写。
 *
 * ## 为什么这一个设置**不用** DataStore
 *
 * 语言必须在**第一个 Activity 的 `attachBaseContext` 里**就知道。那次调用发生在
 * `Application.onCreate` 之前，而且必须是同步的。DataStore 的读取天生是异步的
 * （要开文件、要过一遍协程），用 `runBlocking` 去读会在冷启动关键路径上阻塞主线程，
 * 换来可感知的白屏——这跟本项目「`onCreate` 里只做轻活」的约定直接冲突。
 *
 * SharedPreferences 的第一次读取会把整个文件读进内存，但那个文件里只有这一个键；
 * 而且这次调用发生在进程最早的时刻，此时磁盘缓存还没被别的 I/O 冲掉。代价可以忽略，
 * 换来的是「同步可知」这个硬需求。
 *
 * ## API 33 以上的分支不是「优化」，是权威来源
 *
 * Android 13 起系统自己提供了「每个应用的语言」（`LocaleManager`），用户可以在
 * **系统设置**里改，改完由系统重建应用。如果这时我们还拿自己存的值去覆盖 Context，
 * 用户就会发现「系统设置里改了，进应用还是老样子」——所以 33 以上**以系统为准**，
 * 这里只做读写转发。
 */
object AppLocaleStore {

    private const val FILE_NAME = "msp_locale"
    private const val KEY_TAG = "locale.tag"

    /**
     * 33 以上走系统的「每应用语言」。
     *
     * 加 `@ChecksSdkIntAtLeast` 不只是为了消 lint 警告：这个注解是**读代码的人**
     * （和 lint）判断「下面那些 33 专属 API 是安全的」的唯一依据。写成普通的
     * `Build.VERSION.SDK_INT >= 33` 常量，调用点就得自己重写一遍版本判断——
     * 而重写过的判断迟早会和这里不一致。
     */
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    private val usesSystemStore: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * 进程内最近一次读到的语言。
     *
     * ## 33 以上为什么必须自己缓存
     *
     * 33 以上的权威来源是系统的「每应用语言」，而它**没有任何回调**：值一改，系统
     * 直接把 Activity 重建一遍。重建时 `ViewModel` 是**存活**的（同一个进程、同一次
     * 配置变更），所以那个「只发一次」的 Flow 不会再发值——实测症状是
     * 「点了「繁體中文」，整屏都变繁体中文了，单选框却还勾在「跟随系统」上」。
     * 这一页唯一的真相就是那个单选框，它勾错了就是在说谎。
     *
     * 所以把最近一次读到的值留在进程里：写入点（[write]）自己更新它，每一次
     * `attachBaseContext`（也就是每一次重建）再重新读一次覆盖它（[refresh]）。
     * 两条路径合起来才覆盖「本应用改」和「用户在系统设置里改」这两个来源。
     */
    private val cached: MutableStateFlow<AppLanguage> = MutableStateFlow(AppLanguage.DEFAULT)

    /**
     * 重新从系统读一次、更新缓存，并返回读到的语言。
     *
     * **必须在每个 Activity 的 `attachBaseContext` 里调用**（见 [Context.wrapLocale]）：
     * 「用户在系统设置里改每应用语言」这条路径我们收不到任何通知，而它一定会重建
     * Activity——那是我们唯一的观测点。
     */
    fun refresh(context: Context): AppLanguage = read(context).also { cached.value = it }

    /** 当前生效的语言标签。空串 = 跟随系统。 */
    fun readTag(context: Context): String {
        if (usesSystemStore) {
            val manager = context.getSystemService(LocaleManager::class.java) ?: return ""
            val locales = manager.applicationLocales
            // `LocaleList` 空集表示「没设过 / 已恢复跟随系统」，不是「设成了空语言」。
            return if (locales.isEmpty) "" else locales.toLanguageTags()
        }
        return context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TAG, "")
            .orEmpty()
    }

    /** 当前生效的语言。 */
    fun read(context: Context): AppLanguage = AppLanguage.fromTag(readTag(context))

    /**
     * 写入选择。空串 = 恢复跟随系统。
     *
     * 33 以上写入系统后**由系统重建 Activity**，调用方不要再自己 `recreate()`，
     * 否则会重建两次（第二次重建时用户正看着的还是旧配置，观感是闪了两下）。
     * 判断交给 [requiresManualRecreate]，别在调用点重写版本比较。
     *
     * 先更新缓存再写盘：33 以上没有回调能把新值推回来（见 [cached]），而写盘到
     * 系统重建 Activity 之间隔着一整帧，那段时间界面已经在渲染了。
     */
    fun write(context: Context, language: AppLanguage) {
        cached.value = language
        if (usesSystemStore) {
            val manager = context.getSystemService(LocaleManager::class.java) ?: return
            manager.applicationLocales =
                if (language == AppLanguage.SYSTEM) {
                    LocaleList.getEmptyLocaleList()
                } else {
                    LocaleList.forLanguageTags(language.tag)
                }
            return
        }
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_TAG, language.tag) }
    }

    /**
     * 写完语言之后，调用方要不要自己重建 Activity。
     *
     * 33 以上不需要：系统会重建。低于 33 则必须手动重建，否则 Resources 已经是新的、
     * 而屏幕上那一屏还是旧语言，用户会以为「点了没反应」。
     */
    fun requiresManualRecreate(): Boolean = !usesSystemStore

    /**
     * 观察语言变化。
     *
     * 33 以上发的是进程内缓存（[cached]）而不是「读一次就完」的值。原来说明里写的
     * 「任何改动都会重建应用，所以读一次就够」漏掉了一件事：**重建时 `ViewModel`
     * 不会重建**，收集者还是老的，读一次之后就没有第二次了。缓存让 [write] 和
     * [refresh] 都能把新值推进来。
     *
     * 两种实现都做成 Flow，是为了让 `SettingsViewModel` 只写一遍。把
     * `if (SDK_INT >= 33)` 散进业务代码里，正是「给某个入口加了判断、
     * 另一个入口忘了加」这类 bug 的温床。
     */
    fun observe(context: Context): Flow<AppLanguage> {
        if (usesSystemStore) {
            // 先同步读一次：收集者可能在 [refresh] 之前就订阅了，那时缓存里只有初值。
            refresh(context)
            return cached.asStateFlow()
        }

        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        return callbackFlow {
            trySend(AppLanguage.fromTag(prefs.getString(KEY_TAG, "")))
            val listener =
                SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == KEY_TAG) {
                        trySend(AppLanguage.fromTag(prefs.getString(KEY_TAG, "")))
                    }
                }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
    }
}

/**
 * 把已选语言套到 Context 上。
 *
 * ## 必须传 `base`，不能传 `this`
 *
 * 这个方法在 `Application.attachBaseContext` 里被调用，而那个时刻
 * `Application.mBase` **还没被赋值**（`attach` 里就是先调 `attachBaseContext`
 * 再设 base 的）。所以在 `Application.attachBaseContext` 里读
 * `this.getSharedPreferences(...)` 会直接 NPE；必须用参数传进来的那个
 * 已经是完整 ContextImpl 的 `base`。这也是它写成扩展函数而不是
 * `Application` 成员方法的原因——调用点长这样：
 *
 * ```
 * override fun attachBaseContext(base: Context) {
 *     super.attachBaseContext(base.wrapLocale())
 * }
 * ```
 *
 * ## 33 以上不改配置，但要刷缓存
 *
 * 系统已经在 `attachBaseContext` 之后、`onCreate` 之前把 `Resources.configuration`
 * 改好了（那是「每应用语言」这个功能的全部意义）。我们再套一次反而会把系统设的
 * 值覆盖掉，于是系统设置里的改动看起来「不生效」。
 *
 * 不套的后果很具体：`Resources.getString` 会用系统语言，而 Compose 界面已经按
 * 另一种语言排版了——症状是「文案是英文的，但日期还是中文格式」这种半翻译状态。
 *
 * ## 它是一个 `attachBaseContext`，所以这里也是唯一能刷新缓存的地方
 *
 * [AppLocaleStore.refresh] 放在这个函数的**第一行、版本判断之前**：33 以上不改
 * 配置，但「用户在系统设置里改了语言」是收不到通知的，而它一定会重建 Activity。
 * 反过来说，漏了这一个调用点的症状就是「文案全变了，单选框停在上一次的选择上」。
 */
fun Context.wrapLocale(): Context {
    val language = AppLocaleStore.refresh(this)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return this
    if (language == AppLanguage.SYSTEM) return this

    // `zh-Hant` 要变成「语言 zh + 文字 Hant」两个字段，`Locale.forLanguageTag` 会做这件事。
    // 自己 split('-') 拼 `Locale("zh", "Hant")` 在这里恰好也对，但换一个三段的标签
    // （`zh-Hant-TW`）就错了，而错法是「语言对了、地区丢了」，看起来一切正常。
    val locale = Locale.forLanguageTag(language.tag)
    Locale.setDefault(locale)
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(locale)
    configuration.setLocales(LocaleList(locale))
    return createConfigurationContext(configuration)
}
