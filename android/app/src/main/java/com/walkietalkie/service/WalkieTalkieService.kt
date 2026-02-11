package com.walkietalkie.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.walkietalkie.MainActivity
import com.walkietalkie.WalkieTalkieApp

private const val TAG = "WTService"
private const val NOTIFICATION_ID = 1

class WalkieTalkieService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): WalkieTalkieService = this@WalkieTalkieService
    }

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()
        Log.i(TAG, "Service started")
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, WalkieTalkieApp.CHANNEL_ID)
            .setContentTitle("Walkie Talkie")
            .setContentText("Connected to Claude")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "WalkieTalkie::VoiceConnection"
            ).apply {
                acquire(60 * 60 * 1000L) // 1 hour max
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
            wakeLock = null
        }
    }
}
