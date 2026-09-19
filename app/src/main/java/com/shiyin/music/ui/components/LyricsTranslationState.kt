package com.shiyin.music.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 需求6「歌词本翻译」的当前档位。 */
enum class LyricsTranslationMode {
    /** 关闭:不显示译文,也不发起任何调用。 */
    Off,

    /** 离线(ML Kit 端侧)。**当前未接线**——等 APK 体积决策后再启用。 */
    Offline,

    /** AI(用户自配大模型)。质量主力。 */
    Ai,
}

/**
 * 需求6「歌词本翻译」的 UI 状态。由 `MainViewModel` 持有（与 `LyricsMorphState` 同层）。
 *
 * 只放"UI 要看的东西"：档位、进度、行号→译文、错误文案、归属曲目。
 * 翻译编排本身在 `data/translate/LyricsTranslationPipeline`（纯逻辑、已单测）。
 *
 * **按曲隔离**：[mediaId] 记录这批译文属于哪首歌。切歌时调用方必须 [reset]，
 * 否则上一首的译文会挂到新歌的行上（振假名 F7 踩过同一个坑）。
 */
class LyricsTranslationState {

    var mode by mutableStateOf(LyricsTranslationMode.Off)
    var running by mutableStateOf(false)

    /** 已译行数 / 请求行数，用于「已译 23/58 行」的实时进度。 */
    var done by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)

    /** 行号（0-based，对应歌词列表下标）→ 译文。缺行号 = 该行无译文。 */
    var byLine by mutableStateOf<Map<Int, String>>(emptyMap())

    /** 非空 = 需要提示用户的错误/降级文案（如"未配置大模型"、"已译 51/58 行"）。 */
    var error by mutableStateOf<String?>(null)

    /** 这批译文归属的曲目 id。null = 无归属。 */
    var mediaId by mutableStateOf<Long?>(null)

    /** 这批译文对应的歌词版本哈希（`LyricsHash.of(raw)`）。换源/改文本后失效。 */
    var lyricsHash by mutableStateOf<String?>(null)

    /** 译文来源（`ai` / `offline`），决定按钮角标。 */
    var source by mutableStateOf("ai")

    /**
     * **有数据**（不论当前是否显示）。
     *
     * 与 [hasContent] 的区别是这套设计的核心：译文**译完即存**（DB），
     * 按钮只控制"显示/隐藏"——与振假名一致（`furiganaOn` 管显示，evidence 管数据）。
     * 所以"再点一下没了"不再意味着数据丢失，再点一次会**立刻**回来，不重新调 API。
     */
    val hasData: Boolean get() = byLine.isNotEmpty()

    /** 有可渲染的译文（档位非关 且 至少译出一行）——渲染层用这个。 */
    val hasContent: Boolean get() = mode != LyricsTranslationMode.Off && byLine.isNotEmpty()

    /** 该行的译文；无则 null（渲染层据此决定是否留白）。 */
    fun translationFor(lineIndex: Int): String? = byLine[lineIndex]

    /** 进行中的进度文案；null = 没有进行中的任务。 */
    val progressLabel: String?
        get() = if (running) "翻译中… $done/$total 行" else null

    /** 整体复位：切歌、删除缓存、或准备发起新一轮时调用。 */
    fun reset() {
        mode = LyricsTranslationMode.Off
        running = false
        done = 0
        total = 0
        byLine = emptyMap()
        error = null
        mediaId = null
        lyricsHash = null
        source = "ai"
    }

    /** 发起新一轮翻译前的清场（保留调用方随后写入的档位与归属）。 */
    fun begin(mode: LyricsTranslationMode, mediaId: Long, lyricsHash: String) {
        this.mode = mode
        this.mediaId = mediaId
        this.lyricsHash = lyricsHash
        running = true
        done = 0
        total = 0
        byLine = emptyMap()
        error = null
    }

    /** 收尾：写入结果。[failure] 非空时保留已拿到的部分译文，只把原因透给 UI。 */
    fun finish(byLine: Map<Int, String>, failure: String?) {
        running = false
        this.byLine = byLine
        error = failure
    }

    /** 从持久化恢复：有数据即**直接显示**（用户："应该和振假名一样，默认保存好的"）。 */
    fun restore(byLine: Map<Int, String>, source: String, mediaId: Long, lyricsHash: String) {
        if (byLine.isEmpty()) return
        this.byLine = byLine
        this.source = source
        this.mediaId = mediaId
        this.lyricsHash = lyricsHash
        this.mode = if (source == "offline") LyricsTranslationMode.Offline else LyricsTranslationMode.Ai
        this.running = false
        this.error = null
    }

    /** 隐藏译文（数据保留）。 */
    fun hide() { mode = LyricsTranslationMode.Off }

    /** 重新显示已保存的译文（无网络请求）。 */
    fun show() {
        if (byLine.isEmpty()) return
        mode = if (source == "offline") LyricsTranslationMode.Offline else LyricsTranslationMode.Ai
    }
}
