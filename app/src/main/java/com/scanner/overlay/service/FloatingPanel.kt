package com.scanner.overlay.service

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.border
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scanner.overlay.R
import com.scanner.overlay.overlay.OverlayActivity
import com.scanner.overlay.settings.ArticleBarcodeActivity
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
        const val PREF_BTN_SIZE = "panel_btn_size"
        const val PREF_OPACITY = "panel_opacity"
        private const val TAB_W_DP = 32
        private const val TAB_H_DP = 72
        private const val PAD_DP = 20
        private const val GAP_DP = 12
        private const val EDGE_MARGIN_DP = 0
    }

    private var edge: Edge = Edge.RIGHT
    private var isOpen by mutableStateOf(prefs.getBoolean(PREF_OPEN, true))
    private var panelY by mutableStateOf(prefs.getInt(PREF_Y, defaultY()).toFloat())
    private var btnSizeDp = prefs.getInt(PREF_BTN_SIZE, 56).dp
    private var opacity = prefs.getFloat(PREF_OPACITY, 1f)

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density: Float get() = context.resources.displayMetrics.density

    private val bodyWidthDp: Int
        get() {
            val d = btnSizeDp.value.toDouble()
            return (d + PAD_DP * 2).toInt()
        }

    private var composeView: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null

    fun show() {
        if (composeView != null) return
        isOpen = prefs.getBoolean(PREF_OPEN, true)
        panelY = prefs.getInt(PREF_Y, defaultY()).toFloat()
        btnSizeDp = prefs.getInt(PREF_BTN_SIZE, 56).dp
        opacity = prefs.getFloat(PREF_OPACITY, 1f)
        val savedEdge: String = prefs.getString(PREF_EDGE, "right") ?: "right"
        edge = if (savedEdge == "left") Edge.LEFT else Edge.RIGHT

        val root = ComposeView(context).apply {
            setId(resources.getIdentifier("floating_panel_root", null, context.packageName))
            setContent {
                ScannerOverlayTheme {
                    PanelContent(
                        isOpen = isOpen,
                        onToggle = { toggle() },
                        edge = edge,
                        btnSizeDp = btnSizeDp
                    )
                }
            }
        }

        val totalW: Int = bodyWidthDp + dp(TAB_W_DP)
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
            y = panelY.toInt().coerceIn(0, maxY())
        }

        root.alpha = opacity
        composeView = root
        try {
            wm.addView(root, params!!)
        } catch (_: Exception) {
            composeView = null
            params = null
        }
    }

    fun hide() {
        composeView?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        composeView = null
        params = null
    }

    private fun toggle() {
        isOpen = !isOpen
        prefs.edit().putBoolean(PREF_OPEN, isOpen).apply()
        val startX = params!!.x.toFloat()
        val endX = if (isOpen) calcOpenX().toFloat() else calcClosedX().toFloat()
        val root = composeView ?: return
        var anim: android.animation.ValueAnimator? = null
        android.animation.ValueAnimator.ofFloat(startX, endX).apply {
            duration = 350L
            interpolator = android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f)
            addUpdateListener { a ->
                params!!.x = (a.animatedValue as Float).toInt()
                try { wm.updateViewLayout(root, params!!) } catch (_: Exception) {}
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    anim = null
                }
            })
        }.start()
    }

    fun setEdge(newEdge: Edge) {
        edge = newEdge
        prefs.edit().putString(PREF_EDGE, newEdge.name.lowercase()).apply()
        rebuild()
    }

    fun setBtnSize(size: Int) {
        btnSizeDp = size.dp
        prefs.edit().putInt(PREF_BTN_SIZE, size).apply()
        rebuild()
    }

    fun setOpacity(value: Float) {
        opacity = value
        prefs.edit().putFloat(PREF_OPACITY, value).apply()
        composeView?.alpha = value
    }

    fun getEdge(): Edge = edge

    private fun rebuild() {
        composeView?.let { wm.removeView(it) }
        composeView = null
        params = null
        show()
    }

    private fun launch(idx: Int) {
        val intent = when (idx) {
            0 -> Intent(context, OverlayActivity::class.java)
            1 -> Intent(context, ShelfPickerActivity::class.java)
            2 -> Intent(context, ArticleLookupActivity::class.java)
            3 -> Intent(context, ArticleBarcodeActivity::class.java)
            else -> return
        }.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        context.startActivity(intent)
    }

    private fun calcOpenX(): Int = when (edge) {
        Edge.RIGHT -> scrW - bodyWidthDp - dp(TAB_W_DP) - dp(EDGE_MARGIN_DP)
        Edge.LEFT -> dp(EDGE_MARGIN_DP)
    }

    private fun calcClosedX(): Int = when (edge) {
        Edge.RIGHT -> scrW - dp(TAB_W_DP) - dp(EDGE_MARGIN_DP)
        Edge.LEFT -> -bodyWidthDp + dp(EDGE_MARGIN_DP)
    }

    private fun dp(v: Number): Int = (v.toFloat() * density).toInt()
    private val scrW: Int get() = context.resources.displayMetrics.widthPixels
    private fun defaultY(): Int = context.resources.displayMetrics.heightPixels / 3
    private fun maxY(): Int = context.resources.displayMetrics.heightPixels - dp(TAB_H_DP)
}

