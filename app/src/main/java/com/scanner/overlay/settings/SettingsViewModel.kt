package com.scanner.overlay.settings

import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scanner.overlay.BuildConfig
import com.scanner.overlay.service.ScannerForegroundService
import com.scanner.overlay.update.AutoUpdateManager
import com.scanner.overlay.update.UpdateInfo
import com.scanner.overlay.update.UpdateResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val info: UpdateInfo) : UpdateUiState
    data object Downloading : UpdateUiState
    data class Error(val message: String) : UpdateUiState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val app: Application,
    private val prefs: SharedPreferences
) : AndroidViewModel(app) {

    companion object {
        private const val PREF_KEY_SERVICE_RUNNING = "service_running"
        private const val PREF_KEY_SCAN_TIMEOUT = "scan_timeout_ms"
    }

    private val _isFloatingButtonEnabled = MutableStateFlow(false)

    private val _scanTimeoutMs = MutableStateFlow(prefs.getLong(PREF_KEY_SCAN_TIMEOUT, 45_000L))
    val scanTimeoutMs: StateFlow<Long> = _scanTimeoutMs.asStateFlow()
    val isFloatingButtonEnabled: StateFlow<Boolean> = _isFloatingButtonEnabled.asStateFlow()

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    val currentVersion: String = BuildConfig.VERSION_NAME

    init {
        refreshServiceState()
    }

    fun refreshServiceState() {
        val isRunning = ScannerForegroundService.isRunning
        if (_isFloatingButtonEnabled.value != isRunning) {
            _isFloatingButtonEnabled.value = isRunning
            prefs.edit().putBoolean(PREF_KEY_SERVICE_RUNNING, isRunning).apply()
        }
    }

    fun toggleService() {
        val intent = Intent(app, ScannerForegroundService::class.java)
        if (_isFloatingButtonEnabled.value) {
            intent.action = ScannerForegroundService.ACTION_STOP
            app.stopService(intent)
            prefs.edit().putBoolean(PREF_KEY_SERVICE_RUNNING, false).apply()
            _isFloatingButtonEnabled.value = false
        } else {
            intent.action = ScannerForegroundService.ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
            prefs.edit().putBoolean(PREF_KEY_SERVICE_RUNNING, true).apply()
            _isFloatingButtonEnabled.value = true
        }
    }

    fun updateScanTimeout(ms: Long) {
        _scanTimeoutMs.value = ms
        prefs.edit().putLong(PREF_KEY_SCAN_TIMEOUT, ms).apply()
    }

    fun openAccessibilitySettings() {
        app.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    fun openOverlaySettings() {
        app.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = android.net.Uri.parse("package:${app.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            app.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(
            "${app.packageName}/com.scanner.overlay.accessibility.ScannerAccessibilityService"
        )
    }

    fun checkForUpdate() {
        if (_updateState.value is UpdateUiState.Checking) return
        _updateState.value = UpdateUiState.Checking
        viewModelScope.launch {
            when (val result = AutoUpdateManager.checkForUpdate()) {
                is UpdateResult.UpToDate -> _updateState.value = UpdateUiState.UpToDate
                is UpdateResult.Available -> _updateState.value = UpdateUiState.Available(result.info)
                is UpdateResult.Error -> _updateState.value = UpdateUiState.Error(result.message)
            }
        }
    }

    fun downloadUpdate(info: UpdateInfo) {
        if (_updateState.value is UpdateUiState.Downloading) return
        _updateState.value = UpdateUiState.Downloading
        viewModelScope.launch {
            val result = AutoUpdateManager.downloadAndInstall(getApplication(), info)
            if (result.isFailure) {
                _updateState.value = UpdateUiState.Error(
                    result.exceptionOrNull()?.message ?: "Ошибка скачивания"
                )
            }
        }
    }

    fun resetUpdateState() {
        _updateState.value = UpdateUiState.Idle
    }
}
