package com.shiyin.music.data.artist

/**
 * 「隐藏客串歌手」的**关系**（纯逻辑，可 JVM 单测）。
 *
 * 定稿 定稿的交互，要一眼看懂（原话）：
 * > "关闭了隐藏客串歌手，那所有的也跟着关闭；打开了，所有跟着打开，用户就很清楚的知道
 * > 是什么关系。然后就可以关掉特定的一些他不想隐藏的歌手。"
 *
 * 所以内部只有一个**放行名单**（[exceptions]，存的是"点名要留下"的歌手）：
 * ```
 *   某位是否隐藏 = 总开关开 && 这位不在放行名单里
 *   总开关关     ⇒ 全部显示（不论名单里有什么）
 *   总开关开     ⇒ 全部隐藏，除非被点名放行
 *   拨某一位的开关 ⇒ 只动名单里那一个名字
 * ```
 * 设置页只负责把上面三条画成开关，判定一律走这里 —— 免得"界面说的"和"歌手栏做的"分叉。
 */
object FeatureArtistFilter {

    /** 某一位此刻**是否会被隐藏**。 */
    fun hidden(name: String, master: Boolean, exceptions: Set<String>): Boolean =
        master && name !in exceptions

    /** 整批：此刻真正被隐藏的那些人（歌手栏就照它过滤）。总开关关 ⇒ 空集。 */
    fun hiddenSet(all: Collection<String>, master: Boolean, exceptions: Set<String>): Set<String> =
        if (!master) emptySet() else all.toSet() - exceptions

    /**
     * 拨某一位的开关。[hidden] = 用户要隐藏它。
     * @return 新的放行名单
     */
    fun toggleException(exceptions: Set<String>, name: String, hidden: Boolean): Set<String> =
        if (hidden) exceptions - name else exceptions + name
}
