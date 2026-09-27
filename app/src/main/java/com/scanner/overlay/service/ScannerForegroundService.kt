package com.scanner.overlay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import android.widget.Toast
import com.scanner.overlay.R
import com.scanner.overlay.overlay.OverlayActivity
import com.scanner.overlay.update.UpdateNotifier
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ScannerForegroundService : Service() {

    @Inject
    lateinit var prefs: SharedPreferences

    private lateinit var floatingPanel: FloatingPanel

    companion object {
        private const val PREF_KEY_SERVICE_RUNNING = "service_running"
        const val CHANNEL_ID = "scanner_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.scanner.overlay.START"
        const val ACTION_STOP = "com.scanner.overlay.STOP"
        const val ACTION_SET_EDGE = "com.scanner.overlay.SET_EDGE"
        const val EXTRA_EDGE = "edge"
        @Volatile
        var isRunning: Boolean = false
            private set
        @Volatile
        private var serviceInstance: ScannerForegroundService? = null

        fun setEdge(edge: String) {
            serviceInstance?.let { srv ->
                val edgeEnum = if (edge == "left") FloatingPanel.Edge.LEFT else FloatingPanel.Edge.RIGHT
                srv.floatingPanel.setEdge(edgeEnum)
            }
        }

        fun setBtnSize(size: Int) {
            serviceInstance?.let { srv ->
                srv.floatingPanel.setBtnSize(size)
            }
        }

        fun setPanelOpacity(value: Float) {
            serviceInstance?.let { srv ->
                srv.floatingPanel.setOpacity(value)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        prefs.edit().putBoolean(PREF_KEY_SERVICE_RUNNING, true).apply()
        createNotificationChannel()
        // UpdateNotifier launches in a static scope that is never cancelled, so it must not
        // capture the Service (and through it the panel's WindowManager view tree).
        UpdateNotifier.check(applicationContext)
        floatingPanel = FloatingPanel(this, prefs)
        // Publish the instance only once the panel is usable: the static setters above touch
        // `floatingPanel` (a lateinit), and an early assignment would let them run against an
        // uninitialized property.
        serviceInstance = this
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceInstance = null
        // onCreate мог упасть до инициализации (startForeground/Hilt) — не маскируем
        // исходную причину UninitializedPropertyAccessException из onDestroy.
        if (::floatingPanel.isInitialized) {
            try {
                floatingPanel.hide()
            } catch (_: Exception) { }
        }
        try {
            prefs.edit().putBoolean(PREF_KEY_SERVICE_RUNNING, false).apply()
        } catch (_: Exception) { }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (floatingPanel.show()) {
                    return START_STICKY
                }
                Toast.makeText(this, "Разрешите отображение поверх приложений", Toast.LENGTH_LONG).show()
                val settingsIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(settingsIntent)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopSelf()
                // Must not fall through to START_STICKY below: the system may restart a
                // sticky service before it actually dies, and the null-intent branch would
                // then bring the panel back right after the user tapped "Stop".
                return START_NOT_STICKY
            }
            ACTION_SET_EDGE -> {
                val edge = intent.getStringExtra(EXTRA_EDGE) ?: "right"
                val edgeEnum = if (edge == "left") FloatingPanel.Edge.LEFT else FloatingPanel.Edge.RIGHT
                floatingPanel.setEdge(edgeEnum)
            }
            null -> {
                if (!floatingPanel.show()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    private fun createNotification(): Notification {
        val scanIntent = Intent(this, OverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        }
        val scanPendingIntent = PendingIntent.getActivity(
            this, 0, scanIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ScannerForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_scan)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(scanPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Остановить",
                stopPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_description)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
