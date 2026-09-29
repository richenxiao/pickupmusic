package com.shiyin.music.data.lyrics

/**
 * v1.3.10 需求 A29:歌词源的**运行时配置**。
 *
 * 与写真源 [com.shiyin.music.data.image.ImageSourceConfig] 同模式:设置在 VM 里变更后推到这里,
 * 取歌词链路(LyricsFetcher)零依赖地读——不需要把 SettingsStore 塞进每一层。
 */
object LyricsSourceConfig {

    /** 被用户关掉的内置源名(如 "LRCLIB" / "网易云音乐" / "LrcShare")。 */
    @Volatile
    var disabledBuiltins: Set<String> = emptySet()

    /** 用户自定义源(设置页"歌词源管理"里添加)。 */
    @Volatile
    var customSources: List<CustomLyricsSourceDef> = emptyList()
}
