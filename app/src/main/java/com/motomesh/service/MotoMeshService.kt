package com.motomesh.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.annotation.SuppressLint
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
import com.motomesh.audio.DuckingController
import com.motomesh.audio.AudioPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.motomesh.mesh.NodeTable

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
                putExtra(EXTRA_TRANSPORT, transport.name)
            }
            context.startForegroundService(intent)
        }

        fun getTransport(): TransportMode = transportMode

        const val EXTRA_TRANSPORT: String = "extra_transport"
        const val ACTION_RELOAD_AUDIO: String = "com.motomesh.action.RELOAD_AUDIO"
    }

    private val serviceScope = CoroutineScope(SupervisorJob())

    private var foregrounded = false
    private var audioPipeline: AudioPipeline? = null
    private var duckingController: DuckingController? = null

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
        // Check for audio-reload signal from SettingsActivity
        if (intent?.action == ACTION_RELOAD_AUDIO) {
            reloadAudioSettings()
            return START_STICKY
        }
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
        audioPipeline?.stop()   // stops txLoop + rxLoop before releasing resources
        audioPipeline = null
        duckingController = null
        releaseWake()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Notification / foreground ───────────────────────────────────

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID,
            "MotoMesh Active",
            NotificationManager.IMPORTANCE_LOW,  // LOW = silent, always visible
        ).apply {
            description = "MotoMesh voice service is running"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
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

    /** Update the persistent notification with current transport mode and rider count. */
    private fun startNotificationUpdater() {
        val ctx: Context = this@MotoMeshService
        serviceScope.launch {
            NodeTable.nodeFlow.collectLatest { nodes ->
                val modeLabel = when (transportMode) {
                    TransportMode.LOOPBACK -> "Loopback"
                    TransportMode.LORA -> "LoRa"
                    TransportMode.CELLULAR -> "Cellular"
                }
                val channel = if (transportMode == TransportMode.LORA)
                    ctx.getSharedPreferences("moto_settings", Context.MODE_PRIVATE).getInt("channel", 0)
                else 0
                val channelTag = if (transportMode == TransportMode.LORA) " Ch$channel" else ""
                val riderCount = nodes.count { it.isAlive }
                val text = if (riderCount == 0) "$modeLabel$channelTag — idle"
                    else "$modeLabel$channelTag — $riderCount rider${if (riderCount != 1) "s" else ""}"

                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val launchIntent = Intent(ctx, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pi = PendingIntent.getActivity(
                    ctx, 0, launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val notification = NotificationCompat.Builder(ctx, NOTIF_CHANNEL_ID)
                    .setContentTitle(ctx.getString(R.string.app_name))
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build()
                nm.notify(NOTIF_ID, notification)
            }
        }
    }

    // ─── Engine boot ─────────────────────────────────────────────────

    private fun boot() {
        Log.i(TAG, "Booting MotoMeshEngine — transport=$transportMode")
        MotoMeshEngine.start(this, serviceScope, transport = transportMode)

        // DuckingController: scope = serviceScope.coroutineContext (not serviceScope itself,
        // which is a CoroutineScope, not a CoroutineContext); context = this Service.
        // Read user audio prefs from moto_settings SharedPreferences.
        val audioPrefs = getSharedPreferences("moto_settings", MODE_PRIVATE)
        val voxThreshold = audioPrefs.getInt("vox_threshold", 1200).toShort().coerceIn(800, 8000)
        val duckDepthPct = audioPrefs.getInt("duck_depth_pct", 80).coerceIn(0, 90)
        val duckGain = (100 - duckDepthPct) / 100f  // 80% → 0.20, 0% → 1.0
        duckingController = DuckingController(
            voiceThreshold = voxThreshold,
            musicDuckedGain = duckGain,
            context = this,
            scope  = serviceScope.coroutineContext
        )

        // AudioPipeline: callback bridges rxLoop voice RMS into DuckingController.
        // Application context is safe here — AudioRecord / AudioTrack are created
        // inside OpusCodec.buildAudioRecord/buildAudioTrack, both called with this
        // application context from start().
        audioPipeline = AudioPipeline(AudioPipeline.Config()) { rms ->
            duckingController?.pushVoiceRms(rms)
        }
        audioPipeline?.start()

        // Live notification: transport mode + rider count updates reactively
        startNotificationUpdater()
    }

    /** Re-read audio prefs and recreate DuckingController without restarting the service. */
    private fun reloadAudioSettings() {
        Log.i(TAG, "reloadAudioSettings — re-reading prefs from SettingsActivity")
        duckingController?.stop()
        val audioPrefs = getSharedPreferences("moto_settings", MODE_PRIVATE)
        val voxThreshold = audioPrefs.getInt("vox_threshold", 1200).toShort().coerceIn(800, 8000)
        val duckDepthPct = audioPrefs.getInt("duck_depth_pct", 80).coerceIn(0, 90)
        val duckGain = (100 - duckDepthPct) / 100f
        duckingController = DuckingController(
            voiceThreshold = voxThreshold,
            musicDuckedGain = duckGain,
            context = this,
            scope  = serviceScope.coroutineContext
        )
        Log.i(TAG, "Audio settings reloaded: voxThreshold=$voxThreshold duckGain=$duckGain")
    }

    // ─── Permissions ─────────────────────────────────────────────────

    @SuppressLint("InlinedApi")  // BLUETOOTH_CONNECT/SCAN API31 + POST_NOTIFICATIONS API33: runtime version-checked; minSdk=29 intentional
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
            // RYLR993 BLE path: connect + scan are the two Bluetooth operations
            val btPerms = listOf(
                Manifest.permission.BLUETOOTH_SCAN,
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
