package com.shiyin.music.data.karaoke

/**
 * v1.5.0 块 4：**伴唱的格式闸门**（纯逻辑，可单测）。
 *
 * 可用版只放 **FLAC / MP3**（用户 2026-10-14 定的范围）——其余格式不是"暂时没空做"，
 * 而是**两条解码路径还没验证过**（端到端逐位一致只验了这两种），所以宁可置灰。
 *
 * ⚠️ 置灰**不等于按不了**：按钮仍可点，点了给出 [reasonFor] 的原因 ——
 * 一个"按了没反应"的按钮用户会以为坏了。
 */
object KaraokeFormatGate {

    /** 可用版支持的容器（按扩展名判断，MediaStore 的 MIME 不可靠）。 */
    val SUPPORTED = setOf("flac", "mp3")

    /**
     * @param dataPath 曲目文件路径（用扩展名判格式）
     * @return null = 支持；否则是**给用户看的原因**
     */
    fun reasonFor(dataPath: String): String? {
        val ext = dataPath.substringAfterLast('.', "").lowercase()
        return when {
            ext in SUPPORTED -> null
            ext.isEmpty() -> "认不出这个文件的格式，暂不支持伴唱"
            else -> "暂只支持 FLAC 与 MP3（当前是 ${ext.uppercase()}）"
        }
    }

    fun isSupported(dataPath: String): Boolean = reasonFor(dataPath) == null
}
