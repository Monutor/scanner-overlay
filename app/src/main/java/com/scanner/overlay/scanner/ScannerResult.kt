package com.scanner.overlay.scanner

sealed interface ScannerResult {
    data class Success(val barcode: String, val format: Int) : ScannerResult
    data class Error(val message: String) : ScannerResult
}
