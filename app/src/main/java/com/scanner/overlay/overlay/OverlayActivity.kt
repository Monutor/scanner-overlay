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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.runtime.DisposableEffect
import com.scanner.overlay.R
import com.scanner.overlay.accessibility.ScannerAccessibilityService
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

    private lateinit var vibrator: Vibrator
    private lateinit var prefs: android.content.SharedPreferences

    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val isSubmittingToSew = mutableStateOf(false)

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
        isSubmittingToSew.value = true
        val cal = buildSewCalibration()
        android.util.Log.d(
            "OverlayActivity",
            "triggerSewAutoInput: barcode=$barcode pkg=${cal.targetPackage} isCalibrated=${cal.isCalibrated} " +
                "openModal=(${cal.openModal.x},${cal.openModal.y}) " +
                "confirm=(${cal.confirm.x},${cal.confirm.y})"
        )
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
        mainHandler.post {
            isSubmittingToSew.value = false
            val title = if (ok) "Штрих введён" else "Ошибка ввода в SEW"
            val text = if (ok) "Готово" else message.take(200)
            notifySewResult(ok, title, text)
            vibrateResult(ok)
        }
    }

    private fun notifySewResult(success: Boolean, title: String, text: String) {
        try {
            val builder = NotificationCompat.Builder(this, ScannerApp.SEW_RESULT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_scan)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
            getSystemService(android.app.NotificationManager::class.java)
                .notify(ScannerApp.SEW_RESULT_NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            android.util.Log.e("OverlayActivity", "notifySewResult failed", e)
        }
    }

    private fun vibrateResult(ok: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ms = if (ok) 100L else 400L
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            toastAtBottom(getString(R.string.camera_unavailable), Toast.LENGTH_LONG)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.addFlags(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)

        prefs = getSharedPreferences("scanner_prefs", MODE_PRIVATE)
        BarcodeDatabase.init(this)
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

        val tapToFocusEnabled = prefs.getBoolean("tap_to_focus_enabled", true)
        val autoFocusEnabled = prefs.getBoolean("auto_focus_enabled", false)
        val sewCalibrated = buildSewCalibration().isCalibrated
        val autoImportSew = prefs.getBoolean("auto_import_sew", false)

        setContent {
            val viewModel = hiltViewModel<OverlayViewModel>()
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    OverlayContent(
                        viewModel = viewModel,
                        isSubmittingToSew = isSubmittingToSew,
                        onClose = { finish() },
                        onBarcodeScanned = { barcode -> onBarcodeScanned(barcode) },
                        onInjectToSew = { barcode ->
                            triggerSewAutoInput(barcode)
                        },
                        onCopyToClipboard = { barcode ->
                            copyToClipboard(barcode)
                        },
                        onRetry = {
                            viewModel.resetToScanning()
                        },
                        tapToFocusEnabled = tapToFocusEnabled,
                        autoFocusEnabled = autoFocusEnabled,
                        sewCalibrated = sewCalibrated,
                        autoImportSew = autoImportSew
                    )
                }
            }
        }
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun onBarcodeScanned(barcode: String) {
        try {
            vibrate()
            playBeep()
            speakShelfName(barcode)
            ScanHistoryEntry.add(prefs, barcode)
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

    private fun copyToClipboard(barcode: String) {
        try {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("barcode", barcode))
            toastAtBottom("Скопировано: $barcode")
        } catch (e: Exception) {
            android.util.Log.e("OverlayActivity", "copyToClipboard error", e)
        }
        if (!isFinishing) finish()
    }

    private fun setupVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
    }

    private fun playBeep() {
        try {
            resources.openRawResourceFd(R.raw.scan_beep)?.use { afd ->
                val mp = MediaPlayer()
                mp.setOnErrorListener { _, what, extra ->
                    mp.release()
                    playSystemBeep()
                    true
                }
                mp.setOnCompletionListener { it.release() }
                mp.setOnPreparedListener { player ->
                    try {
                        player.start()
                    } catch (_: Exception) {
                        player.release()
                        playSystemBeep()
                    }
                }
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                mp.prepareAsync()
            }
        } catch (_: Exception) {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
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
    isSubmittingToSew: State<Boolean> = remember { mutableStateOf(false) },
    onClose: () -> Unit,
    onBarcodeScanned: (String) -> Unit,
    onInjectToSew: (String) -> Unit,
    onCopyToClipboard: (String) -> Unit,
    onRetry: () -> Unit = {},
    tapToFocusEnabled: Boolean = true,
    autoFocusEnabled: Boolean = false,
    sewCalibrated: Boolean = false,
    autoImportSew: Boolean = false
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
    val isSubmitting by isSubmittingToSew

    LaunchedEffect(state, autoFocusEnabled, cameraControl, previewView) {
        if (!autoFocusEnabled) return@LaunchedEffect
        if (state !is OverlayViewModel.OverlayState.Scanning) return@LaunchedEffect
        val control = cameraControl ?: return@LaunchedEffect
        val view = previewView ?: return@LaunchedEffect
        val factory = view.meteringPointFactory
        val executor = ContextCompat.getMainExecutor(view.context)
        while (isActive) {
            val center = factory.createPoint(view.width / 2f, view.height / 2f)
            val action = FocusMeteringAction.Builder(
                center,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
            val future = control.startFocusAndMetering(action)
            future.addListener({
                runCatching { future.get() }
            }, executor)
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
            android.util.Log.d("ScanFlow", "LaunchedEffect: state=Success, barcode=${s.barcode}")
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
                    .pointerInput(state, tapToFocusEnabled) {
                        detectTapGestures { offset ->
                            if (!tapToFocusEnabled) return@detectTapGestures
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
                            val executor = ContextCompat.getMainExecutor(view.context)
                            val future = control.startFocusAndMetering(action)
                            future.addListener({
                                runCatching { future.get() }.onSuccess { result ->
                                    focusSuccess = result.isFocusSuccessful
                                }
                            }, executor)
                        }
                    }
            ) {
                    key(cameraInitAttempt) {
                        CameraPreview(
                            torchOn = torchOn,
                            onCameraReady = { control, view ->
                                cameraControl = control
                                previewView = view
                                isCameraReady = true
                            },
                            onCameraError = { e ->
                                android.util.Log.e("OverlayActivity", "Camera init failed", e)
                                viewModel.onCameraError()
                            },
                            onBarcodeScanned = { result ->
                                try {
                                    coroutineScope.launch {
                                        try {
                                            detectedBarcode = true
                                            onBarcodeScanned(result.barcode)
                                            delay(500)
                                            if (sewCalibrated && autoImportSew) {
                                                onCopyToClipboard(result.barcode)
                                                onInjectToSew(result.barcode)
                                            } else {
                                                viewModel.onBarcodeDetected(result)
                                            }
                                        } catch (e: kotlinx.coroutines.CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            android.util.Log.e("ScanFlow", "launch crash", e)
                                        }
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("ScanFlow", "outer crash", e)
                                }
                            },
                            resetScanCompleted = state is OverlayViewModel.OverlayState.Scanning,
                            modifier = Modifier.fillMaxSize()
                        )
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
            isSubmitting -> {
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
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = Color(0xFF4CAF50),
                            strokeWidth = 3.dp
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Ввод в SEW…",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = Color.White
                        )
                    }
                }
            }

            state is OverlayViewModel.OverlayState.Success -> {
                val barcode = (state as OverlayViewModel.OverlayState.Success).barcode
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
                            text = barcode,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            textAlign = TextAlign.Center,
                            color = Color.White
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Штрихкод найден",
                            color = Color(0xAAFFFFFF),
                            fontSize = 13.sp,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = { onCopyToClipboard(barcode) },
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
                            onClick = { onInjectToSew(barcode) },
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

@Composable
fun CameraPreview(
    torchOn: Boolean = false,
    onBarcodeScanned: (ScannerResult.Success) -> Unit,
    resetScanCompleted: Boolean = false,
    onCameraReady: (CameraControl, PreviewView) -> Unit = { _, _ -> },
    onCameraError: (Exception) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    val cameraControl = remember { mutableStateOf<CameraControl?>(null) }
    val scanCompleted = remember { AtomicBoolean(false) }
    val cameraFrameHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val scannerRef = remember { mutableStateOf<BarcodeAnalyzer?>(null) }

    LaunchedEffect(resetScanCompleted) {
        if (resetScanCompleted) {
            scanCompleted.set(false)
            scannerRef.value?.reset()
        }
    }
    val cameraProviderRef = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val analyzerExecutor = remember { java.util.concurrent.Executors.newSingleThreadScheduledExecutor() }

    LaunchedEffect(torchOn, cameraControl.value) {
        try {
            cameraControl.value?.enableTorch(torchOn)
        } catch (_: Exception) {}
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                cameraProviderRef.value?.unbindAll()
                cameraControl.value = null
                cameraProviderRef.value = null
                analyzerExecutor.shutdown()
                scannerRef.value?.close()
                scannerRef.value = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            cameraProviderRef.value?.unbindAll()
            cameraControl.value = null
            analyzerExecutor.shutdown()
            scannerRef.value?.close()
            scannerRef.value = null
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
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
                    imageAnalysis.setAnalyzer(
                        analyzerExecutor,
                        BarcodeAnalyzer(
                            executor = analyzerExecutor,
                            onResult = { result ->
                                cameraFrameHandler.post {
                                    try {
                                        if (result is ScannerResult.Success && scanCompleted.compareAndSet(false, true)) {
                                            onBarcodeScanned(result)
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
                    cameraControl.value = camera.cameraControl
                    onCameraReady(camera.cameraControl, previewView)
                } catch (e: Exception) {
                    android.util.Log.e("CameraPreview", "bindToLifecycle failed", e)
                    onCameraError(e)
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




