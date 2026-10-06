package com.shiyin.music.data

/**
 * v1.5.0 扫描性能（B2①）：决定哪些文件还需要交给 MediaStore 重新索引。
 *
 * 背景（2026-10-07 真机取证）：设置→「重新扫描文件」时 walk 出 4090 个音频文件，
 * 旧实现把它们**全部**丢给 MediaStoreConnection.scanFile 并等回调，其中 2066 个
 * MediaStore 早已收录 —— 12.4s 的等待是纯重复劳动。
 *
 * 语义边界：forceReindex 存在的原因（v2.0）是「新加进来的文件 MediaStore 没索引到」，
 * 那是**新路径**；已被 MediaStore 收录的路径重扫只会让它重新解析一遍标签，不会改变收录结果。
 * 因此在「别的工具改了标签但没动文件」这类边界上，本过滤与旧行为一致地不覆盖
 * （该边界用户已在 B2① 拍板接受，手动兜底入口仍在）。
 */
object ReindexPlanner {

    /** MediaStore 尚未收录的文件（保持 [fsFiles] 的原有遍历顺序）。 */
    fun filesNeedingReindex(fsFiles: List<String>, indexedPaths: Set<String>): List<String> =
        fsFiles.filterNot { it in indexedPaths }
}
