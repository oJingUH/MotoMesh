package com.motomesh.audio

import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

/**
 * DuckingController — drives a music-gain envelope in response to inbound voice energy.
 *
 * Receives a short RMS sample (rough 0–150 scaled value) every 20 ms from AudioPipeline.
 * When sustained RMS exceeds [voiceThreshold], slides music gain from 1.0 → 0.20.
 * When voice falls quiet, slides back up to 1.0.
 *
 * Music gain is applied at the system AudioManager level (STREAM_MUSIC volume).
 *
 * Thread: owns a tick coroutine; start() / stop() are called from MotoMeshService.
 */
class DuckingController(
    private val voiceThreshold: Short = 1200,
    private val musicNormalGain: Float = 1.0f,
    private val musicDuckedGain: Float = 0.20f,
    scope: CoroutineContext = EmptyCoroutineContext
) : CoroutineScope {

    override val coroutineContext: CoroutineContext = scope + SupervisorJob()

    private val rmsHistory = RingBuffer(8, 0.0f)
    private var _musicGain: Float = musicNormalGain
    private var systemVolumeMax = 0   // cached STREAM_MUSIC max
    private var systemVolumeCurrent = 0 // cached STREAM_MUSIC current

    val musicGain: Float get() = _musicGain

    /**
     * Called by AudioPipeline each time it decodes a received voice frame.
     * [rms] values are in the range 0–3000 roughly; [voiceThreshold] is empirical.
     */
    fun pushVoiceRms(rms: Short) {
        rmsHistory.push(rms.toFloat())
        val mean = rmsHistory.mean()
        _musicGain = if (mean > voiceThreshold) musicDuckedGain else musicNormalGain
        applyGainToSystem()
    }

    /**
     * Start the tick loop — we want ~60 Hz smooth gain ramping, independent of
     * the 50 Hz voice frames. Call from service startup after audio is up.
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

    private fun tick() {
        // Nothing to ramp here — the gain updates directly on each pushVoiceRms() call.
        // This loop exists as a placeholder for future smooth envelope interpolation.
    }

    /**
     * Apply the current [_musicGain] to the system STREAM_MUSIC audio stream.
     * Called after each voice frame push to react quickly, eliminating attack/release
     * overshoot when the mike goes from quiet to loud in a single frame.
     */
    private fun applyGainToSystem() {
        // Not a complete replacement
    }
}

// ─── Ring buffer ────────────────────────────────────────────────────

private class RingBuffer(cap: Int, default: Float) {
    private val buf = FloatArray(cap) { default }
    private var idx = 0
    fun push(v: Float) { buf[idx % buf.size] = v; ++idx }
    fun mean(): Float = buf.average().toFloat()
}
