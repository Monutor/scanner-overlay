package com.scanner.overlay.scanner

import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.scanner.overlay.BuildConfig
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class BarcodeAnalyzer(
    private val maxCenterDistanceFraction: Float = 0.18f,
    private val cooldownMs: Long = 2000L,
    private val startupDelayMs: Long = 1500L,
    private val executor: ScheduledExecutorService,
    private val windowMs: Long = 300L,
    private val fallbackDelayMs: Long = 700L,
    private val requiredMatches: Int = 2,
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

    private val window = FrameWindow(windowMs)
    @Volatile private var firstScanAt: Long = 0L
    @Volatile private var lastFiredCode: String? = null
    private var lastFireTime = 0L

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
        if (BuildConfig.DEBUG) android.util.Log.d("BarcodeAnalyzer", "analyze frame: ${imgW}x${imgH} rot=$rotation")

        try {
            val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

            scanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    if (BuildConfig.DEBUG) android.util.Log.d("BarcodeAnalyzer", "MLKit detected ${barcodes.size} barcode(s)")
                    if (barcodes.isNotEmpty()) {
                        val isRotated = rotation == 90 || rotation == 270
                        val rotW = (if (isRotated) imgH else imgW).toFloat()
                        val rotH = (if (isRotated) imgW else imgH).toFloat()
                        val centerImgX = rotW / 2f
                        val centerImgY = rotH / 2f

                        val maxDist = Math.hypot(rotW.toDouble(), rotH.toDouble()) * maxCenterDistanceFraction

                        val validBarcodes = barcodes.filterNotNull().filter { it.boundingBox != null }

                        if (validBarcodes.isEmpty()) {
                            if (BuildConfig.DEBUG) android.util.Log.w("BarcodeAnalyzer", "No barcodes with boundingBox")
                            return@addOnSuccessListener
                        }

                        val centerBarcode = validBarcodes.minByOrNull { barcode ->
                            val box = barcode.boundingBox!!
                            val cx = box.centerX().toFloat()
                            val cy = box.centerY().toFloat()
                            Math.hypot(cx.toDouble() - centerImgX.toDouble(), cy.toDouble() - centerImgY.toDouble())
                        }

                        val dist = Math.hypot(
                            (centerBarcode!!.boundingBox!!.centerX().toFloat() - centerImgX).toDouble(),
                            (centerBarcode.boundingBox!!.centerY().toFloat() - centerImgY).toDouble()
                        )
                        if (dist > maxDist) {
                            if (BuildConfig.DEBUG) android.util.Log.d("BarcodeAnalyzer", "rejected too far from center: dist=$dist max=$maxDist (${centerBarcode.rawValue})")
                            return@addOnSuccessListener
                        }

                        val value = centerBarcode.rawValue
                            ?: centerBarcode.displayValue
                            ?: run {
                                if (BuildConfig.DEBUG) android.util.Log.w("BarcodeAnalyzer", "barcode has no rawValue or displayValue")
                                return@addOnSuccessListener
                            }

                        if (centerBarcode.format == Barcode.FORMAT_CODE_39 && value.length < 12) {
                            if (BuildConfig.DEBUG) android.util.Log.d("BarcodeAnalyzer", "rejected CODE_39 too short: $value (${value.length} chars)")
                            return@addOnSuccessListener
                        }

                        val canonical = BarcodeShape.bestCanonical(value)
                        if (BuildConfig.DEBUG) android.util.Log.d("BarcodeAnalyzer", "value='$value' canonical=$canonical")

                        if (canonical != null) {
                            handleWarehouseCode(canonical, centerBarcode.format)
                        } else {
                            handleNonWarehouseCode(value, centerBarcode.format)
                        }
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

    private fun handleWarehouseCode(canonical: String, format: Int) {
        val now = System.currentTimeMillis()
        if (canonical == lastFiredCode && now - lastFireTime < cooldownMs) {
            if (BuildConfig.DEBUG) android.util.Log.d("BarcodeAnalyzer", "rejected warehouse cooldown: $canonical")
            return
        }

        val confirmed = window.add(canonical, now, requiredMatches)
        if (confirmed != null) {
            val lookupResult = BarcodeDatabase.lookup(canonical)
            if (BuildConfig.DEBUG) android.util.Log.d("BarcodeAnalyzer", "CONFIRMED: $canonical lookup=$lookupResult")
            onResult(ScannerResult.Success(canonical, format, lookupResult))
            lastFiredCode = canonical
            lastFireTime = now
            window.clear()
            firstScanAt = 0L
        } else if (firstScanAt == 0L) {
            firstScanAt = now
            executor.schedule(::fireFallback, fallbackDelayMs, TimeUnit.MILLISECONDS)
            if (BuildConfig.DEBUG) android.util.Log.d("BarcodeAnalyzer", "warehouse waiting for confirmation: $canonical (window size=${window.size()})")
        } else if (BuildConfig.DEBUG) {
            android.util.Log.d("BarcodeAnalyzer", "warehouse accumulating: $canonical (window size=${window.size()})")
        }
    }

    private fun fireFallback() {
        if (firstScanAt == 0L) return
        val best = window.bestCanonical()
        firstScanAt = 0L
        window.clear()
        if (best == null || best == lastFiredCode) {
            if (BuildConfig.DEBUG) android.util.Log.d("BarcodeAnalyzer", "fallback: nothing to fire (best=$best lastFired=$lastFiredCode)")
            return
        }
        val lookupResult = BarcodeDatabase.lookup(best)
        if (BuildConfig.DEBUG) android.util.Log.d("BarcodeAnalyzer", "FALLBACK: $best lookup=$lookupResult")
        onResult(ScannerResult.Success(best, Barcode.FORMAT_CODE_128, lookupResult))
        lastFiredCode = best
        lastFireTime = System.currentTimeMillis()
    }

    private fun handleNonWarehouseCode(value: String, format: Int) {
        val now = System.currentTimeMillis()
        if (value == lastScannedCode && now - lastScanTime < cooldownMs) {
            if (BuildConfig.DEBUG) android.util.Log.d("BarcodeAnalyzer", "rejected non-warehouse cooldown: $value")
            return
        }
        if (scannedCodes.contains(value)) {
            if (BuildConfig.DEBUG) android.util.Log.d("BarcodeAnalyzer", "rejected non-warehouse duplicate: $value")
            return
        }
        addScannedCode(value)
        lastScannedCode = value
        lastScanTime = now
        if (BuildConfig.DEBUG) android.util.Log.d("BarcodeAnalyzer", "NON-WAREHOUSE SUCCESS: $value")
        onResult(ScannerResult.Success(value, format, null))
    }

    private fun addScannedCode(code: String) {
        scannedCodes.add(code)
        while (scannedCodes.size > maxCachedCodes) {
            scannedCodes.poll()
        }
    }

    fun reset() {
        executor.execute {
            window.clear()
            firstScanAt = 0L
            lastFiredCode = null
        }
    }

    fun close() {
        scanner.close()
    }
}
