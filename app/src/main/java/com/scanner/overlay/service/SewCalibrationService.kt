package com.scanner.overlay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import com.scanner.overlay.R
import com.scanner.overlay.MainActivity

class SewCalibrationService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: FrameLayout
    private lateinit var countdownToast: Toast
    private var prefs: SharedPreferences? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val addOverlayRunnable = Runnable { addOverlay() }
    private val countdownRunnable = object : Runnable {
        private var remaining = (STARTUP_DELAY_MS / 1000L).toInt()
        override fun run() {
            if (remaining <= 0) return
            countdownToast.setText("Оверлей через $remaining сек")
            countdownToast.cancel()
            countdownToast.show()
            remaining--
            mainHandler.postDelayed(this, 1000L)
        }
    }
    private var capturedOpenModal = false
    private var openModalX = 0
    private var openModalY = 0
    private var capturedPackage: String = ""

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        prefs = getSharedPreferences("scanner_prefs", MODE_PRIVATE)
        prefs?.edit()?.putBoolean(PREF_KEY_AWAITING, true)?.apply()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        countdownToast = Toast.makeText(this, "", Toast.LENGTH_SHORT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(stepIndex = STEP_PREPARING),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(stepIndex = STEP_PREPARING))
        }
        mainHandler.post(countdownRunnable)
        mainHandler.postDelayed(addOverlayRunnable, STARTUP_DELAY_MS)
    }

    private fun addOverlay() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        overlayView = FrameLayout(this).apply {
            setBackgroundColor(0x33000000)
            isClickable = false
            isFocusable = false
            setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_UP) {
                    val x = ev.rawX.toInt()
                    val y = ev.rawY.toInt()
                    handleTap(x, y)
                }
                true
            }
        }

        windowManager.addView(overlayView, params)
        updateNotification(stepIndex = 0)
        mainHandler.post {
            Toast.makeText(
                this,
                "Оверлей активен — тапните на «Ручной ввод»",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun handleTap(x: Int, y: Int) {
        val knownPkg = prefs?.getString("sew_target_package", "") ?: ""
        if (knownPkg.isEmpty()) {
            mainHandler.post {
                Toast.makeText(
                    this,
                    "Сначала выберите приложение SEW в настройках",
                    Toast.LENGTH_LONG
                ).show()
            }
            mainHandler.postDelayed({ stopSelf() }, 1500L)
            return
        }
        if (!capturedOpenModal) {
            capturedPackage = knownPkg
            openModalX = x
            openModalY = y
            capturedOpenModal = true
            prefs?.edit()
                ?.putInt("sew_open_modal_x", x)
                ?.putInt("sew_open_modal_y", y)
                ?.apply()
            updateNotification(stepIndex = 1)
            mainHandler.post {
                Toast.makeText(
                    this,
                    "Шаг 1 сохранён. Откройте модалку вручную и нажмите на «Готово».",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            prefs?.edit()
                ?.putInt("sew_confirm_x", x)
                ?.putInt("sew_confirm_y", y)
                ?.apply()
            mainHandler.post {
                Toast.makeText(
                    this,
                    "Калибровка сохранена: $capturedPackage (open=$openModalX,$openModalY confirm=$x,$y)",
                    Toast.LENGTH_SHORT
                ).show()
            }
            mainHandler.postDelayed({ stopSelf() }, 1500L)
        }
    }

    private fun updateNotification(stepIndex: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(stepIndex))
    }

    private fun buildNotification(stepIndex: Int): Notification {
        val channelId = CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Калибровка SEW",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, SewCalibrationService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val text = when (stepIndex) {
            STEP_PREPARING -> "Перейдите в SEW-приложение. Оверлей появится через 5 сек."
            0 -> "Шаг 1/2: нажмите на «Ручной ввод»"
            else -> "Шаг 2/2: откройте модалку и нажмите на «Готово»"
        }
        return Notification.Builder(this, channelId)
            .setContentTitle("Калибровка SEW")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_scan)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(R.drawable.ic_scan, "Отмена", stopIntent)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            mainHandler.removeCallbacks(countdownRunnable)
            mainHandler.removeCallbacks(addOverlayRunnable)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        mainHandler.removeCallbacks(countdownRunnable)
        mainHandler.removeCallbacks(addOverlayRunnable)
        if (::countdownToast.isInitialized) {
            try { countdownToast.cancel() } catch (_: Exception) {}
        }
        prefs?.edit()?.putBoolean(PREF_KEY_AWAITING, false)?.apply()
        if (::overlayView.isInitialized) {
            try {
                windowManager.removeView(overlayView)
            } catch (_: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "sew_calibration_channel"
        private const val STARTUP_DELAY_MS = 5000L
        private const val STEP_PREPARING = -1
        const val ACTION_STOP = "com.scanner.overlay.service.STOP_SEW_CALIBRATION"
        const val PREF_KEY_AWAITING = "sew_awaiting_calibration"

        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
