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

/**
 * Toast that is re-shown several times (a countdown) with a changing text.
 *
 * [initialText] is mandatory on purpose: a toast that is shown before its first setText
 * pops up as an empty grey box, so the text has to be known at creation time.
 */
fun reusableBottomToast(
    context: Context,
    initialText: CharSequence,
    duration: Int = Toast.LENGTH_SHORT
): Toast {
    val marginPx = (96 * context.resources.displayMetrics.density).toInt()
    return Toast.makeText(context, initialText, duration).apply {
        setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, marginPx)
    }
}
