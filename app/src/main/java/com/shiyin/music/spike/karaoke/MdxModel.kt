package com.shiyin.music.spike.karaoke

/**
 * P0 Spike 阶段 B：三个内置模型的参数（全部来自 UVR 官方 model_data.json 核实值，
 * 见 docs/internal/karaoke-p0-phase-a.md；assetName 是打进 APK 的文件名）。
 */
enum class MdxModel(
    val displayName: String,
    val assetName: String,
    val nFft: Int,
    val hop: Int,
    val dimF: Int,
    val dimT: Int,
    val predictsInstrumental: Boolean,
    val compensate: Float,
) {
    // 9482 = vocals 模型：伴奏 = 原曲 − 人声×compensate
    M9482(
        "9482（轻量·人声模型）", "UVR_MDXNET_9482.onnx",
        nFft = 6144, hop = 1024, dimF = 2048, dimT = 256,
        predictsInstrumental = false, compensate = 1.035f,
    ),
    // Inst_HQ_3 = instrumental 模型：伴奏 = 模型输出（原样）
    INST_HQ_3(
        "Inst_HQ_3（默认推荐·伴奏模型）", "UVR-MDX-NET-Inst_HQ_3.onnx",
        nFft = 6144, hop = 1024, dimF = 3072, dimT = 256,
        predictsInstrumental = true, compensate = 1.022f,
    ),
    // Voc_FT = vocals 模型（n_fft 与另两个不同）
    VOC_FT(
        "Voc_FT（人声模型）", "UVR-MDX-NET-Voc_FT.onnx",
        nFft = 7680, hop = 1024, dimF = 3072, dimT = 256,
        predictsInstrumental = false, compensate = 1.021f,
    );

    /** 一个 chunk 覆盖的采样数（hop × (dim_t − 1)）；dim_t=256 时 = 261120 ≈ 5.92s */
    val chunkSamples: Int get() = hop * (dimT - 1)
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
