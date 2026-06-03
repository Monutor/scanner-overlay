package com.scanner.overlay.util

import android.content.Context
import android.view.Gravity
import android.widget.Toast

fun Context.toastAtBottom(message: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
    val marginPx = (96 * resources.displayMetrics.density).toInt()
    Toast.makeText(this, message, duration).apply {
        setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, marginPx)
        show()
    }
}

fun reusableBottomToast(context: Context, duration: Int = Toast.LENGTH_SHORT): Toast {
    val marginPx = (96 * context.resources.displayMetrics.density).toInt()
    return Toast.makeText(context, "", duration).apply {
        setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, marginPx)
    }
}
