package com.scanner.overlay.overlay

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scanner.overlay.scanner.ScannerResult
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

    private var timeoutJob = viewModelScope.launch {
        val timeoutMs = prefs.getLong("scan_timeout_ms", 45_000L)
        delay(timeoutMs)
        _isScanTimedOut.value = true
        _state.value = OverlayState.Error
    }

    override fun onCleared() {
        super.onCleared()
        timeoutJob.cancel()
    }

    fun onBarcodeDetected(result: ScannerResult.Success) {
        timeoutJob.cancel()
        _barcode.value = result.barcode
        _state.value = OverlayState.Success(result.barcode)
    }

    fun onScanError() {
        _state.value = OverlayState.Error
    }

    sealed interface OverlayState {
        data object Scanning : OverlayState
        data class Success(val barcode: String) : OverlayState
        data object Error : OverlayState
    }
}
