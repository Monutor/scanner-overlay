package com.scanner.overlay

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ScannerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createSewResultChannel()
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

    companion object {
        const val SEW_RESULT_CHANNEL_ID = "sew_result_channel"
        const val SEW_RESULT_NOTIFICATION_ID = 1003
    }
}
