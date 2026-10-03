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
    private const val KEY_MODE = "mode"

    /** 档位取值：跟随系统 / 强制浅 / 强制深。 */
    const val MODE_SYSTEM = "system"
    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"

    /** 读镜像；没有记录（全新安装）返回 null。
     *  v1.4.0: 改存**档位**（原先是布尔）。旧版本写下的 "dark" 键仍能读回并映射成档位，
     *  保证升级首帧与用户原选择一致。 */
    fun read(context: Context): String? {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        p.getString(KEY_MODE, null)?.let { return it }
        if (p.contains("dark")) return if (p.getBoolean("dark", false)) MODE_DARK else MODE_LIGHT
        return null
    }

    /** 回写镜像（DataStore 的值变化时调用）。 */
    fun write(context: Context, mode: String) {
        runCatching {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode).apply()
        }
    }
}

/**
 * v1.4.0：让**系统资源选择**跟随应用内主题（`values-night/` 的窗口底色、Android 12+ 启动画面底色）。
 *
 * 为什么需要：窗口底色由资源决定（所以跟的是**系统**日夜），而应用内主题是另一套（DataStore）——
 * 两者不一致时，启动画面与页间过渡会透出错误的底色。`setApplicationNightMode`（API 31，
 * 本项目 minSdk=31，无需新依赖）把应用的夜间模式设成应用内选择，两套就一致了。
 *
 * v1.4.0「系统主题」档 → 传 `UI_MODE_NIGHT_AUTO`：由系统日夜决定资源，应用不再强制。
 *
 * 只在**档位变化**时调用（重复设置会白白触发一次配置变更）。
 */
internal object ThemeMode {

    private var last: String? = null

    fun apply(context: Context, mode: String) {
        if (last == mode) return
        last = mode
        val night = when (mode) {
            ThemeMirror.MODE_SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
            ThemeMirror.MODE_DARK -> Configuration.UI_MODE_NIGHT_YES
            else -> Configuration.UI_MODE_NIGHT_NO
        }
        runCatching {
            context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(night)
        }
    }
}
