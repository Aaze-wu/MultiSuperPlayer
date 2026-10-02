package com.multisuperplayer.core.common.log

import android.content.Context
import android.content.res.Resources
import android.os.Process
import com.multisuperplayer.core.common.appinfo.AppBuildInfo
import java.io.File

/**
 * 在启动时装配日志设施：把 [MspLog.sink] 接到文件、按构建类型设等级、装崩溃处理器，
 * 再写一段「本次会话的抬头」。
 *
 * 调用点只有一处：`MspApplication.onCreate`，而且必须**早于** `startKoin`——
 * Koin 里绑定的 [LogRepository] 要拿到这里解析出来的目录，
 * 反过来的话第一次导出日志会落到「还没有目录」的分支上。
 *
 * 关于「onCreate 里做这个贵不贵」：这里唯一碰磁盘的是 `getExternalFilesDir`，
 * 它会顺手 `mkdirs` 一次（几十微秒量级，且只在进程首帧发生一次）；真正的目录创建、
 * 文件打开、旧文件清理都发生在 [FileLogSink] 自己的后台线程上。所以这是可接受的，
 * 但**不要再往这里加别的东西**——Application 的冷启动路径上每一毫秒都是用户能感觉到的。
 */
object MspLogInitializer {

    /** 日志目录名。放在外部私有目录下，即 `Android/data/<包名>/files/logs`。 */
    const val LOG_DIR_NAME: String = "logs"

    private const val TAG = "MspLog"

    @Volatile
    private var resolvedDirectory: File? = null

    @Volatile
    private var installedSink: FileLogSink? = null

    /**
     * 装配一次，返回文件落地端（幂等：重复调用返回同一个，不会装两个线程、两个崩溃处理器）。
     *
     * 目录选择的优先级：
     * 1. `getExternalFilesDir("logs")`——**不需要任何存储权限**，用户用文件管理器
     *    也能翻到（`Android/data/com.multisuperplayer.player/files/logs`），
     *    可以直接把文件拷给开发者。本项目清单里没有 `WRITE_EXTERNAL_STORAGE`，
     *    也没有 `MANAGE_EXTERNAL_STORAGE`，写到「下载」目录反而要额外权限或 SAF，
     *    对一个日志文件不值得（导出时再走 SAF）。
     * 2. 外部存储不可用（挂载失败/被拔掉）时退回 `filesDir/logs`。这条路用户看不到，
     *    但总比完全没有日志强。
     */
    fun install(context: Context, buildInfo: AppBuildInfo, release: Boolean): FileLogSink {
        installedSink?.let { return it }
        synchronized(this) {
            installedSink?.let { return it }

            val directory = context.getExternalFilesDir(LOG_DIR_NAME)
                ?: File(context.filesDir, LOG_DIR_NAME)
            resolvedDirectory = directory

            val sink = FileLogSink(directory)
            installedSink = sink

            // 顺序有意义：先接落点再写抬头，否则抬头只会出现在 logcat 里。
            MspLog.sink = sink
            MspLog.minLevel = if (release) MspLogLevel.INFO else MspLogLevel.DEBUG
            installCrashHandler(sink)

            logSessionHeader(context.resources, buildInfo, directory)
            return sink
        }
    }

    /** 已解析出的日志目录；未装配时为 null。 */
    fun currentDirectory(): File? = resolvedDirectory

    /** 已装配的文件落地端；未装配时为 null。 */
    fun currentSink(): FileLogSink? = installedSink

    private fun installCrashHandler(sink: FileLogSink) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(CrashLogHandler(previous, sink::flush))
    }

    /**
     * 抬头。它解决一个很实际的问题：用户发过来的日志可能横跨好几天、好几个版本，
     * 没有抬头就只能靠猜「这段是哪次运行的、装的哪个包」。
     *
     * **这里刻意不放设备信息**，和 [com.multisuperplayer.core.common.device.DeviceInfo.snapshot]
     * 的「不要放在冷启动路径上」保持一致：采集屏幕尺寸要走 WindowManager。
     *
     * 设备信息放在**导出报告**里（[LogRepository.buildReport] 的 `header` 参数，
     * 由关于页传入 `DeviceSnapshot.rows()`）。之所以这样做：Android 11 起系统不允许
     * 普通用户/文件管理器浏览 `Android/data`，所以「用户自己把原始日志文件拷出来」
     * 这条路基本不存在，用户拿到的必然是应用内导出的那份 `msp-log-*.txt`。
     * 反过来，给原始文件加设备行就是为一个不会发生的场景在冷启动上多花一次 IPC。
     */
    private fun logSessionHeader(
        resources: Resources,
        buildInfo: AppBuildInfo,
        directory: File,
    ) {
        MspLog.i(TAG) {
            buildString {
                append("===== 会话开始 =====")
                append("\n进程 PID: ").append(Process.myPid())
                append("\n等级门槛: ").append(MspLog.minLevel.name)
                append("\n日志目录: ").append(directory.absolutePath)
                // 标签/值是本地化的（跟着当前界面语言），而这一行的骨架不是——
                // 后者是排查时按 `设备:` 搜的锚点，不能随语言变。
                buildInfo.rows().forEach { row ->
                    append("\n").append(row.label.resolve(resources))
                        .append(": ").append(row.value.resolve(resources))
                }
                append("\n提交: ").append(buildInfo.commitText().resolve(resources))
            }
        }
    }
}
