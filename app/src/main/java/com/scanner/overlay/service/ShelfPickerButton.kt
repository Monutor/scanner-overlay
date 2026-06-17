package com.scanner.overlay.service

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageView
import com.scanner.overlay.R
import com.scanner.overlay.settings.ShelfPickerActivity

class ShelfPickerButton(
    private val context: Context,
    private val prefs: SharedPreferences
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val displayMetrics = context.resources.displayMetrics

    private val marginPx = (16 * displayMetrics.density).toInt()

    private var buttonSizeDp: Int
    private var buttonSizePx: Int
    private var mainButtonSizePx: Int
    private val defaultY: Int = (100 * displayMetrics.density).toInt()

    private var isAdded = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var lastTapTime = 0L
    private val positionSaveHandler = Handler(Looper.getMainLooper())
    private var savePositionRunnable: Runnable? = null

    private val button: ImageView
    private val params: WindowManager.LayoutParams

    init {
        buttonSizeDp = prefs.getInt(PREF_SIZE, DEFAULT_SIZE_DP)
        buttonSizePx = (buttonSizeDp * displayMetrics.density).toInt()
        mainButtonSizePx = (60 * displayMetrics.density).toInt()
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFFB8C00.toInt())
        }

        button = ImageView(context).apply {
            setImageResource(R.drawable.ic_shelf)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(0, 0, 0, 0)
            setColorFilter(android.graphics.Color.WHITE, PorterDuff.Mode.SRC_IN)
            background = bg
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = (6 * displayMetrics.density).toFloat()
                outlineProvider = ViewOutlineProvider.BACKGROUND
                clipToOutline = true
            }
            scaleX = 1f
            scaleY = 1f
        }

        params = WindowManager.LayoutParams(
            buttonSizePx,
            buttonSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resolveInitialX()
            y = resolveInitialY()
        }
    }

    private fun resolveInitialX(): Int {
        val stored = prefs.getInt(PREF_X, -1)
        if (stored >= 0) return stored
        val mainX = prefs.getInt(PREF_MAIN_X, -1)
        val fallbackMainX = displayMetrics.widthPixels - mainButtonSizePx - marginPx
        val mainLeftEdge = if (mainX >= 0) mainX else fallbackMainX
        return (mainLeftEdge - buttonSizePx - marginPx).coerceAtLeast(marginPx)
    }

    private fun resolveInitialY(): Int {
        val stored = prefs.getInt(PREF_Y, -1)
        if (stored >= 0) return stored
        val mainY = prefs.getInt(PREF_MAIN_Y, -1)
        return if (mainY >= 0) mainY else defaultY
    }

    fun show() {
        if (isAdded) return
        button.setOnTouchListener(touchListener)
        windowManager.addView(button, params)
        isAdded = true
    }

    fun hide() {
        if (!isAdded) return
        savePositionRunnable?.let { positionSaveHandler.removeCallbacks(it) }
        savePosition()
        button.setOnTouchListener(null)
        try {
            windowManager.removeView(button)
        } catch (_: IllegalArgumentException) {}
        isAdded = false
    }

    private val touchListener = View.OnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                if (!isDragging) {
                    val slop = ViewConfiguration.get(context).scaledTouchSlop
                    isDragging = Math.abs(dx) > slop || Math.abs(dy) > slop
                }
                if (isDragging) {
                    params.x = clampX((initialX + dx).toInt())
                    params.y = clampY((initialY + dy).toInt())
                    try {
                        windowManager.updateViewLayout(button, params)
                    } catch (_: Exception) {}
                    schedulePositionSave()
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime > 500L) {
                        lastTapTime = now
                        launchPicker()
                    }
                } else {
                    savePosition()
                }
                true
            }
            else -> false
        }
    }

    private fun clampX(x: Int): Int {
        val maxX = displayMetrics.widthPixels - buttonSizePx
        return x.coerceIn(0, maxX)
    }

    private fun clampY(y: Int): Int {
        val maxY = displayMetrics.heightPixels - buttonSizePx
        return y.coerceIn(0, maxY)
    }

    private fun launchPicker() {
        val intent = Intent(context, ShelfPickerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        }
        context.startActivity(intent)
    }

    private fun schedulePositionSave() {
        savePositionRunnable?.let { positionSaveHandler.removeCallbacks(it) }
        savePositionRunnable = Runnable { savePosition() }
        positionSaveHandler.postDelayed(savePositionRunnable!!, 2000L)
    }

    private fun savePosition() {
        prefs.edit()
            .putInt(PREF_X, params.x)
            .putInt(PREF_Y, params.y)
            .apply()
    }

    fun updateSize(sizeDp: Int) {
        if (sizeDp == buttonSizeDp) return
        buttonSizeDp = sizeDp
        buttonSizePx = (sizeDp * displayMetrics.density).toInt()
        prefs.edit().putInt(PREF_SIZE, sizeDp).apply()
        params.width = buttonSizePx
        params.height = buttonSizePx
        params.x = clampX(params.x)
        params.y = clampY(params.y)
        if (isAdded) {
            try {
                windowManager.updateViewLayout(button, params)
            } catch (_: Exception) {}
        }
    }

    companion object {
        private const val PREF_SIZE = "shelf_button_size_dp"
        private const val DEFAULT_SIZE_DP = 56
        private const val PREF_X = "shelf_button_x"
        private const val PREF_Y = "shelf_button_y"
        private const val PREF_MAIN_X = "floating_button_x"
        private const val PREF_MAIN_Y = "floating_button_y"
    }
}
