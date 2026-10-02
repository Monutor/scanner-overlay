package com.scanner.overlay.scanner

import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.scanner.overlay.BuildConfig
import java.util.concurrent.Executor

class BarcodeAnalyzer(
    private val maxCenterDistanceFraction: Float = 0.18f,
    private val cooldownMs: Long = 2000L,
    private val startupDelayMs: Long = 1500L,
    private val scanQrCode: Boolean = true,
    private val onResult: (ScannerResult) -> Unit,
    /**
     * Живой контур вокруг найденного штрихкода. Отдельный сигнал от [onResult] намеренно:
     * [onResult] срабатывает уже после проверки центра, кулдауна и дедупа, а дальше идёт
     * `delay()` и запрос к БД - к тому моменту контур показывать поздно. Здесь нужно
     * положение кода на каждом кадре, включая отклонённые кулдауном.
     */
    private val onBarcodeTracked: (BarcodeQuad?) -> Unit = {}
) : ImageAnalysis.Analyzer {
    // Монотонные часы: System.currentTimeMillis() прыгает при синхронизации времени,
    // и тогда elapsed становился отрицательным, а условие "прошло ли время запуска"
    // выполнялось для каждого кадра — сканер молча переставал видеть штрихкоды.
    private val createdAt = android.os.SystemClock.elapsedRealtime()

    /**
     * Колбэки MLKit выполняются напрямую на потоке, завершившем задачу, и НИКОГДА не через
     * закрываемый executor.
     *
     * Раньше здесь был `Executors.newSingleThreadScheduledExecutor()`, который `releaseCamera()`
     * закрывал в `shutdown()` в момент `onDestroy`. Задачи, уже отправленные в MLKit, при этом
     * доживали до закрытия executor'а: `play-services-tasks` 18.1.0 **не глушит** отказ
     * `RejectedExecutionException`, а пробрасывает его в поток, который регистрировал
     * слушателя (главный), и процесс падал. Это подтверждено стектрейсом из dropbox:
     * `ScheduledThreadPoolExecutor.delayedExecute -> RejectedExecutionException ->
     * gms.tasks.zzn.zzd -> Handler.handleCallback -> ActivityThread.main`.
     *
     * Прямой executor не может быть Terminated, поэтому отказ невозможен в принципе, а
     * `imageProxy.close()` идёт сразу после завершения задачи - без очереди, вставленной
     * десятками кадров, из-за которой STRATEGY_KEEP_ONLY_LATEST выбрасывал кадры.
     */
    private val callbackExecutor = Executor { it.run() }

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                (if (scanQrCode) Barcode.FORMAT_QR_CODE else 0) or
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
    private val scanLock = Any()
    private val scannedCodes = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val maxCachedCodes = 50

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            // A frame without an Image is routine while CameraX tears down or re-binds, and it
            // arrives once per frame. Reporting it through onResult used to fill the error
            // streak instantly and show a bogus "camera error" to the user.
            if (BuildConfig.DEBUG) android.util.Log.w("BarcodeAnalyzer", "analyze: frame without image")
            imageProxy.close()
            return
        }

        val elapsed = android.os.SystemClock.elapsedRealtime() - createdAt
        if (elapsed < startupDelayMs) {
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val imgW = imageProxy.width
        val imgH = imageProxy.height
        if (BuildConfig.DEBUG) android.util.Log.d("BarcodeAnalyzer", "analyze frame: ${imgW}x${imgH} rot=$rotation")

        // Флаг для catch: если колбэк close() уже зарегистрирован, повторно закрывать прокси
        // нельзя - лишний close() на уже освобождённом буфере бросает из ImageReader.
        var closeRegistered = false
        try {
            val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

            scanner.process(inputImage)
                // Порядок важен: close() вешаем ПЕРВЫМ. Если бы регистрация success/failure
                // бросила исключение, цепочка оборвалась бы до close и буфер ImageReader
                // не вернулся бы никогда (с каждым кадром пул кадров исчерпывается).
                .addOnCompleteListener(callbackExecutor) {
                    imageProxy.close()
                }
                .also { closeRegistered = true }
                .addOnSuccessListener(callbackExecutor) { barcodes ->
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
                            onBarcodeTracked(null)
                            return@addOnSuccessListener
                        }

                        val centerBarcode = validBarcodes.minByOrNull { barcode ->
                            val box = barcode.boundingBox!!
                            val cx = box.centerX().toFloat()
                            val cy = box.centerY().toFloat()
                            Math.hypot(cx.toDouble() - centerImgX.toDouble(), cy.toDouble() - centerImgY.toDouble())
                        }

                        // Контур рисуется ДО проверки расстояния до центра: он должен
                        // подсказывать, куда наводить даже когда код ещё вне зелёной рамки.
                        onBarcodeTracked(centerBarcode!!.toQuad(rotW.toInt(), rotH.toInt()))

                        val dist = Math.hypot(
                            (centerBarcode!!.boundingBox!!.centerX().toFloat() - centerImgX).toDouble(),
                            (centerBarcode.boundingBox!!.centerY().toFloat() - centerImgY).toDouble()
                        )
                        if (dist > maxDist) {
                            if (BuildConfig.DEBUG) android.util.Log.d("BarcodeAnalyzer", "rejected too far from center: dist=$dist max=$maxDist (${centerBarcode.rawValue})")
                            return@addOnSuccessListener
                        }

                        val rawValue = centerBarcode.rawValue
                            ?: centerBarcode.displayValue
                            ?: run {
                                if (BuildConfig.DEBUG) android.util.Log.w("BarcodeAnalyzer", "barcode has no rawValue or displayValue")
                                return@addOnSuccessListener
                            }
                        val value = if (rawValue.startsWith("]C1")) {
                            if (BuildConfig.DEBUG) android.util.Log.d("BarcodeAnalyzer", "stripped ]C1 prefix: $rawValue → ${rawValue.removePrefix("]C1")}")
                            rawValue.removePrefix("]C1")
                        } else rawValue

                        if (centerBarcode.format == Barcode.FORMAT_CODE_39 && value.length < 12) {
                            if (BuildConfig.DEBUG) android.util.Log.d("BarcodeAnalyzer", "rejected CODE_39 too short: $value (${value.length} chars)")
                            return@addOnSuccessListener
                        }

                        handleBarcode(value, centerBarcode.format)
                    } else {
                        onBarcodeTracked(null)
                    }
                }
                .addOnFailureListener(callbackExecutor) { e ->
                    android.util.Log.e("BarcodeAnalyzer", "MLKit failed: ${e.message}", e)
                    onResult(ScannerResult.Error("MLKit: ${e.message}"))
                }
        } catch (e: Exception) {
            android.util.Log.e("BarcodeAnalyzer", "analyze exception", e)
            onResult(ScannerResult.Error("analyze: ${e.message}"))
            if (!closeRegistered) imageProxy.close()
        }
    }

    private fun handleBarcode(value: String, format: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        // The check and the commit must share one critical section: reset() runs on the main
        // thread ("Повторить"), so a window between two separate synchronized blocks let
        // reset() clear the state and then handleBarcode wrote lastScannedCode/scannedCodes
        // back - the repeat button silently failed to lift the cooldown.
        synchronized(scanLock) {
            if (value == lastScannedCode && now - lastScanTime < cooldownMs) {
                if (BuildConfig.DEBUG) android.util.Log.d("BarcodeAnalyzer", "rejected cooldown: $value")
                return
            }
            if (scannedCodes.contains(value)) {
                if (BuildConfig.DEBUG) android.util.Log.d("BarcodeAnalyzer", "rejected duplicate: $value")
                return
            }
            addScannedCode(value)
            lastScannedCode = value
            lastScanTime = now
        }
        if (BuildConfig.DEBUG) android.util.Log.d("BarcodeAnalyzer", "SUCCESS: $value")
        onResult(ScannerResult.Success(value, format))
    }

    private fun addScannedCode(code: String) {
        scannedCodes.add(code)
        while (scannedCodes.size > maxCachedCodes) {
            scannedCodes.poll()
        }
    }

    fun reset() {
        synchronized(scanLock) {
            lastScannedCode = null
            lastScanTime = 0L
            scannedCodes.clear()
        }
    }

    fun close() {
        scanner.close()
    }
}

/**
 * Углы штрихкода для отрисовки контура. Сперва `cornerPoints` — они учитывают
 * перспективную дисторсию, поэтому контур повторит наклон кода. Если углы недоступны,
 * откатываемся на 4 угла `boundingBox` (прямоугольник), если нет и его - контур не рисуем.
 */
private fun Barcode.toQuad(frameWidth: Int, frameHeight: Int): BarcodeQuad? {
    val corners = cornerPoints
    if (corners != null && corners.size >= 4) {
        return BarcodeQuad(corners.take(4).toList(), frameWidth, frameHeight)
    }
    val box = boundingBox ?: return null
    return BarcodeQuad(
        listOf(
            android.graphics.Point(box.left, box.top),
            android.graphics.Point(box.right, box.top),
            android.graphics.Point(box.right, box.bottom),
            android.graphics.Point(box.left, box.bottom)
        ),
        frameWidth,
        frameHeight
    )
}
