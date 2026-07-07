package com.scanner.overlay.settings

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scanner.overlay.BuildConfig
import com.scanner.overlay.accessibility.ScannerAccessibilityService
import com.scanner.overlay.scanner.ArticleBarcodeDatabase
import com.scanner.overlay.scanner.BarcodeDatabase
import com.scanner.overlay.scanner.ProductImporter
import com.scanner.overlay.scanner.ProductItem
import com.scanner.overlay.scanner.ScanHistoryEntry
import com.scanner.overlay.calibration.SewCalibration
import com.scanner.overlay.service.ScannerForegroundService
import com.scanner.overlay.service.SewCalibrationService
import com.scanner.overlay.sync.GithubDatabaseManager
import com.scanner.overlay.update.AutoUpdateManager
import com.scanner.overlay.update.UpdateInfo
import com.scanner.overlay.update.UpdateResult
import com.scanner.overlay.util.reusableBottomToast
import com.scanner.overlay.util.toastAtBottom
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import java.net.URL

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val info: UpdateInfo) : UpdateUiState
    data object Downloading : UpdateUiState
    data class Error(val message: String) : UpdateUiState
}

sealed interface DbManagerState {
    data object Idle : DbManagerState
    data object Importing : DbManagerState
    data object Checking : DbManagerState
    data class Applied(val count: Int, val source: String) : DbManagerState
    data class Error(val message: String) : DbManagerState
}

