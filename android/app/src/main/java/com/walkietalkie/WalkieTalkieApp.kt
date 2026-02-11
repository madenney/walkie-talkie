package com.walkietalkie

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class WalkieTalkieApp : Application() {
    companion object {
        const val CHANNEL_ID = "walkie_talkie_service"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Walkie Talkie",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the voice connection active"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
