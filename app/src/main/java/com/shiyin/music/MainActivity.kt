package com.shiyin.music

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.shiyin.music.ui.AppRoot
import com.shiyin.music.ui.theme.ShiyinTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val trashLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        vm.onTrashResult(result.resultCode == RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // v1.4.0：**首帧就用对主题**（方案①）+ 让窗口/启动画面底色跟随应用内主题。
        // 为什么在 setContent 之前：应用内主题存在 DataStore（异步），首帧拿不到 → 会先按系统
        // 日夜画一帧窗口底（values/values-night），设置回来才翻 → 冷启动闪一下。
        // 镜像（SharedPreferences，同步可读）回答"首帧该用哪套"；DataStore 仍是唯一权威，
        // 读回后若不一致会以它为准并回写镜像（见 MainViewModel 的设置流）。
        // 全新安装没有镜像 → 用与 DataStore 相同的默认值（浅色），并在这一次就设好系统夜间模式。
        val mirroredDark = com.shiyin.music.data.ThemeMirror.read(this) ?: false
        vm.applyInitialDark(mirroredDark)
        com.shiyin.music.data.ThemeMode.apply(this, mirroredDark)

        vm.launchTrashIntent = { pi ->
            trashLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        }

        if (hasAudioPermission()) vm.onPermissionGranted()

        // Handle VIEW intent that launched the activity
        handleViewIntent(intent)

        setContent {
            ShiyinTheme(dark = vm.darkTheme) {
                val view = LocalView.current
                // v1.2.0 #6: 歌手页写真铺到状态栏区,顶部有黑色渐变遮罩——用白色图标清晰。
                // 其余页面按主题(浅色主题=深色图标)。运行时覆盖 theme 的 windowLightStatusBar。
                LaunchedEffect(vm.darkTheme, vm.artistKey) {
                    val controller = WindowCompat.getInsetsController(window, view)
                    controller.isAppearanceLightStatusBars = if (vm.artistKey != null) false else !vm.darkTheme
                    controller.isAppearanceLightNavigationBars = !vm.darkTheme
                }
                AppRoot(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Picks up a grant made from the system App-Info page.
        if (!vm.hasMediaPermission && hasAudioPermission()) vm.onPermissionGranted()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(intent: android.content.Intent?) {
        if (intent?.action != android.content.Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        if (!hasAudioPermission()) return
        // Find the track by URI and play it
        vm.playUri(uri)
    }

    private fun hasAudioPermission(): Boolean {
        val audioPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(this, audioPerm) == PackageManager.PERMISSION_GRANTED
    }
}
