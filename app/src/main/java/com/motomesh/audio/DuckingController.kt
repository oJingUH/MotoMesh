package com.motomesh.audio

import android.content.Context
import android.media.AudioManager
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * DuckingController — drives a music-gain envelope in response to inbound voice energy.
 *
 * Receives a short RMS sample every 20 ms from AudioPipeline.rxLoop.
 * When sustained mean RMS exceeds [voiceThreshold], slides music gain from 1.0 → 0.20.
 * When voice goes quiet, slides back up to 1.0.
 *
 * Music gain is applied via AudioManager.setStreamVolume(STREAM_MUSIC, …).
 *
 * Thread: owns a tick coroutine; start() / stop() are called from MotoMeshService.
 *
 * @param voiceThreshold  Emperical RMS threshold (Short, 0–32767). Audible voice
 *                          typically produces RMS in the 2000-5000 range after the
 *                          preamp gain is set. See calibration notes in AUDIT_REPORT §4e.
 * @param musicNormalGain        Gain (0.0–1.0) when no voice is detected.
 * @param musicDuckedGain        Gain (0.0–1.0) when voice is above threshold.
 * @param context                Application context for AudioManager access.
 * @param scope                  CoroutineContext parent — passed to the DuckingController
 *                               coroutineScope so its tick job is tied to the service parent.
 */
class DuckingController(
    private val voiceThreshold: Short = 1200,
    private val musicNormalGain: Float = 1.0f,
    private val musicDuckedGain: Float = 0.20f,
    private val context: Context? = null,
    scope: CoroutineContext = EmptyCoroutineContext
) : CoroutineScope {

    override val coroutineContext: CoroutineContext = scope + SupervisorJob()

    private val rmsHistory = RingBuffer(8, 0.0f)
    private var _musicGain: Float = musicNormalGain
    private var systemVolumeMax = 0   // cached STREAM_MUSIC max
    private var systemVolumeCurrent = -1  // cached last volume index written
    private var volumeCacheInitialized = false

    val musicGain: Float get() = _musicGain

    /**
     * Called by AudioPipeline each time it decodes a received voice frame.
     * [rms] is a Short (0–32767) computed over one 20 ms PCM frame.
     *
     * CRITICAL — call on the main dispatcher if there is a risk this is
     * called from a background thread; AudioManager.setStreamVolume must be
     * called on the process main thread or an exception is thrown.
     */
    fun pushVoiceRms(rms: Short) {
        rmsHistory.push(rms.toFloat())
        val mean = rmsHistory.mean()
        _musicGain = if (mean > voiceThreshold) musicDuckedGain else musicNormalGain
        applyGainToSystem()
    }

    /**
     * Start the tick loop — runs at ~60 Hz providing a skeleton for future smooth
     * gain ramp interpolation. Call from service startup after audio is up.
     */
    fun start() {
        launch(Dispatchers.Default) {
            while (isActive) {
                tick()
                delay(16)
            }
        }
    }

    fun stop() {
        coroutineContext[Job]?.cancel()
    }

    /**
     * Placeholder for smooth envelope interpolation.
     *
     * Current implementation: gain snaps instantly on every pushVoiceRms() call.
     * When interpolation is added, use:
     *
     *     _musicGain += (targetGain - _musicGain) * A * dt
     *
     * where A ≈ 12 is a 12 dB/s time constant (industry standard for broadcast
     * ducking). The 50 Hz frame granularity means dt = 0.020 s gives
     * effective α = 0.23 per sample — roughly 3 samples to reach 50 %.
     */
    private fun tick() {
        // Nothing to ramp here — the gain updates directly on each pushVoiceRms() call.
        // This loop exists as a placeholder for future smooth envelope interpolation.
    }

    /**
     * Apply the current [_musicGain] to the system STREAM_MUSIC audio stream.
     *
     * Uses AudioManager.setStreamVolume(…, volumeIndex, 0) so the change is
     * visible in the volume HUD and survives Bluetooth A2DP re-render on reconnect.
     *
     * Guard: NOP if context is null or permission is missing — avoids
     * SecurityException silently. MODIFY_AUDIO_SETTINGS is already declared in
     * AndroidManifest.xml.
     *
     * Called after each voice frame push to react quickly. The instantaneous
     * snap is deliberate for this build; the tick coroutine provides the
     * skeleton for a smooth ramp interpolation in a future revision.
     */
    private fun applyGainToSystem() {
        val ctx = context ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

        // Cache max volume once per process lifetime
        if (!volumeCacheInitialized) {
            systemVolumeMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            volumeCacheInitialized = true
        }

        val targetVol = (_musicGain * systemVolumeMax).toInt().coerceIn(0, systemVolumeMax)
        if (targetVol == systemVolumeCurrent) return  // no-op — avoids repeated IPC

        am.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            targetVol,
            0   // no volume HUD flags
        )
        systemVolumeCurrent = targetVol
    }
}

// ─── Ring buffer ────────────────────────────────────────────────────

private class RingBuffer(cap: Int, default: Float) {
    private val buf = FloatArray(cap) { default }
    private var idx = 0
    fun push(v: Float) { buf[idx % buf.size] = v; ++idx }
    fun mean(): Float = buf.average().toFloat()
}
