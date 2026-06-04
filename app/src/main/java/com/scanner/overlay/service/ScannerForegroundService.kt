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
import com.scanner.overlay.R
import com.scanner.overlay.overlay.OverlayActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ScannerForegroundService : Service() {

    @Inject
    lateinit var prefs: SharedPreferences

    private lateinit var floatingButton: FloatingScanButton
    private lateinit var shelfPickerButton: ShelfPickerButton
    private lateinit var articleLookupButton: ArticleLookupButton

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var shelfObserverJob: Job? = null
    private var articleObserverJob: Job? = null

    companion object {
        private const val PREF_KEY_SERVICE_RUNNING = "service_running"
        const val CHANNEL_ID = "scanner_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.scanner.overlay.START"
        const val ACTION_STOP = "com.scanner.overlay.STOP"
        const val PREF_KEY_SHELF_PICKER_ENABLED = "shelf_picker_enabled"
        const val PREF_KEY_ARTICLE_LOOKUP_ENABLED = "article_lookup_enabled"
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
        shelfPickerButton = ShelfPickerButton(this, prefs)
        articleLookupButton = ArticleLookupButton(this, prefs)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        startShelfPickerObserver()
        startArticleLookupObserver()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        floatingButton.hide()
        shelfPickerButton.hide()
        articleLookupButton.hide()
        prefs.edit().putBoolean(PREF_KEY_SERVICE_RUNNING, false).apply()
        serviceScope.cancel()
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
            null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                    floatingButton.show()
                }
            }
        }
        return START_STICKY
    }

    private fun startShelfPickerObserver() {
        shelfObserverJob?.cancel()
        shelfObserverJob = serviceScope.launch {
            shelfPickerEnabledFlow().collectLatest { enabled ->
                if (enabled) shelfPickerButton.show() else shelfPickerButton.hide()
            }
        }
    }

    private fun startArticleLookupObserver() {
        articleObserverJob?.cancel()
        articleObserverJob = serviceScope.launch {
            articleLookupEnabledFlow().collectLatest { enabled ->
                if (enabled) articleLookupButton.show() else articleLookupButton.hide()
            }
        }
    }

    private fun shelfPickerEnabledFlow() = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PREF_KEY_SHELF_PICKER_ENABLED) {
                trySend(prefs.getBoolean(PREF_KEY_SHELF_PICKER_ENABLED, false))
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(prefs.getBoolean(PREF_KEY_SHELF_PICKER_ENABLED, false))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    private fun articleLookupEnabledFlow() = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PREF_KEY_ARTICLE_LOOKUP_ENABLED) {
                trySend(prefs.getBoolean(PREF_KEY_ARTICLE_LOOKUP_ENABLED, false))
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(prefs.getBoolean(PREF_KEY_ARTICLE_LOOKUP_ENABLED, false))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

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