// ─── Compose UI ──────────────────────────────────────────────────────────────

private val PanelBg = Color(0xF21E1E3C)
private val PanelStroke = Color(0x14FFFFFF)
private val LabelColor = Color(0x999999)

private data class PanelBtn(val icon: ImageVector, val color: Color, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScannerOverlayTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}

@Composable
private fun PanelContent(
    isOpen: Boolean,
    onToggle: () -> Unit,
    edge: FloatingPanel.Edge,
    btnSizeDp: androidx.compose.ui.unit.Dp
) {
    val tabW = 32.dp
    val tabH = 72.dp
    val pad = 20.dp
    val gap = 12.dp
    val radius = 20.dp

    val bodyOffsetX = animateDpAsState(
        targetValue = if (isOpen) 0.dp else -(btnSizeDp + pad * 2),
        label = "panelBodyOffset"
    )

    val buttons = listOf(
        PanelBtn(Icons.Filled.ArrowForward, Color(0xFF1976D2), "Сканер"),
        PanelBtn(Icons.Filled.ArrowBack, Color(0xFFFB8C00), "Полка"),
        PanelBtn(Icons.Filled.Search, Color(0xFF388E3C), "Артикул"),
        PanelBtn(Icons.Filled.QrCode, Color(0xFF7B1FA2), "ШК")
    )

    Row(modifier = Modifier.fillMaxWidth()) {
        // Tab (arrow)
        val tabCornerRadii = when (edge) {
            FloatingPanel.Edge.RIGHT -> RoundedCornerShape(topStart = radius, bottomStart = radius)
            FloatingPanel.Edge.LEFT -> RoundedCornerShape(topEnd = radius, bottomEnd = radius)
        }
        Box(
            modifier = Modifier
                .width(tabW)
                .height(tabH)
                .clip(tabCornerRadii)
                .background(PanelBg)
                .border(0.5.dp, PanelStroke, tabCornerRadii)
                .clickable { onToggle() },
            contentAlignment = Alignment.Center
        ) {
            val arrow = if (isOpen == (edge == FloatingPanel.Edge.LEFT)) Icons.Filled.ArrowForward else Icons.Filled.ArrowBack
            Icon(arrow, contentDescription = "Toggle", tint = Color.White, modifier = Modifier.size(18.dp))
        }

        // Body
        val bodyCornerRadii = when (edge) {
            FloatingPanel.Edge.RIGHT -> RoundedCornerShape(topEnd = radius, bottomEnd = radius)
            FloatingPanel.Edge.LEFT -> RoundedCornerShape(topStart = radius, bottomStart = radius)
        }
        Box(modifier = Modifier.offset(x = bodyOffsetX.value)) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(btnSizeDp + pad * 2)
                    .padding(pad)
                    .clip(bodyCornerRadii)
                    .background(PanelBg)
                    .border(0.5.dp, PanelStroke, bodyCornerRadii)
            ) {
                buttons.forEachIndexed { i, btn ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(btnSizeDp)
                                .clip(CircleShape)
                                .background(btn.color),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                btn.icon,
                                contentDescription = btn.label,
                                tint = Color.White,
                                modifier = Modifier.size(btnSizeDp * 0.5f)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            btn.label,
                            fontSize = 7.sp,
                            color = LabelColor,
                            textAlign = TextAlign.Center
                        )
                    }
                    if (i < buttons.size - 1) {
                        Spacer(modifier = Modifier.height(gap))
                    }
                }
            }
        }
    }
}


