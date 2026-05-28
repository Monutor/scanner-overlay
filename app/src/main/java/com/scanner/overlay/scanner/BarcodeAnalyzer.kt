package com.scanner.overlay.scanner

import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import androidx.compose.ui.geometry.Rect

class BarcodeAnalyzer(
    private val scanRegionWidthFraction: Float = 0.80f,
    private val cooldownMs: Long = 2000L,
    private val startupDelayMs: Long = 1500L,
    private val onResult: (ScannerResult) -> Unit
) : ImageAnalysis.Analyzer {
    private val createdAt = System.currentTimeMillis()
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_QR_CODE or
                Barcode.FORMAT_EAN_13 or
                Barcode.FORMAT_EAN_8 or
                Barcode.FORMAT_CODE_128 or
                Barcode.FORMAT_CODE_39 or
                Barcode.FORMAT_CODE_93 or
                Barcode.FORMAT_UPC_A or
                Barcode.FORMAT_UPC_E or
                Barcode.FORMAT_DATA_MATRIX or
                Barcode.FORMAT_AZTEC or
                Barcode.FORMAT_PDF417
            )
            .build()
    )

    private var lastScannedCode: String? = null
    private var lastScanTime = 0L
    private val scannedCodes = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val maxCachedCodes = 50

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            onResult(ScannerResult.Error("No image from camera"))
            imageProxy.close()
            return
        }

        val elapsed = System.currentTimeMillis() - createdAt
        if (elapsed < startupDelayMs) {
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val imgW = imageProxy.width
        val imgH = imageProxy.height
        android.util.Log.d("BarcodeAnalyzer", "analyze frame: ${imgW}x${imgH} rot=$rotation")

        try {
            val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

            scanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    android.util.Log.d("BarcodeAnalyzer", "MLKit detected ${barcodes.size} barcode(s)")
                    if (barcodes.isNotEmpty()) {
                        val regionW = (imgW * scanRegionWidthFraction).toInt()
                        val centerXImg = imgW / 2f
                        val centerYImg = imgH / 2f
                        val verticalMargin = imgH * 0.15f

                        for ((i, barcode) in barcodes.withIndex()) {
                            val box = barcode.boundingBox
                            android.util.Log.d("BarcodeAnalyzer", "  barcode[$i] format=${barcode.format} value=${barcode.rawValue} center=(${box?.centerX()},${box?.centerY()}) bounds=(${box?.left},${box?.top},${box?.right},${box?.bottom})")
                        }

                        val centerBarcode = barcodes.firstOrNull { barcode ->
                            val box = barcode.boundingBox ?: run {
                                android.util.Log.d("BarcodeAnalyzer", "  rejected: no boundingBox")
                                return@firstOrNull false
                            }
                            val cx = box.centerX().toFloat()
                            val cy = box.centerY().toFloat()
                            val leftEdge = (imgW - regionW) / 2f
                            val rightEdge = leftEdge + regionW
                            val horizontallyCentered = cx in leftEdge..rightEdge
                            val verticallyClose = cy in (centerYImg - verticalMargin)..(centerYImg + verticalMargin)
                            if (!horizontallyCentered) android.util.Log.d("BarcodeAnalyzer", "  rejected: not horizontally centered, cx=$cx range=[$leftEdge..$rightEdge]")
                            if (!verticallyClose) android.util.Log.d("BarcodeAnalyzer", "  rejected: not vertically close, cy=$cy range=[${centerYImg - verticalMargin}..${centerYImg + verticalMargin}]")
                            horizontallyCentered && verticallyClose
                        }

                        if (centerBarcode == null) {
                            android.util.Log.w("BarcodeAnalyzer", "No barcode in center region, skipping all ${barcodes.size} detected")
                            return@addOnSuccessListener
                        }

                        val value = centerBarcode.rawValue
                            ?: centerBarcode.displayValue
                            ?: run {
                                android.util.Log.w("BarcodeAnalyzer", "barcode has no rawValue or displayValue")
                                return@addOnSuccessListener
                            }

                        if (centerBarcode.format == Barcode.FORMAT_CODE_39 && value.length < 12) {
                            android.util.Log.d("BarcodeAnalyzer", "rejected CODE_39 too short: $value (${value.length} chars)")
                            return@addOnSuccessListener
                        }

                        val now = System.currentTimeMillis()
                        if (value == lastScannedCode && now - lastScanTime < cooldownMs) {
                            android.util.Log.d("BarcodeAnalyzer", "rejected cooldown: $value (${now - lastScanTime}ms since last)")
                            return@addOnSuccessListener
                        }

                        if (scannedCodes.contains(value)) {
                            android.util.Log.d("BarcodeAnalyzer", "rejected duplicate: $value")
                            return@addOnSuccessListener
                        }
                        addScannedCode(value)
                        lastScannedCode = value
                        lastScanTime = now
                        android.util.Log.d("BarcodeAnalyzer", "SUCCESS: $value format=${centerBarcode.format}")

                        val overlayData = centerBarcode.boundingBox?.let { box ->
                            BarcodeOverlayData(
                                boundingBox = Rect(
                                    box.left.toFloat(), box.top.toFloat(),
                                    box.right.toFloat(), box.bottom.toFloat()
                                ),
                                imageWidth = imgW,
                                imageHeight = imgH,
                                imageRotation = rotation
                            )
                        }
                        onResult(ScannerResult.Success(value, centerBarcode.format, overlayData))
                    }
                }
                .addOnFailureListener { e ->
                    android.util.Log.e("BarcodeAnalyzer", "MLKit failed: ${e.message}", e)
                    onResult(ScannerResult.Error("MLKit: ${e.message}"))
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } catch (e: Exception) {
            android.util.Log.e("BarcodeAnalyzer", "analyze exception", e)
            onResult(ScannerResult.Error("analyze: ${e.message}"))
            imageProxy.close()
        }
    }

    private fun addScannedCode(code: String) {
        scannedCodes.add(code)
        while (scannedCodes.size > maxCachedCodes) {
            scannedCodes.poll()
        }
    }
}