package com.scanner.overlay.settings

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scanner.overlay.BuildConfig
import com.scanner.overlay.accessibility.ScannerAccessibilityService
import com.scanner.overlay.calibration.SewCalibration
import com.scanner.overlay.service.ScannerForegroundService
import com.scanner.overlay.service.SewCalibrationService
import com.scanner.overlay.update.AutoUpdateManager
import com.scanner.overlay.update.UpdateInfo
import com.scanner.overlay.update.UpdateResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val prefs: SharedPreferences,
    private val currentCalibration: SewCalibration
) : AndroidViewModel(app) {

    companion object {
        private const val COUNTDOWN_SECONDS = 5
        private const val PREF_KEY_SERVICE_RUNNING = "service_running"
        private const val PREF_KEY_SCAN_TIMEOUT = "scan_timeout_ms"
        private const val PREF_KEY_SCAN_QUALITY = "scan_quality"
        private const val PREF_KEY_SEW_CALIBRATED = "sew_calibrated"
        private const val PREF_KEY_SEW_TARGET_PACKAGE = "sew_target_package"
        private const val PREF_KEY_SEW_OPEN_MODAL_X = "sew_open_modal_x"
        private const val PREF_KEY_SEW_OPEN_MODAL_Y = "sew_open_modal_y"
        private const val PREF_KEY_SEW_CONFIRM_X = "sew_confirm_x"
        private const val PREF_KEY_SEW_CONFIRM_Y = "sew_confirm_y"
    }

    private val _isFloatingButtonEnabled = MutableStateFlow(false)

    private val _scanTimeoutMs = MutableStateFlow(prefs.getLong(PREF_KEY_SCAN_TIMEOUT, 45_000L))
    val scanTimeoutMs: StateFlow<Long> = _scanTimeoutMs.asStateFlow()

    private val _scanQuality = MutableStateFlow(prefs.getInt(PREF_KEY_SCAN_QUALITY, 1))
    val scanQuality: StateFlow<Int> = _scanQuality.asStateFlow()

    val isFloatingButtonEnabled: StateFlow<Boolean> = _isFloatingButtonEnabled.asStateFlow()

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    private val _sewCalibration = MutableStateFlow(currentCalibration)
    val sewCalibration: StateFlow<SewCalibration> = _sewCalibration.asStateFlow()

    private val _sewTestResult = MutableStateFlow(
        SewTestResult(steps = emptyList(), finished = true)
    )
    val sewTestResult: StateFlow<SewTestResult> = _sewTestResult.asStateFlow()

    private val _awaitingSewCalibration = MutableStateFlow(false)
    val awaitingSewCalibration: StateFlow<Boolean> = _awaitingSewCalibration.asStateFlow()

    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

    val currentVersion: String = BuildConfig.VERSION_NAME

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            PREF_KEY_SEW_CALIBRATED,
            PREF_KEY_SEW_TARGET_PACKAGE,
            PREF_KEY_SEW_OPEN_MODAL_X,
            PREF_KEY_SEW_OPEN_MODAL_Y,
            PREF_KEY_SEW_CONFIRM_X,
            PREF_KEY_SEW_CONFIRM_Y -> refreshSewCalibration()
            SewCalibrationService.PREF_KEY_AWAITING -> {
                _awaitingSewCalibration.value = prefs.getBoolean(
                    SewCalibrationService.PREF_KEY_AWAITING, false
                )
            }
        }
    }

    init {
        refreshServiceState()
        _awaitingSewCalibration.value = prefs.getBoolean(
            SewCalibrationService.PREF_KEY_AWAITING, false
        )
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        loadInstalledApps()
    }

    private fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = app.packageManager
            val intent = Intent(Intent.ACTION_MAIN)
            val activities = pm.queryIntentActivities(intent, 0)
            val apps = activities
                .map { resolveInfo ->
                    AppInfo(
                        packageName = resolveInfo.activityInfo.packageName,
                        label = resolveInfo.loadLabel(pm).toString()
                    )
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
            withContext(Dispatchers.Main) {
                _installedApps.value = apps
            }
        }
    }

    fun setSewTargetPackage(packageName: String) {
        prefs.edit()
            .putString(PREF_KEY_SEW_TARGET_PACKAGE, packageName)
            .putBoolean(PREF_KEY_SEW_CALIBRATED, false)
            .putInt(PREF_KEY_SEW_OPEN_MODAL_X, 0)
            .putInt(PREF_KEY_SEW_OPEN_MODAL_Y, 0)
            .putInt(PREF_KEY_SEW_CONFIRM_X, 0)
            .putInt(PREF_KEY_SEW_CONFIRM_Y, 0)
            .apply()
    }

    override fun onCleared() {
        super.onCleared()
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(app)) {
                openOverlaySettings()
                return
            }
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

    fun updateScanQuality(quality: Int) {
        require(quality in 0..2) { "Quality must be 0, 1, or 2" }
        _scanQuality.value = quality
        prefs.edit().putInt(PREF_KEY_SCAN_QUALITY, quality).apply()
    }

    fun resetSewCalibration() {
        val current = _sewCalibration.value
        prefs.edit()
            .putBoolean(PREF_KEY_SEW_CALIBRATED, false)
            .putInt(PREF_KEY_SEW_OPEN_MODAL_X, 0)
            .putInt(PREF_KEY_SEW_OPEN_MODAL_Y, 0)
            .putInt(PREF_KEY_SEW_CONFIRM_X, 0)
            .putInt(PREF_KEY_SEW_CONFIRM_Y, 0)
            .apply()
        _sewCalibration.value = current.copy(
            openModal = android.graphics.Point(0, 0),
            confirm = android.graphics.Point(0, 0)
        )
        _sewTestResult.value = SewTestResult(steps = emptyList(), finished = true)
    }

    fun refreshSewCalibration() {
        val cal = SewCalibration(
            targetPackage = prefs.getString(PREF_KEY_SEW_TARGET_PACKAGE, "") ?: "",
            openModal = android.graphics.Point(
                prefs.getInt(PREF_KEY_SEW_OPEN_MODAL_X, 0),
                prefs.getInt(PREF_KEY_SEW_OPEN_MODAL_Y, 0)
            ),
            confirm = android.graphics.Point(
                prefs.getInt(PREF_KEY_SEW_CONFIRM_X, 0),
                prefs.getInt(PREF_KEY_SEW_CONFIRM_Y, 0)
            )
        )
        _sewCalibration.value = cal
    }

    fun startSewCalibration() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(app)) {
            openOverlaySettings()
            return
        }
        val intent = Intent(app, SewCalibrationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }
    }

    fun cancelSewCalibration() {
        val intent = Intent(app, SewCalibrationService::class.java).apply {
            action = SewCalibrationService.ACTION_STOP
        }
        app.startService(intent)
    }

    fun runSewCalibrationTest() {
        val calibration = _sewCalibration.value
        if (!calibration.isCalibrated) {
            _sewTestResult.value = SewTestResult(
                steps = listOf(StepStatus("SEW откалиброван", ok = false, message = "Сначала откалибруйте")),
                finished = true
            )
            return
        }
        val service = ScannerAccessibilityService.instance
        if (service == null) {
            _sewTestResult.value = SewTestResult(
                steps = listOf(StepStatus("Сервис доступности", ok = false, message = "Включите специальные возможности")),
                finished = true
            )
            return
        }
        val stepNames = listOf(
            "SEW найден",
            "Кнопка «Ручной ввод» доступна",
            "Поле ввода найдено",
            "Ввод работает",
            "Кнопка «Готово» найдена"
        )
        _sewTestResult.value = SewTestResult(
            steps = stepNames.map { StepStatus(it, ok = false, message = "Ожидание...") },
            inProgress = true,
            finished = false,
            countdownSeconds = COUNTDOWN_SECONDS
        )
        android.widget.Toast.makeText(app, "Откройте SEW", android.widget.Toast.LENGTH_SHORT).show()
        viewModelScope.launch {
            for (i in (COUNTDOWN_SECONDS - 1) downTo 1) {
                kotlinx.coroutines.delay(1000L)
                if (!_sewTestResult.value.inProgress) return@launch
                _sewTestResult.value = _sewTestResult.value.copy(countdownSeconds = i)
            }
            kotlinx.coroutines.delay(1000L)
            if (!_sewTestResult.value.inProgress) return@launch
            _sewTestResult.value = _sewTestResult.value.copy(countdownSeconds = null)
            service.runSewAutoInput(
                barcode = "TEST_CALIBRATION",
                calibration = calibration,
                testMode = true,
                onResult = { ok, message ->
                    val current = _sewTestResult.value
                    val updated = current.steps.mapIndexed { i, s ->
                        if (s.message == "Ожидание..." && !s.ok) {
                            s.copy(ok = ok && i == current.steps.lastIndex, message = if (ok) null else message)
                        } else s
                    }
                    _sewTestResult.value = current.copy(
                        steps = updated,
                        inProgress = false,
                        finished = true
                    )
                    android.widget.Toast.makeText(
                        app,
                        if (ok) "Тест пройден" else "Тест не пройден: $message",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                },
                onStep = { name, ok, message ->
                    val current = _sewTestResult.value
                    val idx = current.steps.indexOfFirst { it.name == name }
                    if (idx >= 0) {
                        val updated = current.steps.toMutableList()
                        updated[idx] = StepStatus(name = name, ok = ok, message = message)
                        _sewTestResult.value = current.copy(steps = updated)
                    }
                    if (!ok && message != null) {
                        android.widget.Toast.makeText(
                            app,
                            "$name: $message",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
        }
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
