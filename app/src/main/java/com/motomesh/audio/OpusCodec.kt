package com.motomesh.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.motomesh.BuildConfig

/**
 * Opus codec wrappers around Concentus.
 *
 * Design invariant: 20 ms frames, mono, 16 kHz, 20 Kbps CBR.
 * These are the only parameters that fit reliably in a RYLR993 packet.
 *
 * Concentus NuAutoRelease and its Android binding (concentus-android)
 * expose Encoder / Decoder Java classes. This file is the contract surface
 * for the rest of the app — swap the lib without touching mesh or UI code.
 */
object OpusCodec {

    const val SAMPLE_RATE = 16_000      // Hz
    const val CHANNELS = 1              // mono
    const val FRAME_MS = 20             // ms per Opus frame
    const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000   // 320
    const val BIT_RATE_BPS = 20_000     // 20 Kbps
    const val MAX_PAYLOAD = 240         // RYLR993 max packet bytes, trimmed to 200 below

    private var encoder: com.concentus.NuAutoRelease? = null
    private var decoder: com.concentus.NuAutoRelease? = null
    private var encoderInitialized = false
    private var decoderInitialized = false

    // ─── Encoder ─────────────────────────────────────────────────

    /**
     * Encode 320 raw 16-bit mono samples (20 ms) into a single Opus frame.
     * Returns the encoded payload bytes, or null if the sample count is wrong.
     *
     * Thread-safety: call from the Tx coroutine context, single-producer.
     */
    fun encodeFrame(pcm16: ShortArray): ByteArray? {
        if (!encoderInitialized) return null
        if (pcm16.size != FRAME_SAMPLES) {
            // Caller passed wrong frame size. Frame exactly 320 samples expected.
            return null
        }
        return encoder!!.encode(pcm16, 0, FRAME_SAMPLES)
    }

    fun initEncoder() {
        if (encoderInitialized) return
        encoder = com.concentus.NuAutoRelease(BIT_RATE_BPS, SAMPLE_RATE, CHANNELS)
        encoder!!.setVBR(true)
        encoder!!.setComplexity(8)
        encoder!!.setInBandFEC(true)
        encoder!!.setDTX(false)                      // always transmit, no DTX
        encoder!!.setBufferSize(MAX_PAYLOAD)
        encoderInitialized = true
    }

    fun releaseEncoder() {
        encoder?.close()
        encoder = null
        encoderInitialized = false
    }

    // ─── Decoder ─────────────────────────────────────────────────

    /**
     * Decode an Opus frame into 320 raw 16-bit mono samples.
     * Returns null on PLC — caller should output silence for lost frames.
     */
    fun decodeFrame(opusBytes: ByteArray): ShortArray {
        if (!decoderInitialized) return ShortArray(FRAME_SAMPLES) { 0 }
        val out = ShortArray(FRAME_SAMPLES)
        val consumed = decoder!!.decode(opusBytes, out, 0)
        if (consumed <= 0) {
            // Packet loss or decode error — return zero signal (silence)
            // Caller-provided jitter buffer provides frame timing, PLC fill-in
            return ShortArray(FRAME_SAMPLES) { 0 }
        }
        return out.copyOfRange(0, consumed)
    }

    fun initDecoder() {
        if (decoderInitialized) return
        decoder = com.concentus.NuAutoRelease(SAMPLE_RATE, CHANNELS)
        decoderInitialized = true
    }

    fun releaseDecoder() {
        decoder?.close()
        decoder = null
        decoderInitialized = false
    }

    // ─── AudioRecord / AudioTrack helpers ─────────────────────────

    fun micRecordBufferSize(): Int =
        AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

    fun speakerPlaybackBufferSize(): Int =
        AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

    /**
     * Build a configured AudioRecord for mic capture.
     * Buffer size is sized for 3 Opus frames (60 ms) of readahead.
     */
    fun buildAudioRecord(bufSize: Int): AudioRecord = AudioRecord.Builder()
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
        )
        .setBufferSizeInBytes(bufSize.coerceAtLeast(micRecordBufferSize() * 3))
        .build()

    /**
     * Build a configured AudioTrack for headphone output.
     * Wired headset strongly recommended — Bluetooth A2DP is unsuitable due to buffer delay.
     */
    fun buildAudioTrack(bufSize: Int): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setBufferSizeInBytes(bufSize.coerceAtLeast(speakerPlaybackBufferSize() * 3))
        .build()
}


/**
 * Compute the magnitude of the PCM RMS mono
 * of a buffer of shorts from the decoder to the audio output mixer.
 * 20 frames/sec.
 */
fun rms(buf: ShortArray): Short =
    kotlin.math.sqrt(buf.map { (it * it.toLong()).toDouble() }.average()).toInt().toShort()
