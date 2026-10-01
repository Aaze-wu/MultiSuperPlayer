package com.multisuperplayer.core.subtitle.di

import com.multisuperplayer.core.subtitle.SubtitleParserRegistry
import org.koin.dsl.module

/**
 * 字幕解析层的依赖注入。
 *
 * 刻意把 [SubtitleParserRegistry] 做成单例而不是每个文件新建一个：
 * 解析器本身无状态、构造很便宜，但 `defaultParsers()` 里那一串正则对象
 * 是每个解析器实例各自持有的——批量导入整个音乐库（几千个 .lrc）时，
 * 反复 new 会白白重复编译正则。
 *
 * 注意这里**不注册** `SubtitleFormatDetector`：它是 `object`，
 * 全局本来就只有一个实例，塞进容器只会让人以为它可被替换。
 */
val subtitleModule = module {
    single { SubtitleParserRegistry() }
}
