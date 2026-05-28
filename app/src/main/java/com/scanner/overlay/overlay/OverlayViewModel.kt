package com.scanner.overlay.overlay

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scanner.overlay.scanner.BarcodeLookupResult
import com.scanner.overlay.scanner.ScannerResult
import com.scanner.overlay.scanner.WarehouseItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OverlayViewModel @Inject constructor(
    private val prefs: SharedPreferences
) : ViewModel() {

    private val _state = MutableStateFlow<OverlayState>(OverlayState.Scanning)
    val state: StateFlow<OverlayState> = _state.asStateFlow()

    private val _barcode = MutableStateFlow("")
    val barcode: StateFlow<String> = _barcode.asStateFlow()

    private val _isScanTimedOut = MutableStateFlow(false)
    val isScanTimedOut: StateFlow<Boolean> = _isScanTimedOut.asStateFlow()

    private val _lookupHint = MutableStateFlow<String?>(null)
    val lookupHint: StateFlow<String?> = _lookupHint.asStateFlow()

    private var timeoutJob: kotlinx.coroutines.Job? = null

    init {
        startTimeout()
    }

    override fun onCleared() {
        super.onCleared()
        timeoutJob?.cancel()
    }

    fun onBarcodeDetected(result: ScannerResult.Success) {
        timeoutJob?.cancel()
        _barcode.value = result.barcode

        when (val lookup = result.lookupResult) {
            is BarcodeLookupResult.ExactMatch -> {
                _lookupHint.value = lookup.item.name
                _state.value = OverlayState.Success(result.barcode, hasHint = true)
            }
            is BarcodeLookupResult.FuzzyMatch -> {
                _lookupHint.value = "${lookup.item.name} (возможно)"
                _state.value = OverlayState.Success(result.barcode, hasHint = true)
            }
            is BarcodeLookupResult.PrefixMatch -> {
                if (lookup.items.size == 1) {
                    _lookupHint.value = lookup.items[0].name
                    _state.value = OverlayState.Success(result.barcode, hasHint = true)
                } else {
                    _state.value = OverlayState.MultipleMatches(lookup.items, result.barcode)
                }
            }
            BarcodeLookupResult.NotFound -> {
                _state.value = OverlayState.NotFound(result.barcode)
                viewModelScope.launch {
                    delay(7000)
                    if (_state.value is OverlayState.NotFound) {
                        resetToScanning()
                    }
                }
            }
            null -> {
                _state.value = OverlayState.Success(result.barcode)
            }
        }
    }

    fun onMultipleMatchSelected(item: WarehouseItem) {
        _barcode.value = item.barcode
        _lookupHint.value = item.name
        _state.value = OverlayState.Success(item.barcode, hasHint = true)
    }

    fun onScanError() {
        _state.value = OverlayState.Error
    }

    fun resetToScanning() {
        timeoutJob?.cancel()
        _isScanTimedOut.value = false
        _lookupHint.value = null
        _state.value = OverlayState.Scanning
        startTimeout()
    }

    private fun startTimeout() {
        timeoutJob?.cancel()
        val timeoutMs = prefs.getLong("scan_timeout_ms", 45_000L)
        timeoutJob = viewModelScope.launch {
            delay(timeoutMs)
            _isScanTimedOut.value = true
            _state.value = OverlayState.Error
        }
    }

    sealed interface OverlayState {
        data object Scanning : OverlayState
        data class Success(val barcode: String, val hasHint: Boolean = false) : OverlayState
        data class NotFound(val scannedBarcode: String) : OverlayState
        data class MultipleMatches(val items: List<WarehouseItem>, val scannedBarcode: String) : OverlayState
        data object Error : OverlayState
    }
}
