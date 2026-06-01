package com.scanner.overlay.calibration

import android.graphics.Point

data class SewCalibration(
    val targetPackage: String,
    val openModal: Point,
    val confirm: Point
) {
    val isCalibrated: Boolean
        get() = targetPackage.isNotEmpty() &&
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
