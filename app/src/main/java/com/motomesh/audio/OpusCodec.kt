package com.motomesh.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat

/**
 * OpusCodec — wraps Android's built-in MediaCodec Opus encoder/decoder.
 *
 * Protocol invariant (matches RYLR993 packet layout):
 *   20 ms frames | mono | 16 kHz | Opus bitrate 20 kbps CBR
 *
 * MediaCodec exposes Opus as "audio/opus" in API 24+.
 * We use synchronous (blocking) calls so the encoder/decoder
 * stays simple enough for a 50 Hz coroutine loop.
 */
object OpusCodec {

    const val SAMPLE_RATE = 16_000      // Hz
    const val CHANNELS = 1              // mono
    const val FRAME_MS = 20             // ms per Opus frame
    const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000   // 320
    const val BIT_RATE_BPS = 20_000     // 20 Kbps
    const val MAX_PAYLOAD = 240         // RYLR993 max packet bytes

    private const val MIME = "audio/opus"

    private var encoder: MediaCodec? = null
    private var decoder: MediaCodec? = null
    private var encoderReady = false
    private var decoderReady = false

    // ─── Encoder ─────────────────────────────────────────────────

    /**
     * Encode 320 raw 16-bit mono samples (20 ms) into an Opus frame.
     * Returns the encoded payload bytes, or null if encoder not initialised.
     * Thread-safety: call from Tx coroutine, single-producer.
     */
    fun encodeFrame(pcm16: ShortArray): ByteArray? {
        val codec = encoder ?: return null
        if (pcm16.size != FRAME_SAMPLES) return null

        val inBufIdx = codec.dequeueInputBuffer(-1) ?: return null
        val inBuf = codec.getInputBuffer(inBufIdx) ?: return null
        inBuf.clear()
        for (i in 0 until FRAME_SAMPLES) {
            inBuf.putShort(pcm16[i])
        }
        codec.queueInputBuffer(inBufIdx, 0, FRAME_SAMPLES * 2, 0, 0)

        val info = MediaCodec.BufferInfo()
        val outBufIdx = codec.dequeueOutputBuffer(info, 0)
        if (outBufIdx < 0) return null
        if (outBufIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            // first frame — just drain
            codec.releaseOutputBuffer(outBufIdx, false)
            return encodeFrame(pcm16)
        }
        val outBuf = codec.getOutputBuffer(outBufIdx) ?: return null
        outBuf.position(info.offset)
        outBuf.limit(info.offset + info.size)
        val bytes = ByteArray(info.size)
        outBuf.get(bytes)
        codec.releaseOutputBuffer(outBufIdx, false)
        return bytes
    }

    fun initEncoder() {
        if (encoderReady) return
        encoder = MediaCodec.createEncoderByType(MIME)
        val format = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE_BPS)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_SAMPLES * 2)
        }
        encoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder?.start()
        encoderReady = true
    }

    fun releaseEncoder() {
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        encoder = null
        encoderReady = false
    }

    // ─── Decoder ─────────────────────────────────────────────────

    /**
     * Decode an Opus frame into 320 raw 16-bit mono samples.
     * Returns a ShortArray of samples, or silent frame on PLC / error.
     */
    fun decodeFrame(opusBytes: ByteArray): ShortArray {
        val codec = decoder ?: return ShortArray(FRAME_SAMPLES) { 0 }
        val inBufIdx = codec.dequeueInputBuffer(0) ?: return ShortArray(FRAME_SAMPLES) { 0 }
        val inBuf = codec.getInputBuffer(inBufIdx) ?: return ShortArray(FRAME_SAMPLES) { 0 }
        inBuf.clear()
        inBuf.put(opusBytes)
        codec.queueInputBuffer(inBufIdx, 0, opusBytes.size, 0, 0)

        val info = MediaCodec.BufferInfo()
        val outBufIdx = codec.dequeueOutputBuffer(info, 5_000_000) // 5 ms timeout
        if (outBufIdx < 0) {
            drainPending()
            return ShortArray(FRAME_SAMPLES) { 0 }
        }
        if (outBufIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            codec.releaseOutputBuffer(outBufIdx, false)
            return decodeFrame(opusBytes)
        }
        val outBuf = codec.getOutputBuffer(outBufIdx)
        if (outBuf == null) {
            codec.releaseOutputBuffer(outBufIdx, false)
            return ShortArray(FRAME_SAMPLES) { 0 }
        }
        outBuf.position(info.offset)
        outBuf.limit(info.offset + info.size)
        val samples = ShortArray(info.size / 2)
        outBuf.asShortBuffer().get(samples)
        codec.releaseOutputBuffer(outBufIdx, false)
        // Drain any additional output
        drainPending()
        return samples
    }

    /** Drain any queued sample buffers without blocking. */
    private fun drainPending() {
        val codec = decoder ?: return
        while (true) {
            val info = MediaCodec.BufferInfo()
            val idx = codec.dequeueOutputBuffer(info, 0)
            if (idx < 0) break
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                codec.releaseOutputBuffer(idx, false)
                continue
            }
            val outBuf = codec.getOutputBuffer(idx)
            if (outBuf == null) {
                codec.releaseOutputBuffer(idx, false)
                continue
            }
            if (info.size > 0) {
                val samples = ShortArray(info.size / 2)
                outBuf.position(info.offset)
                outBuf.limit(info.offset + info.size)
                outBuf.asShortBuffer().get(samples)
            }
            codec.releaseOutputBuffer(idx, false)
        }
    }

    fun initDecoder() {
        if (decoderReady) return
        decoder = MediaCodec.createDecoderByType(MIME)
        val format = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_PAYLOAD)
        }
        decoder?.configure(format, null, null, 0)
        decoder?.start()
        decoderReady = true
    }

    fun releaseDecoder() {
        try { decoder?.stop() } catch (_: Exception) {}
        try { decoder?.release() } catch (_: Exception) {}
        decoder = null
        decoderReady = false
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
 * Compute the magnitude of the PCM RMS of a decoded frame.
 */
fun rms(buf: ShortArray): Short {
    var sum = 0L
    for (s in buf) sum += (s * s).toInt().toLong()
    return kotlin.math.sqrt(sum.toDouble() / buf.size).toInt().toShort()
}
