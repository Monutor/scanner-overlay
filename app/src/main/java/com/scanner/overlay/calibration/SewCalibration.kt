package com.scanner.overlay.calibration

import android.graphics.Point

data class SewCalibration(
    val targetPackage: String,
    val openModal: Point,
    val confirm: Point
) {
    /**
     * Both coordinates of both points must be valid.
     *
     * Each pair is written in a separate calibration step (SewCalibrationService.handleTap
     * writes `openModal` first and `confirm` only on the second tap), so a run cancelled
     * after step 1 leaves `confirm == (0,0)`. `||` would accept that as a finished
     * calibration and the auto-input would tap the screen corner; a half-written pair
     * (x == 0 but y != 0) must be rejected for the same reason.
     */
    val isCalibrated: Boolean
        get() = targetPackage.isNotBlank() &&
            openModal.x > 0 && openModal.y > 0 &&
            confirm.x > 0 && confirm.y > 0

    companion object {
        fun empty(): SewCalibration = SewCalibration(
            targetPackage = "",
            openModal = Point(0, 0),
            confirm = Point(0, 0)
        )
    }
}
