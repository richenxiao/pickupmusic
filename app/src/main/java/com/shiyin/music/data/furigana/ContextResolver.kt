package com.shiyin.music.data.furigana

/**
 * 轻量上下文消歧器（V1.1 准确率层）。
 *
 * 当第三方词典（JMdict）对某 surface 返回多个合法 reading（CONFLICT）时，先尝试用
 * **可证明、语法上无歧义**的上下文规则消歧；消歧不了才进入 No Reading（→ Occurrence
 * Override 人工修正）。介于 JMdict-CONFLICT 与 No Reading 之间。
 *
 * 【职责边界——硬约束】
 *   - 只实现「语法上可证明正确」的固定模式（助词组合、系动词组合、固定表达）。
 *   - **不训练模型、不无限增加静态词条**：规则须可证明，每条都有语法依据。
 *   - 任何无法高置信判断的语境 → 返回 null（No Reading），绝不猜。
 *   - Occurrence Override 仍在更上层，作为最终人工修正（覆盖本层的判断）。
 *
 * Phase 1 规则：仅「何」（なに / なん）。依据标准日语语法：
 *   - 何 + が/を/の/も → なに（疑问代词接主语/宾语/属格/副助词；何が/何を/何の/何も）
 *   - 何 + だ/です/でしょうか/だろう → なん（系动词前；何だ/何です/何でしょうか/何だろう）
 *   - 其余（何なん、何で、何か…）语境本身歧义 → null（不猜）
 *
 * 其他 CONFLICT 词（二人/明日…）Phase 1 暂无 provable 规则 → null → No Reading →
 * Occurrence Override。后续可在此处增加可证明规则，但须满足上述准入门槛。
 */
object ContextResolver {

    /**
     * @param surface CONFLICT 词（如 何）
     * @param readings JMdict 给的多个合法读法（已归一平假名、去重）
     * @param nextTokSurface 该词后一个 token 的 surface（词末/句末为 null）
     * @return 解析出的读法（须在 readings 中）或 null（消歧不了 → No Reading）
     */
    fun resolve(surface: String, readings: List<String>, nextTokSurface: String?): String? {
        if (readings.size <= 1) return null
        val rs = readings.toSet()
        val n = nextTokSurface ?: return null
        return when (surface) {
            "何" -> resolveNani(n, rs)
            else -> null // Phase 1：其他 CONFLICT 词无可证明规则 → No Reading
        }
    }

    private fun resolveNani(next: String, rs: Set<String>): String? = when (next) {
        // 疑问代词「何」接主语/宾语/属格/副助词 → なに（何が/何を/何の/何も）
        "が", "を", "の", "も" -> if ("なに" in rs) "なに" else null
        // 系动词前 → なん（何だ/何です/何でしょうか/何だろう）
        "だ", "です", "でしょうか", "だろう" -> if ("なん" in rs) "なん" else null
        // 何なん/何で/何か 等语境本身歧义 → 不猜 → No Reading → Occurrence Override
        else -> null
    }
}
