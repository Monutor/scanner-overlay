package com.scanner.overlay.scanner

import androidx.compose.ui.geometry.Rect

data class BarcodeOverlayData(
    val boundingBox: Rect,
    val imageWidth: Int,
    val imageHeight: Int,
    val imageRotation: Int
)

sealed interface ScannerResult {
    data class Success(
        val barcode: String,
        val format: Int,
        val overlayData: BarcodeOverlayData? = null,
        val lookupResult: BarcodeLookupResult? = null
    ) : ScannerResult
    data class Error(val message: String) : ScannerResult
    data object Scanning : ScannerResult
}
