package com.shiyin.music.ui

/**
 * v1.3.3 返回恢复:导航/UI 状态快照的独立类型文件。
 * 放顶层(不嵌在 MainViewModel 里)——KSP 对 VM 嵌套 data class 的符号扫描
 * 在 util.kt:264 崩(IllegalStateException,无错误信息),拆顶层文件后扫描路径
 * 独立、更简单,避开该 KSP 解析 bug。
 */

/** 歌手页局部 UI 状态快照(ArtistDetail 的展开/滚动位)——v1.3.3 返回恢复。 */
data class ArtistUiState(
    val showAllAlbums: Boolean = false,
    val songCap: Int = 5,
    val firstVisibleIndex: Int = 0,
    val firstVisibleOffset: Int = 0,
)
