package com.scanner.overlay.update

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.app.NotificationCompat
import com.scanner.overlay.BuildConfig
import com.scanner.overlay.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object UpdateNotifier {
    private const val PREF_LAST_CHECK = "update_last_check_ms"
    private const val PREF_PENDING_VC = "pending_update_vc"
    private const val PREF_PENDING_VN = "pending_update_vn"
    private const val PREF_PENDING_URL = "pending_update_url"
    private const val PREF_PENDING_NOTES = "pending_update_notes"
    const val UPDATE_CHANNEL_ID = "update_channel"
    const val UPDATE_NOTIFICATION_ID = 1004
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun getPendingUpdate(prefs: SharedPreferences): UpdateInfo? {
        val vc = prefs.getInt(PREF_PENDING_VC, 0)
        if (vc <= BuildConfig.VERSION_CODE) return null
        return UpdateInfo(
            versionCode = vc,
            versionName = prefs.getString(PREF_PENDING_VN, "") ?: "",
            downloadUrl = prefs.getString(PREF_PENDING_URL, "") ?: "",
            releaseNotes = prefs.getString(PREF_PENDING_NOTES, "") ?: ""
        )
    }

    fun savePendingUpdate(prefs: SharedPreferences, info: UpdateInfo) {
        prefs.edit()
            .putInt(PREF_PENDING_VC, info.versionCode)
            .putString(PREF_PENDING_VN, info.versionName)
            .putString(PREF_PENDING_URL, info.downloadUrl)
            .putString(PREF_PENDING_NOTES, info.releaseNotes)
            .apply()
    }

    fun clearPendingUpdate(prefs: SharedPreferences) {
        prefs.edit()
            .remove(PREF_PENDING_VC)
            .remove(PREF_PENDING_VN)
            .remove(PREF_PENDING_URL)
            .remove(PREF_PENDING_NOTES)
            .apply()
    }

    fun check(context: Context) {
        val prefs = context.getSharedPreferences("scanner_prefs", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastCheck = prefs.getLong(PREF_LAST_CHECK, 0)
        if (now - lastCheck < CHECK_INTERVAL_MS) return
        prefs.edit().putLong(PREF_LAST_CHECK, now).apply()

        scope.launch {
            try {
                when (val result = AutoUpdateManager.checkForUpdate()) {
                    is UpdateResult.Available -> {
                        savePendingUpdate(prefs, result.info)
                        showNotification(context, result.info)
                    }
                    is UpdateResult.UpToDate -> clearPendingUpdate(prefs)
                    is UpdateResult.Error -> { }
                }
            } catch (_: Exception) { }
        }
    }

    fun startPeriodicCheck(context: Context) {
        val prefs = context.getSharedPreferences("scanner_prefs", Context.MODE_PRIVATE)
        scope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val lastCheck = prefs.getLong(PREF_LAST_CHECK, 0)
                if (now - lastCheck >= CHECK_INTERVAL_MS) {
                    prefs.edit().putLong(PREF_LAST_CHECK, now).apply()
                    try {
                        when (val result = AutoUpdateManager.checkForUpdate()) {
                            is UpdateResult.Available -> {
                                savePendingUpdate(prefs, result.info)
                                showNotification(context, result.info)
                            }
                            is UpdateResult.UpToDate -> clearPendingUpdate(prefs)
                            is UpdateResult.Error -> { }
                        }
                    } catch (_: Exception) { }
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    private fun showNotification(context: Context, info: UpdateInfo) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setContentTitle("Доступно обновление ${info.versionName}")
            .setContentText("Нажмите, чтобы открыть настройки и установить")
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(UPDATE_NOTIFICATION_ID, notification)
    }
}
