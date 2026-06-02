package com.scanner.overlay.settings

data class SewTestResult(
    val steps: List<StepStatus>,
    val inProgress: Boolean = false,
    val finished: Boolean = false,
    val errorMessage: String? = null
)

data class StepStatus(
    val name: String,
    val ok: Boolean,
    val message: String? = null
)
