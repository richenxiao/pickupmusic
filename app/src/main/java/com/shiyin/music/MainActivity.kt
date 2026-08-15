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

        vm.launchTrashIntent = { pi ->
            trashLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        }

        if (hasAudioPermission()) vm.onPermissionGranted()

        // Handle VIEW intent that launched the activity
        handleViewIntent(intent)

        setContent {
            ShiyinTheme(dark = vm.darkTheme) {
                val view = LocalView.current
                LaunchedEffect(vm.darkTheme) {
                    val controller = WindowCompat.getInsetsController(window, view)
                    controller.isAppearanceLightStatusBars = !vm.darkTheme
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
