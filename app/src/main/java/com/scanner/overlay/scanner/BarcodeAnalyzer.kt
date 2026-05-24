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
    private val startupDelayMs: Long = 600L,
    private val scanRegionWidthFraction: Float = 0.60f,
    private val cooldownMs: Long = 2000L,
    private val onResult: (ScannerResult) -> Unit
) : ImageAnalysis.Analyzer {

    private val startTime = System.currentTimeMillis()
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
    private val scannedCodes = java.util.LinkedList<String>()
    private val maxCachedCodes = 50

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            onResult(ScannerResult.Error("No image from camera"))
            imageProxy.close()
            return
        }

        if (System.currentTimeMillis() - startTime < startupDelayMs) {
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val imgW = imageProxy.width
        val imgH = imageProxy.height

        try {
            val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

            scanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    if (barcodes.isNotEmpty()) {
                        val regionW = (imgW * scanRegionWidthFraction).toInt()
                        val centerY = imgH / 2f

                        val centerBarcode = barcodes.firstOrNull { barcode ->
                            val box = barcode.boundingBox ?: return@firstOrNull false
                            val centerX = box.centerX().toFloat()
                            val leftEdge = (imgW - regionW) / 2f
                            val rightEdge = leftEdge + regionW
                            val horizontallyCentered = centerX in leftEdge..rightEdge
                            val verticallyCrossesCenter = box.top < centerY && box.bottom > centerY
                            horizontallyCentered && verticallyCrossesCenter
                        } ?: return@addOnSuccessListener

                        val value = centerBarcode.rawValue
                            ?: centerBarcode.displayValue
                            ?: return@addOnSuccessListener

                        if (centerBarcode.format == Barcode.FORMAT_CODE_39 && value.length < 12) {
                            return@addOnSuccessListener
                        }

                        val now = System.currentTimeMillis()
                        if (value == lastScannedCode && now - lastScanTime < cooldownMs) {
                            return@addOnSuccessListener
                        }

                        if (scannedCodes.contains(value)) {
                            return@addOnSuccessListener
                        }
                        addScannedCode(value)
                        lastScannedCode = value
                        lastScanTime = now

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
        } catch (e: Exception) {
            android.util.Log.e("BarcodeAnalyzer", "analyze exception", e)
            onResult(ScannerResult.Error("analyze: ${e.message}"))
        } finally {
            imageProxy.close()
        }
    }

    private fun addScannedCode(code: String) {
        if (scannedCodes.size >= maxCachedCodes) {
            scannedCodes.poll()
        }
        scannedCodes.add(code)
    }
}