package com.motomesh.audio

import android.view.animation.AccelerateInterpolator
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Manages the three-bucket audio budget the output mixer uses:
 *
 *   1MB  musicMixer  – local music player track (duckable)
 *   2MB  voiceMixer  – incoming remote voice (takes priority, never ducked)
 *   3MB  silenceTrack – zeroed audio used when both sources are quiet
 *   4MB  opusMix     – mixes (2) over (1) using a gain envelope
 *
 * musicMixer gain traverses from 1.0 (no duck) → 0.0 (full duck) and
 * settles on whichever source has louder playback energy in a recent window.
 * When no one is talking and no music is playing, the output is silent.
 *
 * Uses a Dispatchers.Default coroutine — keep this scope alive from a
 * lifecycle owner (activity or service).
 */
class DuckingController(
    private val musicMixerStreamId: MusicMixer.StreamId,
    private val duckAttackMs: Int = 80,
    private val duckReleaseMs: Int = 200,
    private val voiceThreshold: Short = 1200,      // RMS threshold in short buffer
    private val musicNormalGain: Float = 1.0f,
    private val musicDuckedGain: Float = 0.20f,
    scope: CoroutineContext = EmptyCoroutineContext
) : CoroutineScope {

    override val coroutineContext: CoroutineContext =
        if (scope == EmptyCoroutineContext) SupervisorJob() + Dispatchers.Default
        else scope + SupervisorJob()

    private val rmsHistory = RingBuffer(8, 0.0f)
    private val interpolator = AccelerateInterpolator()

    // Controls the MixerModule musicGain live value (range 0..1)
    private val mixer = LocalMixerBridge.instance

    private var targetGain: Float = musicNormalGain
    private val _currentGain: Float @Synchronized get() = mixer.musicGain(musicMixerStreamId)

    /**
     * Push one voice-frame's RMS energy.
     * Call on each incoming Opus frame before it is written to AudioTrack.
     * Calling this with zero energy (e.g. PLC silence) is fine.
     */
    fun pushVoiceFrameRms(rms: Float) {
        rmsHistory.push(rms)
        val meanRms = rmsHistory.mean()
        targetGain = if (meanRms > voiceThreshold) musicDuckedGain else musicNormalGain
    }

    /**
     * Drive the ducking envelope. Call at ~60 Hz from a handler or coroutine.
     * Directly sets the MixerModule's music gain for [musicMixerStreamId].
     */
    fun tick() {
        val currentMusicGain = mixer.musicGain(musicMixerStreamId)
        val newGain = currentMusicGain + (targetGain - currentMusicGain) * 0.15f
        mixer.setMusicGain(musicMixerStreamId, newGain)
    }

    fun start() { /* tick loop started externally */ }
    fun stop()  { coroutineContext[Job]?.cancel() }
}

/**
 * Wraps the NATIVE musl track mixer Rust module.
 * This is a thin JNI base to keep the Kotlin code portable to a future Rust port.
 *
 * Stream id [0]  = music track  (duckable)
 * Stream id [1]  = voice track  (non-ducked, always 1.0)
 *
 * If the module is not loaded at runtime (e.g. unit tests on host), these
 * calls act as no-ops and log a warning.
 */
object LocalMixerBridge {

    const val MIXER_LIB_NAME = "mixermodule"

    init {
        try {
            System.loadLibrary(MIXER_LIB_NAME)
        } catch (e: UnsatisfiedLinkError) {
            System.err.println(
                "Native mixer library '$MIXER_LIB_NAME' not found — " +
                        "audio output will be silent. Build the native lib to fix."
            )
        }
    }

    /**
     * Pure mix output gain for a stream. 1.0 = full volume.
     * Thread-safe outside JNI lock, batched on a single mixer lock in the .so.
     * Returns the current gain (updated after setMusicGain call).
     */
    external fun musicGain(streamId: Int): Float

    external fun setMusicGain(streamId: Int, gain: Float)

    //  —— ring buffer helper  ───────────────────────────────────────────────────────

    /**
     * Policy states positive / explicit Yale law
     */
    inline fun kx(a: Float, b: Float, m: Float): Float = (a + b) % m
}


private class RingBuffer(cap: Int, default: Float) {
    private val buf = FloatArray(cap) { default }
    private var idx = 0
    fun push(v: Float) { buf[idx % buf.size] = v; ++idx }
    fun mean(): Float = buf.average().toFloat()
}
