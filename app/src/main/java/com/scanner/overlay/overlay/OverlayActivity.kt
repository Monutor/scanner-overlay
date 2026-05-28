package com.scanner.overlay.overlay

import android.Manifest
import android.content.pm.PackageManager

import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.activity.compose.BackHandler
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.runtime.DisposableEffect
import com.scanner.overlay.R
import com.scanner.overlay.accessibility.ScannerAccessibilityService
import com.scanner.overlay.scanner.BarcodeAnalyzer
import com.scanner.overlay.scanner.ScannerResult

@AndroidEntryPoint
class OverlayActivity : ComponentActivity() {

    private lateinit var vibrator: Vibrator
    private lateinit var prefs: android.content.SharedPreferences

    private val finishHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val finishRunnable = Runnable { if (!isFinishing) finish() }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_LONG).show()
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
        setupVibrator()
        checkCameraPermission()

        setContent {
            val viewModel = hiltViewModel<OverlayViewModel>()
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    OverlayContent(
                        viewModel = viewModel,
                        onClose = { finish() },
                        onBarcodeScanned = { barcode -> onBarcodeScanned(barcode) },
                        onManualSubmit = { barcode ->
                            cancelFinish()
                            finish()
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                ScannerAccessibilityService.instance?.let {
                                    it.autoInjectText(barcode)
                                    it.injectText(barcode)
                                }
                            }, 500)
                        },
                        onRetry = { viewModel.resetToScanning() },
                        onCancelFinish = { cancelFinish() },
                        onRequestInputFocus = { requestInputFocus() },
                        onReleaseInputFocus = { releaseInputFocus() }
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
            val service = ScannerAccessibilityService.instance
            android.util.Log.d("OverlayActivity", "onBarcodeScanned: barcode=$barcode, service=${service != null}")
            if (service?.autoInjectText(barcode) != true) {
                android.util.Log.d("OverlayActivity", "autoInjectText failed, scheduling injectText")
                service?.injectText(barcode)
            }
            finishHandler.removeCallbacks(finishRunnable)
            finishHandler.postDelayed(finishRunnable, 1500L)
        } catch (e: Exception) {
            android.util.Log.e("OverlayActivity", "onBarcodeScanned crash", e)
        }
    }

    fun cancelFinish() {
        finishHandler.removeCallbacks(finishRunnable)
    }

    private fun injectText(text: String) {
        ScannerAccessibilityService.instance?.injectText(text)
    }

    private fun requestInputFocus() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    }

    private fun releaseInputFocus() {
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
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

    private fun vibrate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        finishHandler.removeCallbacks(finishRunnable)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverlayContent(
    viewModel: OverlayViewModel,
    onClose: () -> Unit,
    onBarcodeScanned: (String) -> Unit = {},
    onManualSubmit: (String) -> Unit,
    onRetry: () -> Unit = {},
    onCancelFinish: () -> Unit = {},
    onRequestInputFocus: () -> Unit = {},
    onReleaseInputFocus: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val isTimedOut by viewModel.isScanTimedOut.collectAsState()
    var manualInput by remember { mutableStateOf("") }
    var showManualInput by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var detectedBarcode by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(showManualInput) {
        if (showManualInput) onRequestInputFocus() else onReleaseInputFocus()
    }

    BackHandler {
        onClose()
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
            ) {
                CameraPreview(
                    torchOn = torchOn,
                    onBarcodeScanned = { result ->
                        try {
                            if (showManualInput) {
                                manualInput = result.barcode
                            } else {
                                coroutineScope.launch {
                                    try {
                                        detectedBarcode = true
                                        delay(800)
                                        viewModel.onBarcodeDetected(result)
                                        onBarcodeScanned(result.barcode)
                                    } catch (e: Exception) {
                                        android.util.Log.e("ScanFlow", "launch crash", e)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("ScanFlow", "outer crash", e)
                        }
                    },
                    onShowManualInput = { barcode ->
                        showManualInput = true
                        manualInput = barcode
                    },
                    onCancelFinish = onCancelFinish,
                    modifier = Modifier.fillMaxSize()
                )

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
                    && state !is OverlayViewModel.OverlayState.Error && !isTimedOut && !showManualInput) {

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
            }
            // Text + buttons (only during active scanning)
            if (state !is OverlayViewModel.OverlayState.Success
                && state !is OverlayViewModel.OverlayState.Error && !isTimedOut && !showManualInput) {
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
                            .clickable { showManualInput = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("⌨", fontSize = 14.sp, color = Color(0xCCFFFFFF))
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
            showManualInput -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xCC000000))
                        .clickable(enabled = false) {},
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier
                            .padding(24.dp)
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Ручной ввод",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(16.dp))
                            OutlinedTextField(
                                value = manualInput,
                                onValueChange = { manualInput = it },
                                label = { Text("Штрихкод") },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        if (manualInput.isNotBlank()) {
                                            onManualSubmit(manualInput)
                                            onClose()
                                        }
                                    }
                                ),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                OutlinedButton(onClick = { showManualInput = false }) {
                                    Text("Назад")
                                }
                                Button(
                                    onClick = {
                                        if (manualInput.isNotBlank()) {
                                            onManualSubmit(manualInput)
                                            onClose()
                                        }
                                    }
                                ) {
                                    Text("Отправить")
                                }
                            }
                        }
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
                            fontSize = 22.sp,
                            textAlign = TextAlign.Center,
                            color = Color.White
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Готово",
                            color = Color(0xAAFFFFFF),
                            fontSize = 13.sp,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = { onClose() },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0x33FFFFFF)
                            )
                        ) {
                            Text("Закрыть", color = Color.White)
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
                            "Время ожидания истекло",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = Color.White
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Не удалось распознать штрихкод",
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
                        Button(
                            onClick = { showManualInput = true },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0x33FFFFFF)
                            )
                        ) {
                            Text("Ввести вручную", color = Color.White)
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
    onShowManualInput: ((String) -> Unit)? = null,
    onCancelFinish: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    val cameraControl = remember { mutableStateOf<CameraControl?>(null) }
    val scanCompleted = remember { AtomicBoolean(false) }
    val currentOnShowManualInput = rememberUpdatedState(onShowManualInput)
    val cameraProviderRef = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val analyzerExecutor = remember { java.util.concurrent.Executors.newSingleThreadExecutor() }

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
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { 
            lifecycleOwner.lifecycle.removeObserver(observer)
            cameraProviderRef.value?.unbindAll()
            cameraControl.value = null
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                cameraProviderRef.value = cameraProvider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setDefaultResolution(android.util.Size(1280, 720))
                    .build()
                imageAnalysis.setAnalyzer(
                    analyzerExecutor,
                    BarcodeAnalyzer(
                        onResult = { result ->
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                try {
                                    if (result is ScannerResult.Success && scanCompleted.compareAndSet(false, true)) {
                                        onBarcodeScanned(result)
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("CameraPreview", "handler crash", e)
                                }
                            }
                        }
                    )
                )
                try {
                    cameraProvider.unbindAll()
                    val camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                    cameraControl.value = camera.cameraControl
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, androidx.core.content.ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = modifier
    )
}




