package com.scanner.overlay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.scanner.overlay.settings.SettingsScreen
import com.scanner.overlay.util.toastAtBottom
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.CAMERA] == false) {
            // Nothing else in the app reports this: without CAMERA the scanner silently
            // shows a black preview and the user has no idea why.
            android.util.Log.w("MainActivity", "CAMERA permission denied, scanning will not work")
            toastAtBottom("Нет доступа к камере — сканирование работать не будет. Разрешите камеру в настройках приложения")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNeededPermissions()
        warnAboutOverlayPermission()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen()
                }
            }
        }
    }

    /**
     * The floating panel is the app's main entry point and needs "draw over other apps",
     * which is denied by default and can only be granted from the system settings.
     * The hint is shown once per install: the permission is also highlighted inside
     * SettingsScreen, so repeating it on every launch would only be noise.
     */
    private fun warnAboutOverlayPermission() {
        if (Settings.canDrawOverlays(this)) return
        val prefs = getSharedPreferences("scanner_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean(overlayHintShownKey, false)) return
        prefs.edit().putBoolean(overlayHintShownKey, true).apply()
        toastAtBottom("Для работы панели нужен доступ «поверх других приложений» — включите его в настройках")
    }

    private companion object {
        const val overlayHintShownKey = "main_overlay_hint_shown"
    }

    private fun requestNeededPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needsRequest = permissions.any { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (needsRequest) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }
}
