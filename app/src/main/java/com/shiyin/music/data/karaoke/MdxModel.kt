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
    /** 发行包里的确切字节数（来自 model_data.json，用于下载进度与占用显示）。 */
    val sizeBytes: Long,
    /** 官方发布的 sha256 —— **下载后必须校验**，否则宁可不装。 */
    val sha256: String,
    /**
     * 下载地址，**国内优先、国外兜底**。逐个试，谁先成功用谁。
     *
     * 2026-10-17 本机（国内网络）实测（每个只取 1KB 验连通，不整包下载）：
     *  · HuggingFace 官方 `huggingface.co` —— **连接超时，不可用**；
     *  · GitHub releases 直连 —— 跟随重定向后 206，约 2.1s，可用；
     *  · `ghproxy.net` 前缀 —— 206，约 **1.1s，最快**；
     *  · `hf-mirror.com` —— 206 但约 12.3s，明显慢。
     * ⇒ 顺序定为 ghproxy（GitHub 源）/ hf-mirror（HF 源），官方地址放最后兜底。
     * ⚠️ 该实测是在**当时那台机器的网络**下做的；若当时挂着代理，GitHub 直连那一项
     * 不能代表国内裸连。所以下载器是**多源顺序重试 + sha256 校验**，不依赖某一个源。
     */
    val urlDomestic: List<String>,
    /** 国外/官方源（兜底）。 */
    val urlOfficial: List<String>,
) {
    // 9482 = vocals 模型：伴奏 = 原曲 − 人声×compensate
    // ⚠️ 名字里**不再带「（默认·人声模型）」这类后缀** —— 铭牌已经写了"人声/伴奏"、"默认"，
    //    再写一遍就是牛皮癣（用户 2026-10-17 明确要求去掉）。
    M9482(
        "mdx_9482", "9482", "UVR_MDXNET_9482.onnx", bundled = true,
        nFft = 6144, hop = 1024, dimF = 2048, dimT = 256,
        predictsInstrumental = false, compensate = 1.035f,
        sizeBytes = 29_704_436L,
        sha256 = "f4f365207c56deb115bceedff3ad8fe98a751c745f9e370cecec6226b8b47184",
        urlDomestic = listOf(
            "https://ghproxy.net/https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/UVR_MDXNET_9482.onnx",
        ),
        urlOfficial = listOf(
            "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/UVR_MDXNET_9482.onnx",
        ),
    ),
    // Inst_HQ_3 = instrumental 模型：伴奏 = 模型输出（原样）
    INST_HQ_3(
        "inst_hq_3", "Inst_HQ_3", "UVR-MDX-NET-Inst_HQ_3.onnx", bundled = false,
        nFft = 6144, hop = 1024, dimF = 3072, dimT = 256,
        predictsInstrumental = true, compensate = 1.022f,
        sizeBytes = 66_759_214L,
        sha256 = "317554b07fe1ea5279a77f2b1520a41ea4b93432560c4ffd08792c30fddf9adc",
        urlDomestic = listOf(
            "https://hf-mirror.com/Tha456/uvr5-models/resolve/main/UVR-MDX-NET-Inst_HQ_3.onnx",
            "https://ghproxy.net/https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/UVR-MDX-NET-Inst_HQ_3.onnx",
        ),
        urlOfficial = listOf(
            "https://huggingface.co/Tha456/uvr5-models/resolve/main/UVR-MDX-NET-Inst_HQ_3.onnx",
            "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/UVR-MDX-NET-Inst_HQ_3.onnx",
        ),
    ),
    // Voc_FT = vocals 模型（n_fft 与另两个不同）
    VOC_FT(
        "voc_ft", "Voc_FT", "UVR-MDX-NET-Voc_FT.onnx", bundled = false,
        nFft = 7680, hop = 1024, dimF = 3072, dimT = 256,
        predictsInstrumental = false, compensate = 1.021f,
        sizeBytes = 66_762_490L,
        sha256 = "534b2070fcc7df514b13ef660dc8cbb328679c2374d04354a5c42bb14ecce111",
        urlDomestic = listOf(
            "https://ghproxy.net/https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/UVR-MDX-NET-Voc_FT.onnx",
        ),
        urlOfficial = listOf(
            "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/UVR-MDX-NET-Voc_FT.onnx",
        ),
    );

    /** 一个 chunk 覆盖的采样数（hop × (dim_t − 1)）；dim_t=256 时 = 261120 ≈ 5.92s */
    val chunkSamples: Int get() = hop * (dimT - 1)

    /** 输出的是哪一轨（给设置页做铭牌）。 */
    val stemLabel: String get() = if (predictsInstrumental) "伴奏" else "人声"

    /** 按**下载渠道**给出该试的顺序（见 [DownloadChannel]）。 */
    fun urlsFor(channel: DownloadChannel): List<String> = when (channel) {
        DownloadChannel.AUTO -> urlDomestic + urlOfficial
        DownloadChannel.DOMESTIC -> urlDomestic
        DownloadChannel.OFFICIAL -> urlOfficial
    }

    /**
     * **模型能表示的最高频率**（Hz）= `(dim_f − 1) × 采样率 / n_fft`。
     *
     * 这条不是花边：`伴奏 = 原曲 − 人声估计`，超过这个上限的频段人声估计恒为 0
     * ⇒ **那段原曲会被原封不动保留在伴奏里**（齿音/镲片的顶端就是这么漏出来的）。
     * 三个模型差别很大，选模型时必须能看到。
     */
    val maxHz: Int get() = ((dimF - 1).toLong() * 44100L / nFft).toInt()

    /**
     * **相对耗时铭牌**（以 9482 = 1.00× 为基准）。
     *
     * 口径：单块卷积量 ≈ `n_fft × dim_f`（再乘 dim_t 与本块帧数，三者相同可约掉）。
     * 是从参数**算出来**的，不是拍脑袋标的 —— 换模型参数时它会自动跟着变。
     */
    val relativeCost: Double get() = (nFft.toDouble() * dimF) / (6144.0 * 2048.0)

    companion object {
        /** 默认分轨模型 —— **只能是内置的那个**（A57）；设置页选了未内置项会退回这里。 */
        fun default(): MdxModel = M9482

        fun byId(id: String?): MdxModel = values().firstOrNull { it.id == id } ?: default()

        /** 人机都可读的大小：`29.7 MB`。 */
        fun humanSize(bytes: Long): String =
            if (bytes >= 1024L * 1024) "%.1f MB".format(bytes / 1048576.0)
            else "%.0f KB".format(bytes / 1024.0)
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
