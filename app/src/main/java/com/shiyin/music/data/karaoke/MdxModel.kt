package com.shiyin.music.data.karaoke

/**
 * P0 Spike 阶段 B：三个内置模型的参数（全部来自 UVR 官方 model_data.json 核实值，
 * 见 docs/internal/karaoke-p0-phase-a.md；assetName 是打进 APK 的文件名）。
 */
enum class MdxModel(
    /** 稳定标识：缓存路径与 model.json 都用它（不要用 enum 名，改名会失效）。 */
    val id: String,
    val displayName: String,
    val assetName: String,
    /**
     * 是否**打进当前 APK**（A57 拍板：只有 9482 = 29.7MB 进包；另两个各 ~67MB 超线，
     * 走"未内置"提示，等下载入口单独做）。
     *
     * ⚠️ 2026-10-15 用户报「默认好像是 hq3」——**功能上一直是 9482**，误导来自 demo 标签，
     * 所以这里把"能不能用"变成**数据**而不是文案，UI 直接照 [bundled] 置灰未内置项。
     */
    val bundled: Boolean,
    val nFft: Int,
    val hop: Int,
    val dimF: Int,
    val dimT: Int,
    val predictsInstrumental: Boolean,
    val compensate: Float,
) {
    // 9482 = vocals 模型：伴奏 = 原曲 − 人声×compensate
    M9482(
        "mdx_9482", "9482（默认·人声模型）", "UVR_MDXNET_9482.onnx", bundled = true,
        nFft = 6144, hop = 1024, dimF = 2048, dimT = 256,
        predictsInstrumental = false, compensate = 1.035f,
    ),
    // Inst_HQ_3 = instrumental 模型：伴奏 = 模型输出（原样）
    INST_HQ_3(
        "inst_hq_3", "Inst_HQ_3（伴奏模型）", "UVR-MDX-NET-Inst_HQ_3.onnx", bundled = false,
        nFft = 6144, hop = 1024, dimF = 3072, dimT = 256,
        predictsInstrumental = true, compensate = 1.022f,
    ),
    // Voc_FT = vocals 模型（n_fft 与另两个不同）
    VOC_FT(
        "voc_ft", "Voc_FT（人声模型）", "UVR-MDX-NET-Voc_FT.onnx", bundled = false,
        nFft = 7680, hop = 1024, dimF = 3072, dimT = 256,
        predictsInstrumental = false, compensate = 1.021f,
    );

    /** 一个 chunk 覆盖的采样数（hop × (dim_t − 1)）；dim_t=256 时 = 261120 ≈ 5.92s */
    val chunkSamples: Int get() = hop * (dimT - 1)

    companion object {
        /** 默认分轨模型 —— **只能是内置的那个**（A57）；设置页选了未内置项会退回这里。 */
        fun default(): MdxModel = M9482

        fun byId(id: String?): MdxModel = values().firstOrNull { it.id == id } ?: default()
    }
}

/**
 * P0 Spike 阶段 B：推理后端选项（规格 §12 要求对比 CPU/加速后端）。
 * NNAPI 会走手机的 DSP/NPU；FP16 可能带来可闻劣化（规格 R2），故单独列出待听感确认。
 */
enum class OrtBackend(val label: String) {
    CPU("CPU（多线程）"),
    XNNPACK("XNNPACK"),
    NNAPI("NNAPI"),
    NNAPI_FP16("NNAPI FP16"),
}
