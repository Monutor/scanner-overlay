package com.scanner.overlay.overlay

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager

import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.activity.compose.BackHandler
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.runtime.DisposableEffect
import com.scanner.overlay.R
import com.scanner.overlay.BuildConfig
import com.scanner.overlay.accessibility.ScannerAccessibilityService
import com.scanner.overlay.scanner.ArticleBarcodeDatabase
import com.scanner.overlay.scanner.BarcodeAnalyzer
import com.scanner.overlay.scanner.BarcodeDatabase
import com.scanner.overlay.scanner.ScanHistoryEntry
import com.scanner.overlay.scanner.ScannerResult
import com.scanner.overlay.calibration.SewCalibration
import com.scanner.overlay.util.toastAtBottom
import androidx.core.app.NotificationCompat
import com.scanner.overlay.ScannerApp

@AndroidEntryPoint
class OverlayActivity : ComponentActivity() {

    // Nullable: some devices have no vibrator at all, and setupVibrator() must survive that.
    private var vibrator: Vibrator? = null
    private lateinit var prefs: android.content.SharedPreferences

    private val cameraPermissionGranted = mutableStateOf(false)
    private val cameraPermissionBlocked = mutableStateOf(false)

    // Настройки храним в State, а не в val из onCreate: Activity запускается в singleTask,
    // поэтому после возврата из системных настроек onCreate не вызывается, и значения,
    // прочитанные один раз, оставались бы прежними. refreshOverlaySettings() в onResume
    // перечитывает prefs и перезапускает нужные LaunchedEffect/pointerInput по новым ключам.
    private val tapToFocusEnabledState = mutableStateOf(true)
    private val autoFocusEnabledState = mutableStateOf(false)
    private val sewCalibratedState = mutableStateOf(false)
    private val autoImportSewState = mutableStateOf(false)

    private var overlayViewModel: OverlayViewModel? = null

    private fun refreshOverlaySettings() {
        tapToFocusEnabledState.value = prefs.getBoolean("tap_to_focus_enabled", true)
        autoFocusEnabledState.value = prefs.getBoolean("auto_focus_enabled", false)
        sewCalibratedState.value = buildSewCalibration().isCalibrated
        autoImportSewState.value = prefs.getBoolean("auto_import_sew", false)
    }

    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun buildSewCalibration(): SewCalibration {
        return SewCalibration(
            targetPackage = prefs.getString("sew_target_package", "") ?: "",
            openModal = android.graphics.Point(
                prefs.getInt("sew_open_modal_x", 0),
                prefs.getInt("sew_open_modal_y", 0)
            ),
            confirm = android.graphics.Point(
                prefs.getInt("sew_confirm_x", 0),
                prefs.getInt("sew_confirm_y", 0)
            )
        )
    }

