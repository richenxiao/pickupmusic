package com.shiyin.music.data.db

/**
 * v1.5.0 A60：备份内容的**分类**（拍板的两级结构）。
 *
 * 设计要点（定稿）：
 * - **每项显示大小，用户自己决定承担哪些体积**——"大小因人而异看待"；
 * - 重做成本高的数据必须有选择权：翻译烧 token（要钱）、歌词/假名重抓很麻烦（要时间）；
 * - 相关项归一个父类（如 拾光 Agent → 聊天记录 / 记忆）。
 */
enum class BackupGroup(val title: String) {
    LIBRARY("曲库整理"),
    LYRICS("歌词与注音"),
    AGENT("拾光 Agent"),
    IMAGES("封面与写真"),
    SETTINGS("设置与凭据"),
}

enum class BackupCategory(val id: String, val group: BackupGroup, val label: String) {
    /** 用户手工整理的一切（固定项/曲序/迁移/修正/歌单/回收站/别名/忽略文件夹）。 */
    EDITS("edits", BackupGroup.LIBRARY, "整理与修正"),

    /** 播放次数与播放记录、Token 用量——纯统计，可选。 */
    STATS("stats", BackupGroup.LIBRARY, "播放统计"),

    /** 用户导入/确认过的歌词（不可能靠重扫找回）。 */
    LYRICS("lyrics", BackupGroup.LYRICS, "歌词"),

    /** AI 译文——重建要再烧一次 token。 */
    TRANSLATION("translation", BackupGroup.LYRICS, "翻译"),

    /** 振假名读法（用户校对 + 已抓到的外部读法证据）——重建要再抓一遍。 */
    READING("reading", BackupGroup.LYRICS, "振假名注音"),

    /** 拾光聊天记录（用户点名）。 */
    CHAT("chat", BackupGroup.AGENT, "聊天记录"),

    /** 拾光长期记忆（"她认识你什么"）。 */
    MEMORY("memory", BackupGroup.AGENT, "长期记忆"),

    /** 用户手动导入的封面/写真**图片本体**（自动获取的只存链接，见 [SOURCES]）。 */
    IMAGES("images", BackupGroup.IMAGES, "手动导入的图片"),

    /** 自动获取的封面/写真的**来源链接**与取色（恢复后按链接重取，不必重新找图）。 */
    SOURCES("sources", BackupGroup.IMAGES, "图片来源链接"),

    /** 全部设置项（含均衡器、曲库固定项、自定义歌词源…）。 */
    SETTINGS("settings", BackupGroup.SETTINGS, "应用设置"),

    /** API Key / WebDAV 凭据。⚠️ 含密钥，导出时界面必须提醒。 */
    SECRETS("secrets", BackupGroup.SETTINGS, "API Key 与凭据"),
    ;

    companion object {
        /** 默认全选。 */
        val ALL: Set<String> = entries.map { it.id }.toSet()

        /**
         * 敏感分类：导出包**含明文密钥**，界面要提醒"勿放公开位置"。
         */
        val SENSITIVE: Set<String> = setOf(SECRETS.id)

        fun byId(id: String): BackupCategory? = entries.firstOrNull { it.id == id }
    }
}
