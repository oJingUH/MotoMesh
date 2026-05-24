package com.motomesh.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.motomesh.R
import com.motomesh.cellular.CellularBridge
import com.motomesh.ui.MainActivity
import com.motomesh.mesh.MotoMeshEngine
import com.motomesh.mesh.MotoMeshEngine.TransportMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * MotoMeshService — foreground service that holds the voice pipeline and
 * (in future) LoRa BLE connection alive when the app is backgrounded.
 *
 * Android O+ requires any service started via startForegroundService() to call
 * startForeground(notificationId, notification) within ~5 seconds of creation.
 * Failure to do so triggers ForegroundServiceDidNotStartInTimeException which
 * kills the process. This class guarantees startForeground() is the very first
 * thing that happens inside onCreate().
 *
 * Loopback mode is carried as a VOLATILE flag crossed from the companion static
 * helper, and is also re-read from the restart Intent in onStartCommand() so
 * a process-recreated service resumes in the correct mode.
 */
class MotoMeshService : Service() {

    companion object {
        private const val TAG = "MotoMeshService"

        private const val NOTIF_CHANNEL_ID = "motomesh_channel"
        private const val NOTIF_ID = 1

        @Volatile
        private var transportMode: TransportMode = TransportMode.LOOPBACK

        fun start(context: Context, transport: TransportMode = TransportMode.LOOPBACK) {
            transportMode = transport
            val intent = Intent(context, MotoMeshService::class.java).apply {
                putExtra(EXTRA_TRANSPORT, transport)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun getTransport(): TransportMode = transportMode

        const val EXTRA_TRANSPORT = "extra_transport"
    }

    private val serviceScope = CoroutineScope(SupervisorJob())

    private var foregrounded = false

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate — permissions=${permissionsGranted()}")
    }

    private fun promoteForeground() {
        if (foregrounded) return
        foregrounded = true
        startInForeground()
        if (permissionsGranted()) {
            boot()
        } else {
            Log.w(TAG, "Permissions not yet granted — engine will be started when they arrive")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote to foreground asap — this is the guaranteed entry point for
        // a startForegroundService()-launched service (onCreate can be brief).
        promoteForeground()

        // Re-read loopback mode on restart so the engine resumes correctly
        // after a process recreation by the system.
        val mode = intent?.getStringExtra(EXTRA_TRANSPORT)?.let {
            TransportMode.valueOf(it)
        } ?: TransportMode.LOOPBACK
        if (mode != transportMode) {
            transportMode = mode
            Log.i(TAG, "Transport restored from restart Intent: $transportMode")
            // If engine is still alive, restart it with the correct mode
            MotoMeshEngine.stop()
            boot()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        MotoMeshEngine.stop()
        CellularBridge.close()
        releaseWake()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Notification / foreground ───────────────────────────────────

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "MotoMesh Active",
                NotificationManager.IMPORTANCE_LOW,  // LOW = silent, always visible
            ).apply {
                description = "MotoMesh voice service is running"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        PendingIntent.FLAG_IMMUTABLE else 0
        )

        val notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("MotoMesh voice service is running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIF_ID, notification)
    }

    // ─── Engine boot ─────────────────────────────────────────────────

    private fun boot() {
        Log.i(TAG, "Booting MotoMeshEngine — transport=$transportMode")
        MotoMeshEngine.start(this, serviceScope, transport = transportMode)
    }

    // ─── Permissions ─────────────────────────────────────────────────

    private fun permissionsGranted(): Boolean {
        val alwaysNeeded = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        for (p in alwaysNeeded) {
            if (ActivityCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Missing permission: $p")
                return false
            }
        }

        if (getTransport() != TransportMode.LOOPBACK) {
            val btPerms = listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
            )
            for (p in btPerms) {
                if (ActivityCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "Missing permission: $p")
                    return false
                }
            }
        }

        return true
    }

    // ─── Wake lock ───────────────────────────────────────────────────

    /* wakelock: stub for acquireWake() */

    private fun releaseWake() {
        // acquireWake() is a stub at this stage; releaseWake is a no-op match.
    }
}
