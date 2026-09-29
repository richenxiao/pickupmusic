package com.shiyin.music.data.memory

/**
 * v1.3.12:「拾光记忆」页面的**展示映射** —— 纯 Kotlin,零 Android,可单测。
 *
 * 单独抽出来的理由:本轮 UI 的信息架构(分组 / 中文标签 / 日期格式)都有明确规格,
 * 而且**空分组必须隐藏**、**类型必须全覆盖不重复**——这些是能被测出来的逻辑,
 * 不该埋在一个 200 行的 Composable 里靠肉眼保证。数据库字段(explicit / personal /
 * 时间戳)一律在这里翻译成用户看得懂的中文,页面层不碰原始取值。
 *
 * 分组与用户给的信息架构一致(**你 / 主题**),类型全部映射到**现有** MemoryType,
 * 不新增任何类型、不改数据模型。
 */
object MemoryPresentation {

    /** 一个分组:标题 + 它包含的记忆类型。 */
    data class Section(val title: String, val types: List<String>)

    /** 页面分组顺序 = 显示顺序。 */
    val SECTIONS: List<Section> = listOf(
        Section("你", listOf(MemoryType.PERSONAL, MemoryType.PREFERENCE)),
        Section("主题", listOf(MemoryType.MUSIC, MemoryType.RELATIONSHIP, MemoryType.EPISODIC)),
    )

    /** 全部类型(顺序与页面分组一致)——供「添加一条」的类型选择用。 */
    val ALL_TYPES: List<String> = SECTIONS.flatMap { it.types }

    private val TYPE_LABELS = mapOf(
        MemoryType.PERSONAL to "个人信息",
        MemoryType.PREFERENCE to "偏好",
        MemoryType.MUSIC to "音乐",
        MemoryType.RELATIONSHIP to "关系",
        MemoryType.EPISODIC to "经历",
    )

    /** 类型 → 中文标签。 */
    fun typeLabel(type: String): String = TYPE_LABELS[type] ?: "其它"

    /** 来源 → 用户能懂的说法(**绝不**出现 explicit / inferred 这类库内取值)。 */
    fun sourceLabel(source: String): String = when (source) {
        MemorySource.EXPLICIT -> "你告诉我的"
        MemorySource.MANUAL -> "你自己添加的"
        else -> "拾光自己记下的"
    }

    /**
     * 按分组整理记忆。**空分组直接不返回**——用户定调:没有"关系"就不要显示
     * "关系 · 暂无记忆"。组内按 updatedAt 倒序(最近更新的在前)。
     */
    fun group(memories: List<Memory>): List<Pair<Section, List<Memory>>> =
        SECTIONS.mapNotNull { sec ->
            val rows = memories.filter { it.type in sec.types }.sortedByDescending { it.updatedAt }
            if (rows.isEmpty()) null else sec to rows
        }

    /** 列表页的短日期:`2026/09/29`。 */
    fun formatDate(ms: Long): String =
        java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.CHINA).format(java.util.Date(ms))

    /** 详情页的长日期:`2026年9月29日`。 */
    fun formatDateLong(ms: Long): String =
        java.text.SimpleDateFormat("yyyy年M月d日", java.util.Locale.CHINA).format(java.util.Date(ms))
}
