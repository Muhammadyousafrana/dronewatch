package com.example.droneclassification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps the process alive and the CPU awake while
 * live drone classification is running. This ensures audio recording and
 * ML inference continue even when the screen is off.
 *
 * The actual ML work is done by [DroneClassificationViewModel] — this service
 * only provides the Android lifecycle anchor (foreground notification + wake lock).
 */
class ClassificationService : Service() {

    companion object {
        private const val TAG = "ClassificationService"
        private const val CHANNEL_ID = "drone_scanner_channel"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "DroneClassification::InferenceWakeLock"

        fun start(context: Context) {
            val intent = Intent(context, ClassificationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ClassificationService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")

        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        acquireWakeLock()

        // If the system kills the service, don't restart automatically.
        // The user must re-tap "Start Scan".
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        Log.d(TAG, "Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Drone Scanner",
                NotificationManager.IMPORTANCE_LOW   // No sound, just persistent icon
            ).apply {
                description = "Shows when drone acoustic scanning is active"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        // Tapping the notification brings the user back to MainActivity
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Drone Scanner Active")
            .setContentText("Listening for drone signatures…")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now) // built-in mic icon
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    // ── Wake Lock ────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                // Timeout after 4 hours as a safety net to prevent infinite battery drain
                // if the user forgets to stop scanning.
                acquire(4 * 60 * 60 * 1000L)
            }
            Log.d(TAG, "Wake lock acquired")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }
}