data class ChangeLogEntry(
    val count: Int,
    val source: String,
    val timestamp: Long
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val app: Application,
    private val prefs: SharedPreferences
) : AndroidViewModel(app) {

    companion object {
        private const val COUNTDOWN_SECONDS = 5
        private const val PREF_KEY_SERVICE_RUNNING = "service_running"
        private const val PREF_KEY_SCAN_TIMEOUT = "scan_timeout_ms"
        private const val PREF_KEY_SCAN_QUALITY = "scan_quality"
        private const val PREF_KEY_SEW_TARGET_PACKAGE = "sew_target_package"
        private const val PREF_KEY_SEW_OPEN_MODAL_X = "sew_open_modal_x"
        private const val PREF_KEY_SEW_OPEN_MODAL_Y = "sew_open_modal_y"
        private const val PREF_KEY_SEW_CONFIRM_X = "sew_confirm_x"
        private const val PREF_KEY_SEW_CONFIRM_Y = "sew_confirm_y"
        const val PREF_KEY_TAP_TO_FOCUS_ENABLED = "tap_to_focus_enabled"
        const val PREF_KEY_FOCUS_HINT_SHOWN = "focus_hint_shown"
        const val PREF_KEY_AUTO_FOCUS_ENABLED = "auto_focus_enabled"
        private const val PREF_KEY_TTS_ENABLED = "tts_enabled"
        private const val PREF_KEY_PANEL_EDGE = "panel_edge"
        private const val PREF_KEY_BTN_SIZE = "panel_btn_size"
        private const val PREF_KEY_OPACITY = "panel_opacity"
        private const val PREF_KEY_PRODUCT_DB_VERSION = "product_db_version"
        private const val PREF_KEY_CHANGE_LOG = "change_log"
    }

    private val _isFloatingButtonEnabled = MutableStateFlow(false)

    private val _tapToFocusEnabled = MutableStateFlow(
        prefs.getBoolean(PREF_KEY_TAP_TO_FOCUS_ENABLED, true)
    )
    val tapToFocusEnabled: StateFlow<Boolean> = _tapToFocusEnabled.asStateFlow()

    private val _autoFocusEnabled = MutableStateFlow(
        prefs.getBoolean(PREF_KEY_AUTO_FOCUS_ENABLED, false)
    )
    val autoFocusEnabled: StateFlow<Boolean> = _autoFocusEnabled.asStateFlow()

    private val _isTtsEnabled = MutableStateFlow(
        prefs.getBoolean(PREF_KEY_TTS_ENABLED, false)
    )
    val isTtsEnabled: StateFlow<Boolean> = _isTtsEnabled.asStateFlow()

    private val _panelEdge = MutableStateFlow(
        prefs.getString(PREF_KEY_PANEL_EDGE, "right") ?: "right"
    )
    val panelEdge: StateFlow<String> = _panelEdge.asStateFlow()

    private val _btnSize = MutableStateFlow(prefs.getInt(PREF_KEY_BTN_SIZE, 56))
    val btnSize: StateFlow<Int> = _btnSize.asStateFlow()

    private val _panelOpacity = MutableStateFlow(prefs.getFloat(PREF_KEY_OPACITY, 1f))
    val panelOpacity: StateFlow<Float> = _panelOpacity.asStateFlow()

    private val _scanTimeoutMs = MutableStateFlow(prefs.getLong(PREF_KEY_SCAN_TIMEOUT, 45_000L))
    val scanTimeoutMs: StateFlow<Long> = _scanTimeoutMs.asStateFlow()

    private val _scanQuality = MutableStateFlow(prefs.getInt(PREF_KEY_SCAN_QUALITY, 1))
    val scanQuality: StateFlow<Int> = _scanQuality.asStateFlow()

    val isFloatingButtonEnabled: StateFlow<Boolean> = _isFloatingButtonEnabled.asStateFlow()

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    private val _dbManagerState = MutableStateFlow<DbManagerState>(DbManagerState.Idle)
    val dbManagerState: StateFlow<DbManagerState> = _dbManagerState.asStateFlow()

    private val _changeLog = MutableStateFlow<List<ChangeLogEntry>>(emptyList())
    val changeLog: StateFlow<List<ChangeLogEntry>> = _changeLog.asStateFlow()

    private val _sewCalibration = MutableStateFlow(readSewCalibration())
    val sewCalibration: StateFlow<SewCalibration> = _sewCalibration.asStateFlow()

    private val _sewTestResult = MutableStateFlow(
        SewTestResult(steps = emptyList(), finished = true)
    )
    val sewTestResult: StateFlow<SewTestResult> = _sewTestResult.asStateFlow()

    private val _awaitingSewCalibration = MutableStateFlow(false)
    val awaitingSewCalibration: StateFlow<Boolean> = _awaitingSewCalibration.asStateFlow()

    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

    private val _scanHistory = MutableStateFlow(ScanHistoryEntry.load(prefs))
    val scanHistory: StateFlow<List<ScanHistoryEntry>> = _scanHistory.asStateFlow()

    private var productDbVersion: Int
        get() = prefs.getInt(PREF_KEY_PRODUCT_DB_VERSION, 0)
        set(value) = prefs.edit().putInt(PREF_KEY_PRODUCT_DB_VERSION, value).apply()

    private fun loadChangeLog(): List<ChangeLogEntry> {
        val json = prefs.getString(PREF_KEY_CHANGE_LOG, null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                ChangeLogEntry(
                    count = obj.getInt("count"),
                    source = obj.getString("source"),
                    timestamp = obj.getLong("timestamp")
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun saveChangeLog(entry: ChangeLogEntry) {
        val entries = loadChangeLog().toMutableList()
        entries.add(0, entry)
        val trimmed = entries.take(20)
        val arr = org.json.JSONArray()
        trimmed.forEach { e ->
            arr.put(org.json.JSONObject().apply {
                put("count", e.count)
                put("source", e.source)
                put("timestamp", e.timestamp)
            })
        }
        prefs.edit().putString(PREF_KEY_CHANGE_LOG, arr.toString()).apply()
        _changeLog.value = trimmed
    }

    val currentVersion: String = BuildConfig.VERSION_NAME

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
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
        val storedAwaiting = prefs.getBoolean(
            SewCalibrationService.PREF_KEY_AWAITING, false
        )
        val actualAwaiting = storedAwaiting && SewCalibrationService.isRunning
        if (storedAwaiting != actualAwaiting) {
            prefs.edit().putBoolean(SewCalibrationService.PREF_KEY_AWAITING, actualAwaiting).apply()
        }
        _awaitingSewCalibration.value = actualAwaiting
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        loadInstalledApps()
        _changeLog.value = loadChangeLog()
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

    fun setTapToFocusEnabled(enabled: Boolean) {
        if (_tapToFocusEnabled.value == enabled) return
        _tapToFocusEnabled.value = enabled
        prefs.edit().putBoolean(PREF_KEY_TAP_TO_FOCUS_ENABLED, enabled).apply()
    }

    fun setAutoFocusEnabled(enabled: Boolean) {
        if (_autoFocusEnabled.value == enabled) return
        _autoFocusEnabled.value = enabled
        prefs.edit().putBoolean(PREF_KEY_AUTO_FOCUS_ENABLED, enabled).apply()
    }

    fun setTtsEnabled(enabled: Boolean) {
        if (_isTtsEnabled.value == enabled) return
        _isTtsEnabled.value = enabled
        prefs.edit().putBoolean(PREF_KEY_TTS_ENABLED, enabled).apply()
    }

    fun setPanelEdge(edge: String) {
        if (_panelEdge.value == edge) return
        _panelEdge.value = edge
        prefs.edit().putString(PREF_KEY_PANEL_EDGE, edge).apply()
        ScannerForegroundService.setEdge(edge)
    }

    fun setBtnSize(size: Int) {
        if (_btnSize.value == size) return
        _btnSize.value = size
        prefs.edit().putInt(PREF_KEY_BTN_SIZE, size).apply()
        ScannerForegroundService.setBtnSize(size)
    }

    fun setPanelOpacity(value: Float) {
        val clamped = value.coerceIn(0.15f, 1f)
        if (_panelOpacity.value == clamped) return
        _panelOpacity.value = clamped
        prefs.edit().putFloat(PREF_KEY_OPACITY, clamped).apply()
        ScannerForegroundService.setPanelOpacity(clamped)
    }

    fun refreshScanHistory() {
        _scanHistory.value = ScanHistoryEntry.load(prefs)
    }

    fun clearScanHistory() {
        ScanHistoryEntry.clear(prefs)
        _scanHistory.value = emptyList()
    }

    fun wasFocusHintShown(): Boolean =
        prefs.getBoolean(PREF_KEY_FOCUS_HINT_SHOWN, false)

    fun markFocusHintShown() {
        prefs.edit().putBoolean(PREF_KEY_FOCUS_HINT_SHOWN, true).apply()
    }

    fun resetSewCalibration() {
        val current = _sewCalibration.value
        prefs.edit()
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

    private fun readSewCalibration(): SewCalibration {
        return SewCalibration(
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
    }

    fun refreshSewCalibration() {
        _sewCalibration.value = readSewCalibration()
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
            finished = false
        )
        val countdownToast = reusableBottomToast(app)
        viewModelScope.launch {
            for (i in COUNTDOWN_SECONDS downTo 1) {
                countdownToast.setText("Старт через $i сек")
                countdownToast.cancel()
                countdownToast.show()
                kotlinx.coroutines.delay(1000L)
                if (!_sewTestResult.value.inProgress) return@launch
            }
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
                    app.toastAtBottom(
                        if (ok) "Тест пройден" else "Тест не пройден: $message",
                        Toast.LENGTH_LONG
                    )
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
                        app.toastAtBottom("$name: $message")
                    }
                }
            )
        }
    }

    fun openCameraSettings() {
        app.startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:${app.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
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

    fun importProductFile(context: Context, uri: android.net.Uri, fileName: String) {
        if (_dbManagerState.value !is DbManagerState.Idle) return
        _dbManagerState.value = DbManagerState.Importing
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val report = ProductImporter.import(context, uri, fileName)
                if (report.count == 0) {
                    val msg = if (report.errors.isNotEmpty()) report.errors.joinToString("\n") else "Нет новых товаров"
                    withContext(Dispatchers.Main) { _dbManagerState.value = DbManagerState.Error(msg) }
                    return@launch
                }
                val total = ArticleBarcodeDatabase.getAllItems().size
                val csv = ArticleBarcodeDatabase.exportToCsv()
                val ok = GithubDatabaseManager.publishFile("barcode-products.csv", csv, "sync: import + publish")
                if (!ok) {
                    withContext(Dispatchers.Main) { _dbManagerState.value = DbManagerState.Error("Импорт выполнен, но не удалось опубликовать на GitHub") }
                    return@launch
                }
                val newVersion = (productDbVersion + 1).coerceAtLeast(1)
                val versionJson = org.json.JSONObject().apply {
                    put("versionCode", newVersion)
                    put("productsHash", csv.length.toString())
                    put("timestamp", System.currentTimeMillis())
                }.toString(2)
                GithubDatabaseManager.publishFile("db_version.json", versionJson, "sync: update db_version.json")
                productDbVersion = newVersion
                saveChangeLog(ChangeLogEntry(report.count, "импорт", System.currentTimeMillis()))
                withContext(Dispatchers.Main) {
                    _dbManagerState.value = DbManagerState.Applied(report.count, "импорт")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _dbManagerState.value = DbManagerState.Error(e.message ?: "Ошибка импорта")
                }
            }
        }
    }

    fun checkForProductUpdates() {
        if (_dbManagerState.value !is DbManagerState.Idle) return
        _dbManagerState.value = DbManagerState.Checking
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val remoteVersion = GithubDatabaseManager.getDbVersion()
                if (remoteVersion == null) {
                    withContext(Dispatchers.Main) {
                        _dbManagerState.value = DbManagerState.Error("База ещё не опубликована. Выполните импорт, чтобы инициализировать.")
                    }
                    return@launch
                }
                if (remoteVersion.versionCode <= productDbVersion) {
                    withContext(Dispatchers.Main) {
                        _dbManagerState.value = DbManagerState.Idle
                    }
                    return@launch
                }
                val csv = GithubDatabaseManager.downloadFile("barcode-products.csv")
                if (csv == null) {
                    withContext(Dispatchers.Main) {
                        _dbManagerState.value = DbManagerState.Error("Не удалось скачать базу товаров")
                    }
                    return@launch
                }
                val newItems = ArticleBarcodeDatabase.importFromRemote(csv)
                ArticleBarcodeDatabase.mergeExtra(newItems)
                ArticleBarcodeDatabase.persistExtra(app)
                productDbVersion = remoteVersion.versionCode
                if (newItems.isNotEmpty()) {
                    saveChangeLog(ChangeLogEntry(newItems.size, "синхронизация", System.currentTimeMillis()))
                }
                withContext(Dispatchers.Main) {
                    if (newItems.isEmpty()) {
                        _dbManagerState.value = DbManagerState.Idle
                    } else {
                        _dbManagerState.value = DbManagerState.Applied(newItems.size, "синхронизация")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _dbManagerState.value = DbManagerState.Error(e.message ?: "Ошибка проверки")
                }
            }
        }
    }



    fun resetDbManagerState() {
        _dbManagerState.value = DbManagerState.Idle
    }
}
