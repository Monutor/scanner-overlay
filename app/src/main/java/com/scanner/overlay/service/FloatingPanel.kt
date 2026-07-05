package com.scanner.overlay.service

import android.animation.ValueAnimator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.scanner.overlay.R
import com.scanner.overlay.overlay.OverlayActivity
import com.scanner.overlay.settings.ArticleLookupActivity
import com.scanner.overlay.settings.ShelfPickerActivity

class FloatingPanel(
    private val context: Context,
    private val prefs: SharedPreferences
) {
    enum class Edge { LEFT, RIGHT }

    companion object {
        private const val PREF_OPEN = "panel_open"
        private const val PREF_Y = "panel_panel_y"
        private const val PREF_EDGE = "panel_edge"
        private const val PANEL_W_DP = 240
        private const val TAB_W_DP = 32
        private const val TAB_H_DP = 80
        private const val BTN_SIZE_DP = 56
        private const val PAD_DP = 20
        private const val GAP_DP = 12
        private const val EDGE_MARGIN_DP = 4
        private const val ANIM_MS = 350L
    }

    private var edge: Edge
    private var isOpen = true
    private var isAnimating = false
    private var lastTapTime = 0L
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragTouchX = 0f
    private var dragTouchY = 0f
    private var isDragging = false
    private var rootView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    init {
        val saved = prefs.getString(PREF_EDGE, "right") ?: "right"
        edge = if (saved == "left") Edge.LEFT else Edge.RIGHT
    }

    fun show() {
        if (rootView != null) return
        isOpen = prefs.getBoolean(PREF_OPEN, true)
        val savedEdge = prefs.getString(PREF_EDGE, "right") ?: "right"
        edge = if (savedEdge == "left") Edge.LEFT else Edge.RIGHT
        val sy = prefs.getInt(PREF_Y, defaultY())
        val root = buildPanel()
        val totalW = dp(PANEL_W_DP + TAB_W_DP)
        params = WindowManager.LayoutParams(
            totalW, ViewGroup.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (isOpen) calcOpenX() else calcClosedX()
            y = sy
        }
        rootView = root
        wm.addView(root, params!!)
    }

    fun hide() {
        rootView?.let { wm.removeView(it) }
        rootView = null
        params = null
    }

    private fun buildPanel(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                dp(PANEL_W_DP + TAB_W_DP),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val tab = buildTab()
        val body = buildPanelBody()
        if (edge == Edge.RIGHT) {
            root.addView(tab)
            root.addView(body)
        } else {
            root.addView(body)
            root.addView(tab)
        }
        root.setOnTouchListener { _, event -> onPanelTouch(event) }
        return root
    }

    private fun buildTab(): View {
        val tab = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                dp(TAB_W_DP), ViewGroup.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER
            val r = dp(10).toFloat()
            background = GradientDrawable().apply {
                setShape(GradientDrawable.RECTANGLE)
                setColor(Color.parseColor("#F21E1E3C"))
                setStroke(dp(1), Color.parseColor("#14FFFFFF"))
                cornerRadii = if (edge == Edge.RIGHT)
                    floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                else
                    floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            }
        }
        val arrow = TextView(context).apply {
            text = if (isOpen) "\u25B6" else "\u25C0"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        tab.addView(arrow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        tab.setOnClickListener { toggle() }
        return tab
    }

    private fun buildPanelBody(): LinearLayout {
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                dp(PANEL_W_DP), ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(PAD_DP), dp(PAD_DP), dp(PAD_DP), dp(PAD_DP))
            val r = dp(20).toFloat()
            background = GradientDrawable().apply {
                setShape(GradientDrawable.RECTANGLE)
                setColor(Color.parseColor("#F21E1E3C"))
                setStroke(dp(1), Color.parseColor("#14FFFFFF"))
                cornerRadii = if (edge == Edge.RIGHT)
                    floatArrayOf(r, r, 0f, 0f, r, r, 0f, 0f)
                else
                    floatArrayOf(0f, 0f, r, r, 0f, 0f, r, r)
            }
        }

        data class Btn(val icon: Int, val color: String, val label: String)
        val buttons = listOf(
            Btn(R.drawable.ic_launcher_foreground, "#1976D2", "Сканер"),
            Btn(R.drawable.ic_shelf, "#FB8C00", "Полка"),
            Btn(R.drawable.ic_article, "#388E3C", "Артикул"),
            Btn(R.drawable.ic_article_barcode, "#7B1FA2", "ШК")
        )

        buttons.forEachIndexed { i, btn ->
            val btnLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }

            val img = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(BTN_SIZE_DP), dp(BTN_SIZE_DP))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setImageResource(btn.icon)
                setColorFilter(Color.WHITE)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    elevation = 6f
                }
                background = GradientDrawable().apply {
                    setShape(GradientDrawable.OVAL)
                    setColor(Color.parseColor(btn.color))
                }
                setOnClickListener { launch(i) }
            }
            btnLayout.addView(img)

            val lbl = TextView(context).apply {
                text = btn.label
                textSize = 10f
                gravity = Gravity.CENTER_HORIZONTAL
                setTextColor(Color.parseColor("#999999"))
            }
            btnLayout.addView(lbl)

            body.addView(btnLayout)
            if (i < buttons.size - 1) {
                val spacer = View(context)
                spacer.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(GAP_DP)
                )
                body.addView(spacer)
            }
        }

        return body
    }

    private fun toggle() {
        if (isAnimating) return
        isOpen = !isOpen
        prefs.edit().putBoolean(PREF_OPEN, isOpen).apply()
        val startX = params!!.x
        val endX = if (isOpen) calcOpenX() else calcClosedX()
        isAnimating = true
        ValueAnimator.ofFloat(startX.toFloat(), endX.toFloat()).apply {
            duration = ANIM_MS
            interpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)
            addUpdateListener { a ->
                params!!.x = (a.animatedValue as Float).toInt()
                wm.updateViewLayout(rootView!!, params!!)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    isAnimating = false
                    updateArrow()
                }
            })
        }.start()
    }

    private fun onPanelTouch(event: MotionEvent): Boolean {
        val p = params ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = p.x
                dragStartY = p.y
                dragTouchX = event.rawX
                dragTouchY = event.rawY
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = (event.rawY - dragTouchY).toInt()
                if (!isDragging) isDragging = Math.abs(dy) > 10
                if (isDragging) {
                    p.y = (dragStartY + dy).coerceIn(0, maxY())
                    wm.updateViewLayout(rootView!!, p)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    prefs.edit().putInt(PREF_Y, p.y).apply()
                } else {
                    toggle()
                }
            }
        }
        return true
    }

    private fun updateArrow() {
        val root = rootView as? LinearLayout ?: return
        val tabIndex = if (edge == Edge.RIGHT) 0 else 1
        if (root.childCount <= tabIndex) return
        val tab = root.getChildAt(tabIndex) as? LinearLayout ?: return
        (tab.getChildAt(0) as? TextView)?.text = if (isOpen) "\u25B6" else "\u25C0"
    }

    fun setEdge(newEdge: Edge) {
        edge = newEdge
        prefs.edit().putString(PREF_EDGE, newEdge.name.lowercase()).apply()
        rebuild()
    }

    fun getEdge(): Edge = edge

    private fun rebuild() {
        rootView?.let { wm.removeView(it) }
        rootView = null
        params = null
        show()
    }

    private fun launch(idx: Int) {
        val now = System.currentTimeMillis()
        if (now - lastTapTime < 500) return
        lastTapTime = now
        val intent = when (idx) {
            0 -> Intent(context, OverlayActivity::class.java)
            1 -> Intent(context, ShelfPickerActivity::class.java)
            2 -> Intent(context, ArticleLookupActivity::class.java)
            3 -> Intent(context, ArticleLookupActivity::class.java).putExtra("extra_barcode_focus", true)
            else -> return
        }.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    private fun calcOpenX(): Int = when (edge) {
        Edge.RIGHT -> scrW - dp(PANEL_W_DP + TAB_W_DP) - dp(EDGE_MARGIN_DP)
        Edge.LEFT -> dp(EDGE_MARGIN_DP)
    }

    private fun calcClosedX(): Int = when (edge) {
        Edge.RIGHT -> scrW - dp(TAB_W_DP) - dp(EDGE_MARGIN_DP)
        Edge.LEFT -> -dp(PANEL_W_DP) + dp(EDGE_MARGIN_DP)
    }

    private fun dp(v: Number): Int = (v.toFloat() * density).toInt()
    private val scrW: Int get() = context.resources.displayMetrics.widthPixels
    private val density: Float get() = context.resources.displayMetrics.density
    private fun defaultY(): Int = context.resources.displayMetrics.heightPixels / 3
    private fun maxY(): Int = context.resources.displayMetrics.heightPixels - dp(TAB_H_DP)
}
