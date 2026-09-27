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
    private const val PREF_PENDING_SHA = "pending_update_sha"
    const val UPDATE_CHANNEL_ID = "update_channel"
    const val UPDATE_NOTIFICATION_ID = 1004
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun getPendingUpdate(prefs: SharedPreferences): UpdateInfo? {
        val vc = prefs.getInt(PREF_PENDING_VC, 0)
        if (vc <= BuildConfig.VERSION_CODE) return null
        val url = prefs.getString(PREF_PENDING_URL, "") ?: ""
        if (url.isBlank()) {
            // Битый pending без URL скачивания — не отдаём и зачищаем,
            // иначе кнопка «Установить» упадёт в URL("") → MalformedURLException.
            clearPendingUpdate(prefs)
            return null
        }
        return UpdateInfo(
            versionCode = vc,
            versionName = prefs.getString(PREF_PENDING_VN, "") ?: "",
            downloadUrl = url,
            releaseNotes = prefs.getString(PREF_PENDING_NOTES, "") ?: "",
            sha256 = prefs.getString(PREF_PENDING_SHA, "") ?: ""
        )
    }

    fun savePendingUpdate(prefs: SharedPreferences, info: UpdateInfo) {
        prefs.edit()
            .putInt(PREF_PENDING_VC, info.versionCode)
            .putString(PREF_PENDING_VN, info.versionName)
            .putString(PREF_PENDING_URL, info.downloadUrl)
            .putString(PREF_PENDING_NOTES, info.releaseNotes)
            .putString(PREF_PENDING_SHA, info.sha256)
            .apply()
    }

    fun clearPendingUpdate(prefs: SharedPreferences) {
        prefs.edit()
            .remove(PREF_PENDING_VC)
            .remove(PREF_PENDING_VN)
            .remove(PREF_PENDING_URL)
            .remove(PREF_PENDING_NOTES)
            .remove(PREF_PENDING_SHA)
            .apply()
    }

    fun check(context: Context) {
        val prefs = context.getSharedPreferences("scanner_prefs", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!claimCheckSlot(prefs, now)) return
        scope.launch { performCheck(prefs, context, now) }
    }

    fun startPeriodicCheck(context: Context) {
        val prefs = context.getSharedPreferences("scanner_prefs", Context.MODE_PRIVATE)
        scope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                if (claimCheckSlot(prefs, now)) performCheck(prefs, context, now)
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    /**
     * Reserve the 6-hour slot. Read and write share one lock so concurrent [check] calls and the
     * periodic loop cannot both pass the gate; the write uses commit() because it has to be
     * durable before the request goes out (a process death right after the check would
     * otherwise re-trigger an immediate one). commit() is an fsync, so this must never run on
     * the main thread - it is only called from [scope].
     */
    private fun claimCheckSlot(prefs: SharedPreferences, now: Long): Boolean =
        synchronized(prefs) {
            val lastCheck = prefs.getLong(PREF_LAST_CHECK, 0)
            if (now - lastCheck < CHECK_INTERVAL_MS) return false
            prefs.edit().putLong(PREF_LAST_CHECK, now).commit()
            true
        }

    private suspend fun performCheck(prefs: SharedPreferences, context: Context, claimedAt: Long) {
        try {
            when (val result = AutoUpdateManager.checkForUpdate()) {
                is UpdateResult.Available -> {
                    savePendingUpdate(prefs, result.info)
                    showNotification(context, result.info)
                }
                is UpdateResult.UpToDate -> clearPendingUpdate(prefs)
                is UpdateResult.Error -> releaseCheckSlot(prefs, claimedAt)
            }
        } catch (_: Exception) {
            releaseCheckSlot(prefs, claimedAt)
        }
    }

    /**
     * A failed check (no network, rate limit, malformed update.json) must not consume the slot:
     * otherwise the user sees no update notification for the next 6 hours. The stored value is
     * only rewound when it is still the one we wrote, so a check that started later keeps its slot.
     */
    private fun releaseCheckSlot(prefs: SharedPreferences, claimedAt: Long) {
        synchronized(prefs) {
            if (prefs.getLong(PREF_LAST_CHECK, 0) == claimedAt) {
                prefs.edit().putLong(PREF_LAST_CHECK, 0L).apply()
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

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.notify(UPDATE_NOTIFICATION_ID, notification)
    }
}
