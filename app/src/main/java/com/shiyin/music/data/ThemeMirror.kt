package com.shiyin.music.data

import android.content.Context
import android.content.res.Configuration
import android.app.UiModeManager

/**
 * v1.4.0：应用内主题选择的**同步镜像**（SharedPreferences）。
 *
 * 解决的问题（真机）：冷启动时先按"系统日夜"画一帧窗口底色（`values/` 亮 cream、`values-night/` 深），
 * 等 DataStore 异步读回应用内主题后才翻过来 —— 用户看到"闪一下深/浅"，像卡了一下。
 *
 * 纪律：
 *  - **DataStore 仍是唯一权威来源**。镜像只回答"首帧该用哪套色"，不参与任何业务判断；
 *    设置加载完成后若与镜像不一致，以 DataStore 为准并回写镜像（见 MainViewModel 的设置流）。
 *  - 全新安装没有镜像 → [read] 返回 null，调用方用 DataStore 的默认值（`dark = false`）兜底，
 *    两边默认值必须一致，否则首帧又会跳。
 *  - 镜像文件本身**不进云备份**（DataStore 已被排除，镜像不能反过来被云端还原成陈旧值）。
 */
internal object ThemeMirror {

    private const val PREF = "theme_mirror"
    private const val KEY_DARK = "dark"

    /** 读镜像；没有记录（全新安装）返回 null。 */
    fun read(context: Context): Boolean? {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return if (p.contains(KEY_DARK)) p.getBoolean(KEY_DARK, false) else null
    }

    /** 回写镜像（DataStore 的值变化时调用）。 */
    fun write(context: Context, dark: Boolean) {
        runCatching {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_DARK, dark).apply()
        }
    }
}

/**
 * v1.4.0：让**系统资源选择**跟随应用内主题（`values-night/` 的窗口底色、Android 12+ 启动画面底色）。
 *
 * 为什么需要：窗口底色由资源决定（所以跟的是**系统**日夜），而应用内主题是另一套（DataStore）——
 * 两者不一致时，启动画面与页间过渡会透出错误的底色（`values-night/themes.xml` 的注释里记过
 * 这个病根：AnimatedContent 交叉淡化时透出亮窗口底）。`setApplicationNightMode`（API 31，
 * 本项目 minSdk=31，无需新依赖）把应用的夜间模式设成应用内选择，两套就一致了。
 *
 * 只在**值变化**时调用（重复设置会白白触发一次配置变更）。
 */
internal object ThemeMode {

    private var last: Boolean? = null

    fun apply(context: Context, dark: Boolean) {
        if (last == dark) return
        last = dark
        runCatching {
            context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO,
            )
        }
    }
}
