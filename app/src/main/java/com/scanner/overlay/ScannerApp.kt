package com.scanner.overlay

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.scanner.overlay.update.UpdateNotifier
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ScannerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createSewResultChannel()
        createUpdateChannel()
        UpdateNotifier.startPeriodicCheck(this)
    }

    private fun createSewResultChannel() {
        val channel = NotificationChannel(
            SEW_RESULT_CHANNEL_ID,
            "Результаты SEW",
            NotificationManager.IMPORTANCE_HIGH
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createUpdateChannel() {
        val channel = NotificationChannel(
            UpdateNotifier.UPDATE_CHANNEL_ID,
            "Обновления приложения",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Уведомления о новых версиях"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val SEW_RESULT_CHANNEL_ID = "sew_result_channel"
        const val SEW_RESULT_NOTIFICATION_ID = 1003
    }
}
