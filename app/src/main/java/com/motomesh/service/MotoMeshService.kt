package com.motomesh

import android.Manifest
import android.Manifest.permission
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat

class MotoMeshService : Service() {

    companion object {
        const val CHANNEL_ID = "MotoMeshVoice"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.motomesh.ACTION_START"
        const val ACTION_STOP = "com.motomesh.ACTION_STOP"
        private const val WAKE_LOCK_TAG = "MotoMesh::WakeLock"
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForeground()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    private fun startForeground() {
        // Audio pipeline will be bound here when audio/MeshEngineInitialized
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MotoMesh")
            .setContentText("Voice mesh active — others can reach you")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        acquireWakeLock()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(10*60*1000L) // 10 min max, renewed by service while active
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MotoMesh Voice",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent notification for active LoRa voice mesh"
                setSound(null, null) // silent channel
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        // Shutdown audio pipeline, stop LoRa, close BLE
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
