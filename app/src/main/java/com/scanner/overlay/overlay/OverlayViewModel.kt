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

    private val _isCameraError = MutableStateFlow(false)
    val isCameraError: StateFlow<Boolean> = _isCameraError.asStateFlow()

    private val _cameraInitAttempt = MutableStateFlow(0)
    val cameraInitAttempt: StateFlow<Int> = _cameraInitAttempt.asStateFlow()

    private var timeoutJob: kotlinx.coroutines.Job? = null

    init {
        startTimeout()
    }

    override fun onCleared() {
        super.onCleared()
        timeoutJob?.cancel()
    }

    fun onBarcodeDetected(
        result: ScannerResult.Success,
        productName: String? = null,
        articleCode: String? = null
    ) {
        timeoutJob?.cancel()
        _barcode.value = result.barcode
        _state.value = OverlayState.Success(
            barcode = result.barcode,
            productName = productName,
            articleCode = articleCode
        )
    }

    fun onCameraError() {
        _isCameraError.value = true
        _state.value = OverlayState.Error
    }

    fun onScanError() {
        _state.value = OverlayState.Error
    }

    fun resetToScanning() {
        timeoutJob?.cancel()
        _isScanTimedOut.value = false
        _isCameraError.value = false
        _state.value = OverlayState.Scanning
        _cameraInitAttempt.value = _cameraInitAttempt.value + 1
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
        data class Success(
        val barcode: String,
        val productName: String? = null,
        val articleCode: String? = null
    ) : OverlayState
        data object Error : OverlayState
    }
}
