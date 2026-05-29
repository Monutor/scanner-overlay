package com.scanner.overlay.scanner

sealed interface ScannerResult {
    data class Success(
        val barcode: String,
        val format: Int,
        val lookupResult: BarcodeLookupResult? = null
    ) : ScannerResult
    data class Error(val message: String) : ScannerResult
}
