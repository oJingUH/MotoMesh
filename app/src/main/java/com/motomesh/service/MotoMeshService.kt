package com.motomesh.service

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import com.motomesh.mesh.LoRaDriver
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
        fun start(context: Context) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(Intent(context, MotoMeshService::class.java))
            } else {
                context.startService(Intent(context, MotoMeshService::class.java))
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob())
    private var audioMixer: com.motomesh.audio.AudioMixer? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate — permissions=${permissionsGranted()}")
        if (permissionsGranted()) {
            boot()
        }
    }

    private fun permissionsGranted(): Boolean {
        val needed = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        for (p in needed) {
            if (ActivityCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Missing permission: $p")
                return false
            }
        }
        return true
    }

    private fun boot() {
        Log.i(TAG, "Booting MotoMeshService — LoRa + audio pipeline")
        MotoMeshEngine.start(this, serviceScope)
        LoRaDriver.onEngineStart(serviceScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        MotoMeshEngine.stop()
        LoRaDriver.onEngineStop()
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