    private fun triggerSewAutoInput(barcode: String) {
        // Прогресс-оверлей не показываем: Activity финиширует сразу, чтобы окно SEW
        // вышло наверх для жестов accessibility-сервиса; результат — уведомлением.
        val cal = buildSewCalibration()
        // Под DEBUG: в release-сборке строка с реальным артикулом со склада попадала бы
        // в logcat, доступный баг-репортёрам и процессам с READ_LOGS.
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "OverlayActivity",
                "triggerSewAutoInput: barcode=$barcode pkg=${cal.targetPackage} isCalibrated=${cal.isCalibrated} " +
                    "openModal=(${cal.openModal.x},${cal.openModal.y}) " +
                    "confirm=(${cal.confirm.x},${cal.confirm.y})"
            )
        }
        val service = ScannerAccessibilityService.instance
        if (service == null) {
            android.util.Log.w("OverlayActivity", "Accessibility service not running, falling back")
            toastAtBottom("Сервис доступности не запущен")
            if (!isFinishing) finish()
            return
        }
        service.runSewAutoInput(
            barcode = barcode,
            calibration = cal,
            onResult = { ok, message -> onSewInputResult(ok, message) }
        )
        if (!isFinishing) finish()
    }

    private fun onSewInputResult(ok: Boolean, message: String) {
        // Результат приходит через секунды, когда Activity уже финишировала:
        // уведомление и вибрация идут через контекст приложения, а не мёртвую Activity.
        val appContext = applicationContext
        mainHandler.post {
            val title = if (ok) "Штрих введён" else "Ошибка ввода в SEW"
            val text = if (ok) "Готово" else message.take(200)
            notifySewResult(appContext, ok, title, text)
            vibrateResult(appContext, ok)
        }
    }

    private fun notifySewResult(
        context: android.content.Context,
        success: Boolean,
        title: String,
        text: String
    ) {
        try {
            val builder = NotificationCompat.Builder(context, ScannerApp.SEW_RESULT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_scan)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
            context.getSystemService(android.app.NotificationManager::class.java)
                .notify(ScannerApp.SEW_RESULT_NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            android.util.Log.e("OverlayActivity", "notifySewResult failed", e)
        }
    }

    private fun vibrateResult(context: android.content.Context, ok: Boolean) {
        try {
            val vib: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ms = if (ok) 100L else 400L
                vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (e: Exception) {
            android.util.Log.e("OverlayActivity", "vibrateResult failed", e)
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            cameraPermissionBlocked.value = false
            cameraPermissionGranted.value = true
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            // «Больше не спрашивать»: системный диалог закрыт навсегда — только через настройки
            cameraPermissionBlocked.value = true
        } else {
            toastAtBottom(getString(R.string.camera_unavailable), Toast.LENGTH_LONG)
            finish()
        }
        // Диалог камеры закрылся — только теперь можно спросить про уведомления.
        checkNotificationsPermission()
    }

    private val notificationsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* результат не критичен: без гранта уведомление просто не покажется */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.addFlags(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // NB: без FLAG_NOT_FOCUSABLE — иначе окно не получает key-focus,
        // Back уходит окну под нами (SEW) и BackHandler не срабатывает.

        prefs = getSharedPreferences("scanner_prefs", MODE_PRIVATE)
        // Дисковый I/O (assets + файлы) — в фон, чтобы не тормозить старт на Main-потоке.
        // Читатели BarcodeDatabase потокобезопасны и вернут пусто до конца загрузки.
        lifecycleScope.launch(Dispatchers.IO) {
            BarcodeDatabase.init(applicationContext)
        }
        textToSpeech = TextToSpeech(this) { status ->
            ttsReady = (status == TextToSpeech.SUCCESS)
            if (ttsReady) {
                val langResult = textToSpeech?.setLanguage(java.util.Locale.forLanguageTag("ru-RU")) ?: -1
                android.util.Log.d("OverlayActivity", "TTS ready, setLanguage=$langResult")
            } else {
                android.util.Log.w("OverlayActivity", "TTS init failed: status=$status")
            }
        }
        setupVibrator()
        checkCameraPermission()

        if (prefs.getBoolean("tap_to_focus_enabled", true) &&
            !prefs.getBoolean("focus_hint_shown", false)
        ) {
            toastAtBottom("Тап по камеру для фокуса")
            prefs.edit().putBoolean("focus_hint_shown", true).apply()
        }

        refreshOverlaySettings()

        setContent {
            val viewModel = hiltViewModel<OverlayViewModel>()
            overlayViewModel = viewModel
            MaterialTheme {
                // Surface по умолчанию красит непрозрачный colorScheme.background, чем
                // полностью отменял Theme.ScannerOverlay.Transparent: сквозь оверлей было
                // не видно ничего, и расчётный полупрозрачный скрим 0xE6000000 ниже
                // становился бессмысленным (он и так рассчитан на 90 % затемнения).
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
                    OverlayContent(
                        viewModel = viewModel,
                        onClose = { finish() },
                        onBarcodeScanned = { barcode, productName -> onBarcodeScanned(barcode, productName) },
                        onInjectToSew = { barcode ->
                            triggerSewAutoInput(barcode)
                        },
                        onCopyToClipboard = { barcode ->
                            copyToClipboard(barcode)
                        },
                        onCopyToClipboardForSew = { barcode ->
                            copyToClipboard(barcode, finishAfter = false)
                        },
                        onRetry = {
                            viewModel.resetToScanning()
                        },
                        tapToFocusEnabled = tapToFocusEnabledState,
                        autoFocusEnabled = autoFocusEnabledState,
                        sewCalibrated = sewCalibratedState,
                        autoImportSew = autoImportSewState,
                        hasCameraPermission = cameraPermissionGranted,
                        permissionBlocked = cameraPermissionBlocked,
                        onOpenSettings = { openAppSettings() }
                    )
                }
            }
        }
    }

    private fun checkCameraPermission() {
        if (hasCameraPermission()) {
            cameraPermissionGranted.value = true
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    // Результат SEW-ввода показываем уведомлением — на API 33+ без гранта оно молча дропается.
    // Оверлей могут открывать, ни разу не заходя в MainActivity, поэтому просим и здесь.
    // Зовём из колбэка cameraPermissionLauncher: два launch() подряд в одном onCreate означали,
    // что второй системный диалог показывается поверх первого, и на API 33+ запрос молча
    // игнорируется — уведомление о результате SEW-ввода так и не появлялось.
    private fun checkNotificationsPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        // Возврат из настроек: разрешение могли выдать там — подхватываем без перезапуска
        if (hasCameraPermission()) {
            cameraPermissionBlocked.value = false
            cameraPermissionGranted.value = true
        }
        // ...и настройки сканера тоже могли поменяться: Activity singleTask, onCreate
        // при возврате не вызывается, поэтому перечитываем их здесь.
        if (::prefs.isInitialized) {
            refreshOverlaySettings()
        }
    }

    // singleTask переиспользует живой инстанс: onCreate не вызывается, и пользователь
    // видел бы результат предыдущего скана вместо камеры. Возвращаем скан в исходное состояние.
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        overlayViewModel?.resetToScanning()
    }

    private fun openAppSettings() {
        try {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", packageName, null)
            )
            startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("OverlayActivity", "openAppSettings failed", e)
        }
    }

    fun onBarcodeScanned(barcode: String, productName: String? = null) {
        try {
            vibrate()
            playBeep()
            speakShelfName(barcode)
            ScanHistoryEntry.add(prefs, barcode, productName)
        } catch (e: Exception) {
            android.util.Log.e("OverlayActivity", "onBarcodeScanned crash", e)
        }
    }

    private fun speakShelfName(barcode: String) {
        if (!prefs.getBoolean("tts_enabled", false)) return
        if (!ttsReady) return
        val item = BarcodeDatabase.getByBarcode(barcode) ?: return
        try {
            textToSpeech?.speak(item.name, TextToSpeech.QUEUE_FLUSH, null, "shelf")
        } catch (e: Exception) {
            android.util.Log.e("OverlayActivity", "TTS speak error", e)
        }
    }

    private fun copyToClipboard(barcode: String, finishAfter: Boolean = true) {
        try {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("barcode", barcode))
            toastAtBottom("Скопировано: $barcode")
        } catch (e: Exception) {
            android.util.Log.e("OverlayActivity", "copyToClipboard error", e)
        }
        if (finishAfter && !isFinishing) finish()
    }

    private fun setupVibrator() {
        // Called straight from onCreate: a device without a vibrator returns null from
        // getSystemService, and the unchecked cast would kill the Activity before the
        // camera ever starts. Feedback is optional, so failures must not be fatal.
        vibrator = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            android.util.Log.w("OverlayActivity", "setupVibrator: no vibrator available", e)
            null
        }
    }

    private fun playBeep() {
        var afd: android.content.res.AssetFileDescriptor? = null
        try {
            afd = resources.openRawResourceFd(R.raw.scan_beep) ?: return
            val mp = MediaPlayer()
            // AFD держим открытым до onPrepared/onError. use{} закрывал дескриптор сразу
            // после prepareAsync(), а MediaPlayer читает из него в своём потоке: на части
            // прошивок первый скан проигрывал системный бип вместо scan_beep (fallback
            // playSystemBeep срабатывал по onError).
            fun release() {
                try { mp.release() } catch (_: Exception) {}
                try { afd?.close() } catch (_: Exception) {}
            }
            mp.setOnErrorListener { _, _, _ ->
                release()
                playSystemBeep()
                true
            }
            mp.setOnCompletionListener { release() }
            mp.setOnPreparedListener { player ->
                try {
                    player.start()
                } catch (_: Exception) {
                    release()
                    playSystemBeep()
                }
            }
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            mp.prepareAsync()
        } catch (_: Exception) {
            try { afd?.close() } catch (_: Exception) {}
            playSystemBeep()
        }
    }

    private fun playSystemBeep() {
        try {
            val ringtone = android.media.RingtoneManager.getRingtone(
                this,
                android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            )
            ringtone?.play()
        } catch (e: Exception) {
            android.util.Log.e("OverlayActivity", "playSystemBeep error", e)
        }
    }

    private fun vibrate() {
        val vib = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    override fun onDestroy() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverlayContent(
    viewModel: OverlayViewModel,
    onClose: () -> Unit,
    onBarcodeScanned: (String, String?) -> Unit,
    onInjectToSew: (String) -> Unit,
    onCopyToClipboard: (String) -> Unit,
    onCopyToClipboardForSew: (String) -> Unit,
    onRetry: () -> Unit = {},
    tapToFocusEnabled: State<Boolean> = remember { mutableStateOf(true) },
    autoFocusEnabled: State<Boolean> = remember { mutableStateOf(false) },
    sewCalibrated: State<Boolean> = remember { mutableStateOf(false) },
    autoImportSew: State<Boolean> = remember { mutableStateOf(false) },
    hasCameraPermission: State<Boolean> = remember { mutableStateOf(true) },
    permissionBlocked: State<Boolean> = remember { mutableStateOf(false) },
    onOpenSettings: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val isTimedOut by viewModel.isScanTimedOut.collectAsState()
    val isCameraError by viewModel.isCameraError.collectAsState()
    val cameraInitAttempt by viewModel.cameraInitAttempt.collectAsState()
    var isCameraReady by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var detectedBarcode by remember { mutableStateOf(false) }
    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusSuccess by remember { mutableStateOf<Boolean?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(state, autoFocusEnabled.value, cameraControl, previewView) {
        if (!autoFocusEnabled.value) return@LaunchedEffect
        if (state !is OverlayViewModel.OverlayState.Scanning) return@LaunchedEffect
        val control = cameraControl ?: return@LaunchedEffect
        val view = previewView ?: return@LaunchedEffect
        val factory = view.meteringPointFactory
        while (isActive) {
            val center = factory.createPoint(view.width / 2f, view.height / 2f)
            val action = FocusMeteringAction.Builder(
                center,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
            // Fire-and-forget: the result is unused, and future.get() must never
            // block the Main thread (ANR if the camera stalls).
            control.startFocusAndMetering(action)
            delay(3000)
        }
    }

    BackHandler {
        onClose()
    }

    LaunchedEffect(state) {
        if (state is OverlayViewModel.OverlayState.Scanning) {
            if (detectedBarcode) {
                android.util.Log.d("ScanFlow", "LaunchedEffect: state=Scanning, reset detectedBarcode")
                detectedBarcode = false
            }
        }
        if (state is OverlayViewModel.OverlayState.Success) {
            val s = state as OverlayViewModel.OverlayState.Success
            if (BuildConfig.DEBUG) android.util.Log.d("ScanFlow", "LaunchedEffect: state=Success, barcode=${s.barcode}")
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Dark background around camera
        Box(Modifier.fillMaxSize().background(Color(0xE6000000)))

        // Centered Column with camera + scanning text/buttons
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Camera preview 300x300 centered with overlays
            Box(
                modifier = Modifier
                    .size(300.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(16.dp))
                    .pointerInput(state, tapToFocusEnabled.value) {
                        detectTapGestures { offset ->
                            if (!tapToFocusEnabled.value) return@detectTapGestures
                            if (state !is OverlayViewModel.OverlayState.Scanning) return@detectTapGestures
                            val control = cameraControl ?: return@detectTapGestures
                            val view = previewView ?: return@detectTapGestures
                            val factory = view.meteringPointFactory
                            val point = factory.createPoint(offset.x, offset.y)
                            val action = FocusMeteringAction.Builder(
                                point,
                                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                            ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                            focusSuccess = null
                            focusPoint = offset
                            val future = control.startFocusAndMetering(action)
                            coroutineScope.launch {
                                // Blocking get() goes to IO with a timeout:
                                // never stall Main on a hung camera.
                                val success = withContext(Dispatchers.IO) {
                                    runCatching { future.get(4, TimeUnit.SECONDS) }
                                        .map { it.isFocusSuccessful }
                                        .getOrNull()
                                }
                                focusSuccess = success
                            }
                        }
                    }
            ) {
                    // Биндим камеру только с разрешением: иначе провайдер успевает
                    // отработать до гранта → SecurityException → ложный экран ошибки.
                    // Грант из лаунчера/onResume включает превью через рекомпозицию.
                    if (hasCameraPermission.value) {
                    key(cameraInitAttempt) {
                    CameraPreview(
                        torchOn = torchOn,
                        onCopyToClipboard = onCopyToClipboardForSew,
                            onCameraReady = { control, view ->
                                cameraControl = control
                                previewView = view
                                isCameraReady = true
                            },
                            onCameraError = { e ->
                                android.util.Log.e("OverlayActivity", "Camera init failed", e)
                                viewModel.onCameraError()
                            },
                            onScanError = {
                                viewModel.onScanError()
                            },
                            onBarcodeScanned = { result ->
                                coroutineScope.launch {
                                    try {
                                        detectedBarcode = true
                                        delay(500)
                                        val scannedUrl = result.barcode.trim()
                                        val articleCode = try {
                                            Regex("""mvideo\.ru/products/(\d+)""").find(scannedUrl)?.groupValues?.get(1)
                                        } catch (e: Exception) {
                                            null
                                        }
                                        val product = if (articleCode != null) {
                                            // init читает JSON с диска — только в IO, иначе I/O на Main при первом скане
                                            withContext(Dispatchers.IO) {
                                                ArticleBarcodeDatabase.init(context.applicationContext)
                                                ArticleBarcodeDatabase.searchByArticleCode(articleCode)
                                            }
                                        } else null
                                        val resolvedBarcode = when {
                                            product != null -> product.barcode
                                            articleCode != null -> articleCode
                                            else -> result.barcode
                                        }
                                        onBarcodeScanned(resolvedBarcode, product?.name)
                                        val resolvedResult = ScannerResult.Success(resolvedBarcode, result.format)
                                        if (sewCalibrated.value && autoImportSew.value) {
                                            // Копируем БЕЗ finish: triggerSewAutoInput финиширует один раз сам.
                                            // Иначе инжект стартует из умирающей Activity, а колбэк результата теряется.
                                            onCopyToClipboard(resolvedBarcode)
                                            onInjectToSew(resolvedBarcode)
                                        } else {
                                            if (product != null) {
                                                viewModel.onBarcodeDetected(resolvedResult, product.name, articleCode)
                                            } else if (articleCode != null) {
                                                viewModel.onBarcodeDetected(resolvedResult, articleCode = articleCode)
                                            } else {
                                                viewModel.onBarcodeDetected(result)
                                            }
                                        }
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        android.util.Log.e("ScanFlow", "launch crash", e)
                                    }
                                }
                            },
                            resetScanCompleted = state is OverlayViewModel.OverlayState.Scanning,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    }

                    // Loading overlay (shown while camera initializing)
                    if (!isCameraReady && state is OverlayViewModel.OverlayState.Scanning && !isCameraError) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xE6000000)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(36.dp),
                                color = Color(0xFF4CAF50),
                                strokeWidth = 3.dp
                            )
                        }
                    }

                // Green highlight box (centered, on detection)
                if (detectedBarcode) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp)
                            .height(80.dp)
                            .align(Alignment.Center)
                            .background(Color(0x204CAF50), RoundedCornerShape(8.dp))
                            .border(2.dp, Color(0xFF4CAF50), RoundedCornerShape(8.dp))
                    )
                }

                // Corner accents + scan line (only during active scanning)
                if (state !is OverlayViewModel.OverlayState.Success
                    && state !is OverlayViewModel.OverlayState.Error && !isTimedOut) {

                    // Green corner accents
                    Canvas(Modifier.fillMaxSize()) {
                        val m = 12.dp.toPx()
                        val s = 40.dp.toPx()
                        val w = 3.dp.toPx()
                        val c = Color(0xFF00E676)

                        drawLine(c, Offset(m, m + s), Offset(m, m), w, cap = StrokeCap.Round)
                        drawLine(c, Offset(m, m), Offset(m + s, m), w, cap = StrokeCap.Round)
                        drawLine(c, Offset(size.width - m - s, m), Offset(size.width - m, m), w, cap = StrokeCap.Round)
                        drawLine(c, Offset(size.width - m, m), Offset(size.width - m, m + s), w, cap = StrokeCap.Round)
                        drawLine(c, Offset(m, size.height - m - s), Offset(m, size.height - m), w, cap = StrokeCap.Round)
                        drawLine(c, Offset(m, size.height - m), Offset(m + s, size.height - m), w, cap = StrokeCap.Round)
                        drawLine(c, Offset(size.width - m - s, size.height - m), Offset(size.width - m, size.height - m), w, cap = StrokeCap.Round)
                        drawLine(c, Offset(size.width - m, size.height - m), Offset(size.width - m, size.height - m - s), w, cap = StrokeCap.Round)
                    }

                    // Static centered scan line
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                            .height(2.dp)
                            .align(Alignment.Center)
                            .background(
                                if (detectedBarcode) Color(0xFF00E676) else Color(0xAAFFFFFF),
                                RoundedCornerShape(1.dp)
                            )
                    )
                }

                FocusIndicator(point = focusPoint, success = focusSuccess)
            }
            // Text + buttons (only during active scanning)
            if (state !is OverlayViewModel.OverlayState.Success
                && state !is OverlayViewModel.OverlayState.Error && !isTimedOut) {
                Spacer(Modifier.height(24.dp))

                Text(
                    if (detectedBarcode) "штрихкод найден" else "наведите на код",
                    color = if (detectedBarcode) Color(0xFF00E676) else Color(0x55FFFFFF),
                    fontSize = 12.sp,
                    letterSpacing = 4.sp
                )

                Spacer(Modifier.height(16.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (torchOn) Color(0x33FFD600) else Color(0x0DFFFFFF))
                            .clickable { torchOn = !torchOn },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("⚡", fontSize = 14.sp,
                            color = if (torchOn) Color(0xFFFFD600) else Color(0x99FFFFFF))
                    }
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x1AFFFFFF))
                            .clickable { onClose() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть",
                            tint = Color(0xCCFFFFFF), modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // Full-screen overlays for non-scanning states
        when {
            permissionBlocked.value -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x99000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .background(Color(0x1AFFFFFF), RoundedCornerShape(24.dp))
                            .border(0.5.dp, Color(0x14FFFFFF), RoundedCornerShape(24.dp))
                            .padding(horizontal = 32.dp, vertical = 28.dp)
                    ) {
                        Text(
                            "Нет доступа к камере",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = Color.White
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Разрешение отклонено навсегда.\nВключите камеру в настройках.",
                            fontSize = 14.sp,
                            color = Color(0xCCFFFFFF)
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onOpenSettings) {
                            Text("Открыть настройки")
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onClose) {
                            Text("Закрыть", color = Color(0x99FFFFFF))
                        }
                    }
                }
            }

            state is OverlayViewModel.OverlayState.Success -> {
                val s = state as OverlayViewModel.OverlayState.Success
                val displayLabel = when {
                    s.productName != null -> "Арт. ${s.articleCode}\n${s.productName}"
                    s.articleCode != null -> "Арт. ${s.articleCode}"
                    else -> s.barcode
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x99000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .background(Color(0x1AFFFFFF), RoundedCornerShape(24.dp))
                            .border(0.5.dp, Color(0x14FFFFFF), RoundedCornerShape(24.dp))
                            .padding(horizontal = 40.dp, vertical = 32.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .background(Color(0xFF4CAF50), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("✓", fontSize = 36.sp, color = Color.White)
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = displayLabel,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            textAlign = TextAlign.Center,
                            color = Color.White
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (s.productName != null) "QR-код ценника" else "Штрихкод найден",
                            color = Color(0xAAFFFFFF),
                            fontSize = 13.sp,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = { onCopyToClipboard(s.barcode) },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF7B1FA2)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Копировать", color = Color.White)
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { onInjectToSew(s.barcode) },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF4CAF50)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Ввести в SEW", color = Color.White)
                        }
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = onClose) {
                            Text("Закрыть", color = Color(0xAAFFFFFF))
                        }
                    }
                }
            }

            state is OverlayViewModel.OverlayState.Error || isTimedOut -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x99000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .background(Color(0x1AFFFFFF), RoundedCornerShape(24.dp))
                            .border(0.5.dp, Color(0x14FFFFFF), RoundedCornerShape(24.dp))
                            .padding(horizontal = 40.dp, vertical = 32.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .background(Color(0xFFF44336), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("!", fontSize = 36.sp, color = Color.White)
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            if (isCameraError) "Ошибка камеры" else "Время ожидания истекло",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = Color.White
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (isCameraError) "Не удалось запустить камеру.\nПопробуйте повторить" else "Не удалось распознать штрихкод",
                            color = Color(0xAAFFFFFF),
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = onRetry,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0x33FFFFFF)
                            )
                        ) {
                            Text("Повторить", color = Color.White)
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onClose) {
                            Text("Закрыть", color = Color(0xAAFFFFFF))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Сколько кадров подряд должно отдать ошибку, прежде чем мы покажем экран ошибки.
 * Один сбой (image == null на переходе, гонка с перезапуском камеры) — обычное дело
 * и не должен прерывать сканирование.
 */
private const val SCAN_ERROR_STREAK_THRESHOLD = 5

@Composable
fun CameraPreview(
    torchOn: Boolean = false,
    onBarcodeScanned: (ScannerResult.Success) -> Unit,
    onCopyToClipboard: (String) -> Unit,
    resetScanCompleted: Boolean = false,
    onCameraReady: (CameraControl, PreviewView) -> Unit = { _, _ -> },
    onCameraError: (Exception) -> Unit = {},
    onScanError: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    val cameraControl = remember { mutableStateOf<CameraControl?>(null) }
    val scanCompleted = remember { AtomicBoolean(false) }
    val scanErrorStreak = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    val cameraFrameHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val scannerRef = remember { mutableStateOf<BarcodeAnalyzer?>(null) }
    val previewUseCase = remember { mutableStateOf<Preview?>(null) }
    val analysisUseCase = remember { mutableStateOf<ImageAnalysis?>(null) }

    LaunchedEffect(resetScanCompleted) {
        if (resetScanCompleted) {
            scanCompleted.set(false)
            scanErrorStreak.set(0)
            scannerRef.value?.reset()
        }
    }
    val cameraProviderRef = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val analyzerExecutor = remember { java.util.concurrent.Executors.newSingleThreadScheduledExecutor() }
    val isActive = remember { AtomicBoolean(true) }

    // Живёт на уровне композабла, а не внутри DisposableEffect: её зовёт и onDispose,
    // и catch в AndroidView-фабрике (bindToLifecycle failed) — разные области видимости.
    val cleanedUp = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val releaseCamera: () -> Unit = remember {
        fun() {
            if (!cleanedUp.compareAndSet(false, true)) return
            try {
                val provider = cameraProviderRef.value
                val useCases = listOfNotNull(previewUseCase.value, analysisUseCase.value)
                if (provider != null && useCases.isNotEmpty()) {
                    provider.unbind(*useCases.toTypedArray())
                }
            } catch (_: Exception) {
                // Already unbound (e.g. cleared by a newer session's bind): nothing to release.
            }
            CameraBinding.recordReleased()
            cameraControl.value = null
            cameraProviderRef.value = null
            previewUseCase.value = null
            analysisUseCase.value = null
            // Порядок важен: BarcodeAnalyzer закрывает ImageProxy в колбэке, висящем на
            // ПРЯМОМ executor'е (не на закрываемом), поэтому шутдаун executor'а не может помешать
            // отработать close(). Раньше здесь стоял отдельный executor для колбэков MLKit, и
            // его shutdown() в момент onDestroy ронял процесс: задачи, уже отправленные в MLKit,
            // доживали до закрытия executor'а, а play-services-tasks 18.1.0 не глушит отказ
            // RejectedExecutionException, а пробрасывает его в поток, регистрировавший
            // слушателя (главный) -> падение приложения при закрытии сканера.
            // Теоретически shutdownNow() здесь и не нужен: выше уже вызван provider.unbind(),
            // который снимает use-case и освобождает кадры.
            val scanner = scannerRef.value
            scannerRef.value = null
            scanner?.close()
            analyzerExecutor.shutdownNow()
        }
    }

    LaunchedEffect(torchOn, cameraControl.value) {
        try {
            cameraControl.value?.enableTorch(torchOn)
        } catch (_: Exception) {}
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                // Mark inactive immediately: a bind delayed by CameraBinding's cooldown
                // must not run against a destroyed lifecycle (it would throw and leak).
                isActive.set(false)
                releaseCamera()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            isActive.set(false)
            cameraFrameHandler.removeCallbacksAndMessages(null)
            lifecycleOwner.lifecycle.removeObserver(observer)
            releaseCamera()
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                if (!isActive.get()) {
                    android.util.Log.d("CameraPreview", "Skipping camera init: composable disposed")
                    return@addListener
                }

                val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                CameraBinding.runWhenCameraFree(mainHandler) {
                    if (!isActive.get()) return@runWhenCameraFree
                    try {
                        val cameraProvider = cameraProviderFuture.get()
                        android.util.Log.d("CameraPreview", "ProcessCameraProvider ready, binding camera")
                        cameraProviderRef.value = cameraProvider
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .apply {
                                val quality = ctx.getSharedPreferences("scanner_prefs", android.content.Context.MODE_PRIVATE)
                                    .getInt("scan_quality", 1)
                                val resolution = when (quality) {
                                    0 -> android.util.Size(640, 360)
                                    2 -> android.util.Size(1920, 1080)
                                    else -> android.util.Size(1280, 720)
                                }
                                setDefaultResolution(resolution)
                            }
                            .build()
                        val scanQrCodePref = ctx.getSharedPreferences("scanner_prefs", android.content.Context.MODE_PRIVATE)
                            .getBoolean("scan_qr_code", true)
                        scanErrorStreak.set(0)
                        imageAnalysis.setAnalyzer(
                            analyzerExecutor,
                            BarcodeAnalyzer(
                                scanQrCode = scanQrCodePref,
                                onResult = { result ->
                                    cameraFrameHandler.post {
                                        try {
                                            when (result) {
                                                is ScannerResult.Success -> {
                                                    scanErrorStreak.set(0)
                                                    if (scanCompleted.compareAndSet(false, true)) {
                                                        onBarcodeScanned(result)
                                                    }
                                                }
                                                is ScannerResult.Error -> {
                                                    // Одиночный сбой кадра (image == null на
                                                    // переходе) — обычное дело, экран не трогаем.
                                                    // Реальная поломка MLKit выглядит как серия
                                                    // сбоев подряд: без этого счётчика ошибка
                                                    // молча терялась, и пользователь 45 секунд
                                                    // видел «наведите на код», а потом — таймаут
                                                    // без причины.
                                                    if (scanErrorStreak.incrementAndGet() >= SCAN_ERROR_STREAK_THRESHOLD &&
                                                        !scanCompleted.get()
                                                    ) {
                                                        android.util.Log.e(
                                                            "CameraPreview",
                                                            "scanner failed ${scanErrorStreak.get()} frames in a row: ${result.message}"
                                                        )
                                                        scanCompleted.set(true)
                                                        onScanError()
                                                    }
                                                }
                                            }
                                        } catch (e: Exception) {
                                            android.util.Log.e("CameraPreview", "handler crash", e)
                                        }
                                    }
                                }
                            ).also { scannerRef.value = it }
                        )
                        cameraProvider.unbindAll()
                        val camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis
                        )
                        previewUseCase.value = preview
                        analysisUseCase.value = imageAnalysis
                        cameraControl.value = camera.cameraControl
                        onCameraReady(camera.cameraControl, previewView)
                    } catch (e: Exception) {
                        android.util.Log.e("CameraPreview", "bindToLifecycle failed", e)
                        // Free the analyzer thread + MLKit scanner right away instead of
                        // keeping them alive while the error screen is shown.
                        // Провайдера читаем ДО releaseCamera(): тот обнуляет
                        // cameraProviderRef, и forceReset() получил бы null — то есть
                        // unbindAll() не выполнился бы, а залипшие use-case'ы остались
                        // бы на провайдере до перезапуска процесса.
                        val providerForReset = cameraProviderRef.value
                        releaseCamera()
                        CameraBinding.forceReset(providerForReset)
                        onCameraError(e)
                    }
                }
            }, androidx.core.content.ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = modifier
    )
}

@Composable
private fun BoxScope.FocusIndicator(point: Offset?, success: Boolean?) {
    val currentPoint = point ?: return
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(1.3f) }
    val view = LocalView.current
    val density = LocalDensity.current
    val ringSize = 80.dp
    val ringSizePx = with(density) { ringSize.toPx() }

    LaunchedEffect(currentPoint) {
        alpha.snapTo(0f)
        scale.snapTo(1.3f)
        launch { alpha.animateTo(1f, tween(80)) }
        launch { scale.animateTo(1f, tween(150)) }
    }

    LaunchedEffect(success, currentPoint) {
        if (success == true) {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
        kotlinx.coroutines.delay(1000)
        alpha.animateTo(0f, tween(600))
    }

    val color = if (success == false) Color(0xFFFF5252) else Color(0xFFFFD600)

    Box(
        modifier = Modifier
            .size(ringSize)
            .align(Alignment.TopStart)
            .offset {
                IntOffset(
                    (currentPoint.x - ringSizePx / 2f).toInt(),
                    (currentPoint.y - ringSizePx / 2f).toInt()
                )
            }
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value
            }
            .border(2.dp, color, CircleShape)
    )
}




