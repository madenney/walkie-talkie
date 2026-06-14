package com.walkietalkie.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.walkietalkie.MainActivity
import com.walkietalkie.WalkieTalkieApp

private const val TAG = "WTService"
private const val NOTIFICATION_ID = 1

class WalkieTalkieService : Service() {

    companion object {
        var instance: WalkieTalkieService? = null
            private set
    }

    inner class LocalBinder : Binder() {
        fun getService(): WalkieTalkieService = this@WalkieTalkieService
    }

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentStatus: String = "Connected to Claude"
    // Live server-side counts: how many Claude sessions are running, and how many
    // are blocked waiting on an approval from the user.
    private var currentCount: Int = 0
    private var currentNeedsYou: Int = 0

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()
        Log.i(TAG, "Service started")
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        releaseWakeLock()
        // Pull the notification down ourselves. Some OEMs (e.g. Samsung) leave the
        // ongoing foreground-service notification posted after the service dies, so
        // stopService alone isn't enough — explicitly remove it.
        stopForeground(STOP_FOREGROUND_REMOVE)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    fun updateNotification(status: String, sessionCount: Int = currentCount, needsYou: Int = currentNeedsYou) {
        if (status == currentStatus && sessionCount == currentCount && needsYou == currentNeedsYou) return
        currentStatus = status
        currentCount = sessionCount
        currentNeedsYou = needsYou
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val count = currentCount
        val title = when {
            count <= 0 -> "Walkie Talkie"
            count == 1 -> "1 Claude session"
            else -> "$count Claude sessions"
        }
        val text = if (currentNeedsYou > 0) {
            "⚠️ $currentNeedsYou need approval · $currentStatus"
        } else {
            currentStatus
        }

        // The status-bar icon itself carries the number, so the count is glanceable
        // without pulling down the shade. Reliable across OEMs (unlike the launcher
        // badge number). Falls back to the mic icon when nothing is running.
        val smallIcon = if (count > 0) {
            Icon.createWithBitmap(numberBitmap(count))
        } else {
            Icon.createWithResource(this, android.R.drawable.ic_btn_speak_now)
        }

        return Notification.Builder(this, WalkieTalkieApp.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(smallIcon)
            // Best-effort numeric launcher badge (OEM-dependent; the status-bar
            // icon above is the reliable number).
            .setNumber(count)
            // Red accent => the notification + badge show red while connected.
            .setColor(0xFFFF0000.toInt())
            .setColorized(true)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    /** Render the session count as a white glyph for the status-bar small icon.
     * Bitmap icons aren't theme-tinted by the framework (unlike vector/alpha
     * resources), so this renders solid white — visible on the dark status bar
     * that's the norm. The count is also in the shade title and launcher badge,
     * so this is a glanceable extra, not the only place the number appears. */
    private fun numberBitmap(n: Int): Bitmap {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val label = if (n > 9) "9+" else n.toString()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = if (n > 9) size * 0.62f else size * 0.85f
        }
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(label, size / 2f, y, paint)
        return bmp
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "WalkieTalkie::VoiceConnection"
            ).apply {
                acquire()
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
