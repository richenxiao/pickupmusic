package com.shiyin.music.data

/**
 * 标题解析：标签标题 ↔ 文件名 不一致时的调和。
 *
 * 2026-10-23 用户报「爱的初体验_(Remix)-张震岳-156943-2000.flac 只识别出『爱的初体验』，
 * (Remix) 被吞了」。真机查实：MediaStore TITLE 列就是「爱的初体验」——下载站把版本注记
 * 只写进**文件名**、内嵌标签里只留基础歌名，于是同歌的多个版本在库里塌成同名、无法区分。
 *
 * 规则：文件名里第一个括号注记（`(Remix)`/`(Live)`/`（现场版）`/`【Demo】` 等）若标签标题
 * 没有，且注记前的部分（剥掉 `_`/空格/连字符尾巴）正好是标签标题，就把注记补回标题。
 * 「注记前必须是标题本身」这一条挡住 `歌手 - 歌名(注记)` 这类文件名的误判。
 */
object TitleResolver {

    /** 文件名里的括号/方括号注记（半角与全角都认）。 */
    private val VERSION_NOTE = Regex("""[(（\[【]([^()（）\[\]【】]{1,40})[)）\]】]""")

    /**
     * @param tagTitle MediaStore TITLE（内嵌标签），可能为 null/空白。
     * @param dataPath 文件绝对路径（用于取文件名）。
     */
    fun resolveTitle(tagTitle: String?, dataPath: String): String {
        val fileBase = dataPath.substringAfterLast('/').substringBeforeLast('.')
        val tag = tagTitle?.takeIf { it.isNotBlank() }
            ?: return fileBase.ifBlank { "未知音频" }
        if (fileBase.isBlank()) return tag
        val note = VERSION_NOTE.find(fileBase) ?: return tag
        // 标签已含同样的注记 → 不动
        if (tag.contains(note.value, ignoreCase = true)) return tag
        val head = fileBase.substring(0, note.range.first)
            .trim().trimEnd('_', ' ', '-', '－', '—', '·').trim()
        // 注记前正好就是标题本身（或「分隔符+标题」收尾）→ 注记属于这首歌，补回。
        val matches = head.equals(tag, ignoreCase = true) ||
            head.endsWith(" $tag", ignoreCase = true) ||
            head.endsWith("-$tag", ignoreCase = true) ||
            head.endsWith("_$tag", ignoreCase = true)
        return if (matches) "$tag ${note.value}" else tag
    }
}
