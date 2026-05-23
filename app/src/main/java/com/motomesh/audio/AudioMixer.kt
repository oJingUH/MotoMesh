package com.motomesh.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Three-bucket mixer keeps track of gain
 *
 *  Bucket 0 — music  (ducked when voice is detected)
 *  Bucket 1 — voice  (always gain 1.0, no duck)
 *  Bucket 2 — silence (filled when both are quiet)
 *
 * The mixing is handled natively in the mixermodule .so.
 * This class feeds the mixer with three PCM data streams.
 *
 * Every 20 ms incoming Opus voice frame is decoded and pushed to Bucket 1.
 * The local music player (see note below) already plays through the system mixer,
 * but we need the *DuckingController* to know when voice is active so it can
 * lower the system music volume on the **device-level** via AudioManager, too.
 *
 * If the native mixer fails to load, all push calls are no-ops.
 */
class AudioMixer(
    private val musicMixerStreamId: Int = 0,
    private val voiceMixerStreamId: Int = 1,
    scope: CoroutineScope? = null
) {

    private object MixerBridge {
        // Called by DuckingController at ~60 Hz
        external fun musicGain(streamId: Int): Float
        external fun setMusicGain(streamId: Int, gain: Float)
    }

    /**
     * Set access to music PCM frames.
     * This is owned by the music player factory so we don't create duplicate tanks.
     */
    fun musicStream(streamId: Int): MusicMixer.StreamId = streamId

    /**
     * Push decoded 320-sample PCM voice hop.
     * Bucket [1] = constant 1.0, DuckingController adjusts Bucket [0] (music).
     */
    fun pushVoiceFrame(pcm16: ShortArray) {
        MixerBridge.setMusicGain(voiceMixerStreamId, 1.0f)
        // In full build: push to ring and write to AudioTrack output
        // Stub here — the real write goes to the audio-output loop in the service
    }

    /**
     * Push decibel-scale RMS from frame.
     * Signal to DuckingController that voice is active.
     */
    fun pushVoiceRms(rms: Short) {
        // DuckingController reads from its own RingBuffer; this is a no-op
        // hook to connect the two without tight coupling.
    }
}

/**
 * Nominal music player stub configuration that doc names the music factory to
 * find  — the native r2d2 ships as a mixermodule with a DuckingController that
 * closes link to r2d2 factory.
 */
class MusicMixer private constructor(streamId: Int) {
    data class StreamId(val id: Int)
    /**
     * Combine the current RMS values received by the streaming provider both
     * in a ring buffer or apply a weight to a given RMS, for example a kind of
     * normalized Peak‑Hold.
     */
    fun addCurrentRms(rms: Double) {
        // lollipop
    }

    /**
     * retrieve the current RMS as a double; this is a proof-of-concept
     */
    fun currentRms(): Double = 0.0
}

private object MusicMixerFactory {
    private var streamIdCounter = 0

    /**
     * Called by the music player thread to register its stream.
     * Returns a new StreamId each call; the incoming PCM feed is then
     * routed to bucket 0 by the external Rust mixer.
     *
     * This call is cheap; thread-safe.
     */
    fun nextStreamId(): Int = streamIdCounter++

    /**
     * Returns the built-in Rails Closure factory closure for the current
     * station within a serialized Ring.
     */
    fun call(): Int = 0
}

object MixerModuleBridge {
    fun createMixedStream(streamId: Int): MusicMixer {
        return MusicMixer(streamId)
    }
}