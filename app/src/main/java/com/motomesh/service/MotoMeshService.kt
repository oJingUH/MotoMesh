package com.motomesh.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import com.motomesh.mesh.MotoMeshEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * MotoMeshService — foreground service that holds the LoRa BLE connection
 * and audio pipeline alive even when the app is in the background.
 *
 * The service runs in its own process. BLE connection stays open here;
 * the UI activity binds to this service for state (node count, RSSI, etc).
 */
class MotoMeshService : android.app.Service() {

    companion object {
        private const val TAG = "MotoMeshService"
        private const val WAKE_LOCK_TAG = "MotoMesh::Wakelock"

        @Volatile
        private var loopbackMode: Boolean = false

        fun start(context: Context, loopback: Boolean = false) {
            loopbackMode = loopback
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(Intent(context, MotoMeshService::class.java))
            } else {
                context.startService(Intent(context, MotoMeshService::class.java))
            }
        }

        fun isLoopback(): Boolean = loopbackMode
    }

    private val serviceScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate — permissions=${permissionsGranted()}")
        if (permissionsGranted()) {
            boot()
        }
    }

    private fun permissionsGranted(): Boolean {
        // All modes need core perms
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

        // BT/BLE perms only required when LoRa BLE hardware is active
        if (!isLoopback()) {
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

    private fun boot() {
        Log.i(TAG, "Booting MotoMeshService — loopback=${isLoopback()}")
        MotoMeshEngine.start(this, serviceScope, loopback = isLoopback())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        MotoMeshEngine.stop()
        releaseWake()
        super.onDestroy()
    }

    private fun acquireWake() {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).also {
            it.setReferenceCounted(false)
            it.acquire(10 * 60 * 1000L)
        }
    }

    private fun releaseWake() {
        // Release if held — no-op when stub
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
