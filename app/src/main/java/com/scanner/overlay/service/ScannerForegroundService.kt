package com.scanner.overlay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.scanner.overlay.R
import com.scanner.overlay.overlay.OverlayActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ScannerForegroundService : Service() {

    @Inject
    lateinit var prefs: SharedPreferences

    private lateinit var floatingButton: FloatingScanButton

    companion object {
        private const val PREF_KEY_SERVICE_RUNNING = "service_running"
        const val CHANNEL_ID = "scanner_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.scanner.overlay.START"
        const val ACTION_STOP = "com.scanner.overlay.STOP"
        @Volatile
        var isRunning: Boolean = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        prefs.edit().putBoolean(PREF_KEY_SERVICE_RUNNING, true).apply()
        createNotificationChannel()
        floatingButton = FloatingScanButton(this, prefs)
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        floatingButton.hide()
        prefs.edit().putBoolean(PREF_KEY_SERVICE_RUNNING, false).apply()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    val settingsIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(settingsIntent)
                    stopSelf()
                    return START_NOT_STICKY
                }
                floatingButton.show()
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    private fun start() {
        floatingButton.show()
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
                R.drawable.ic_scan,
                getString(R.string.scan_action),
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
