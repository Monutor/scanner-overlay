package com.scanner.overlay.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.scanner.overlay.overlay.CameraBinding
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@AndroidEntryPoint
class QrScannerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            QrScannerScreen(
                onScanned = { text ->
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_SCANNED_TEXT, text))
                    finish()
                },
                onCancel = {
                    setResult(RESULT_CANCELED)
                    finish()
                }
            )
        }
    }

    companion object {
        const val EXTRA_SCANNED_TEXT = "scanned_text"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QrScannerScreen(
    onScanned: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        if (!granted) {
            Toast.makeText(context, "Нет разрешения на камеру", Toast.LENGTH_SHORT).show()
            onCancel()
        }
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Сканировать QR-код") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        if (permissionGranted) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                QrCameraView(
                    modifier = Modifier.fillMaxSize(),
                    onQrDetected = onScanned
                )
                ScanFrameOverlay()
            }
        }
    }
}

@Composable
private fun QrCameraView(
    modifier: Modifier = Modifier,
    onQrDetected: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderRef = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val scannerRef = remember { mutableStateOf<BarcodeScanner?>(null) }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val isActive = remember { AtomicBoolean(true) }
    val detected = remember { AtomicBoolean(false) }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                if (!isActive.get()) return@addListener
                // ProcessCameraProvider is a process-wide singleton shared with OverlayActivity.
                // Bypass the cooldown and a bind can land inside an in-flight native release
                // from another session, which silently yields a black preview.
                val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                CameraBinding.runWhenCameraFree(mainHandler) {
                if (!isActive.get()) return@runWhenCameraFree
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    cameraProviderRef.value = cameraProvider

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setDefaultResolution(android.util.Size(1280, 720))
                        .build()

                    val scanner = BarcodeScanning.getClient(
                        BarcodeScannerOptions.Builder()
                            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                            .build()
                    )
                    scannerRef.value = scanner

                    imageAnalysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                        scanQrImage(imageProxy, scanner, detected, onQrDetected)
                    }

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    android.util.Log.e("QrCameraView", "Camera init failed", e)
                    // A failed bind may leave use-cases partially attached to the shared
                    // provider; clear them so the next session starts from a clean state.
                    CameraBinding.forceReset(cameraProviderRef.value)
                    cameraProviderRef.value = null
                }
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = modifier
    )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                isActive.set(false)
                // forceReset also records the release so the next session respects the cooldown.
                CameraBinding.forceReset(cameraProviderRef.value)
                cameraProviderRef.value = null
                scannerRef.value?.close()
                analyzerExecutor.shutdownNow()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            isActive.set(false)
            lifecycleOwner.lifecycle.removeObserver(observer)
            CameraBinding.forceReset(cameraProviderRef.value)
            cameraProviderRef.value = null
            scannerRef.value?.close()
            analyzerExecutor.shutdownNow()
        }
    }
}

@OptIn(ExperimentalGetImage::class)
private fun scanQrImage(
    imageProxy: ImageProxy,
    scanner: BarcodeScanner,
    detected: AtomicBoolean,
    onQrDetected: (String) -> Unit
) {
    if (detected.get()) {
        imageProxy.close()
        return
    }
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        try {
            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            scanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        barcode.rawValue?.let { value ->
                            if (detected.compareAndSet(false, true)) {
                                onQrDetected(value)
                            }
                            return@addOnSuccessListener
                        }
                    }
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } catch (_: Exception) {
            // close() живёт только в addOnCompleteListener: если process() бросил, Task не
            // завершится и кадр не вернётся в буфер камеры. Как в BarcodeAnalyzer.analyze().
            imageProxy.close()
        }
    } else {
        imageProxy.close()
    }
}

@Composable
private fun ScanFrameOverlay() {
    val boxFraction = 0.7f          // square side as fraction of min screen dimension
    val scrimAlpha = 0.55f
    val frameColor = Color(0xFF388E3C)   // green
    val frameStrokeWidth = 6.dp
    val cornerRadius = 12.dp
    val hintColor = Color.White
    val density = androidx.compose.ui.platform.LocalDensity.current

    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val boxSize = (minOf(size.width, size.height) * boxFraction).roundToInt()
            val left = (size.width - boxSize) / 2
            val top = (size.height - boxSize) / 2
            val right = left + boxSize
            val bottom = top + boxSize

            // Dark scrim: four strips around the central square.
            drawRect(
                color = Color.Black.copy(scrimAlpha),
                topLeft = Offset(x = 0f, y = 0f),
                size = Size(size.width.toFloat(), top.toFloat())
            )
            drawRect(
                color = Color.Black.copy(scrimAlpha),
                topLeft = Offset(x = 0f, y = bottom.toFloat()),
                size = Size(size.width.toFloat(), (size.height - bottom).toFloat())
            )
            drawRect(
                color = Color.Black.copy(scrimAlpha),
                topLeft = Offset(x = 0f, y = top.toFloat()),
                size = Size(left.toFloat(), boxSize.toFloat())
            )
            drawRect(
                color = Color.Black.copy(scrimAlpha),
                topLeft = Offset(x = right.toFloat(), y = top.toFloat()),
                size = Size((size.width - right).toFloat(), boxSize.toFloat())
            )

            // Rounded green frame around the scan area.
            drawRoundRect(
                color = frameColor,
                topLeft = Offset(x = left.toFloat(), y = top.toFloat()),
                size = Size(boxSize.toFloat(), boxSize.toFloat()),
                cornerRadius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx()),
                style = Stroke(width = frameStrokeWidth.toPx())
            )
        }

        // Hint text centered horizontally, shifted down to sit just below the frame.
        // constraints здесь в px (DrawScope), поэтому конвертируем px -> Dp, а не трактуем как dp.
        Text(
            text = "Поместите QR-код в рамку",
            color = hintColor,
            modifier = Modifier.offset(
                y = with(density) {
                    (minOf(constraints.minWidth, constraints.minHeight) * boxFraction / 2f).toDp()
                }
            )
        )
    }
}
