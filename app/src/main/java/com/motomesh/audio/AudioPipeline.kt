package com.motomesh.audio

import android.media.AudioRecord
import android.media.AudioTrack
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AudioPipeline — the full 20 ms Tx / Rx audio loop driven from MotoMeshEngine.
 *
 * Runs two coroutines inside a SupervisorJob (the owner scope owns the job):
 *
 *  txLoop   — mic → PCM → Opus encode → MotoMeshEngine.txFrame()
 *  rxLoop   — MotoMeshEngine.takeInboundFrame() → JitterBuffer → Opus decode → AudioTrack
 *              → notify DuckingController via [onRms]
 *
 * Both run at strictly 50 Hz (every 20 ms). The rxLoop drops into PLC silence
 * on every frame where no packet has arrived — silence is fine, it's brief.
 */
class AudioPipeline(
    private val config: Config,
    private val onRms: (Short) -> Unit          // DuckingController callback
) : CoroutineScope {

    data class Config(
        val frameSamples: Int = OpusCodec.FRAME_SAMPLES,
        val frameMs: Int = OpusCodec.FRAME_MS
    )

    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Default
    private val running = AtomicBoolean(false)

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private val jitter = JitterBuffer(config.frameMs, 50)
    private val pcmBuf = ShortArray(config.frameSamples)
    private val encodedBuf = ByteArray(OpusCodec.MAX_PAYLOAD)

    fun start() {
        if (running.get()) return
        running.set(true)
        OpusCodec.initEncoder()
        OpusCodec.initDecoder()
        record = OpusCodec.buildAudioRecord(OpusCodec.micRecordBufferSize())
        track = OpusCodec.buildAudioTrack(OpusCodec.speakerPlaybackBufferSize())
        record?.startRecording()
        track?.play()
        launch { txLoop() }
        launch { rxLoop() }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        coroutineContext[Job]?.cancelChildren()
        coroutineContext[Job]?.cancel()
        record?.stop(); record?.release(); record = null
        track?.stop();  track?.release();  track = null
        OpusCodec.releaseEncoder()
        OpusCodec.releaseDecoder()
        jitter.reset()
    }

    // ─── Tx loop: mic → Opus → engine ─────────────────────────────────

    private suspend fun txLoop() = withContext(Dispatchers.Default) {
        val raw = ByteArray(config.frameSamples * 2)   // PCM 16-bit LE
        while (isActive && running.get()) {
            val n = record!!.read(raw, 0, raw.size)
            if (n <= 0) { delay(config.frameMs.toLong()); continue }

            // Zero the tail so a partial read never feeds stale bytes into the encoder.
            java.util.Arrays.fill(raw, n, raw.size, 0.toByte())

            // bytes → shorts (LE, 16-bit signed)
            for (i in 0 until config.frameSamples) {
                pcmBuf[i] = ((raw[i * 2 + 1].toInt() shl 8) or (raw[i * 2].toInt() and 0xFF)).toShort()
            }
            val encoded = OpusCodec.encodeFrame(pcmBuf)
            if (encoded == null) { delay(config.frameMs.toLong()); continue }
            com.motomesh.mesh.MotoMeshEngine.txFrame(encoded)
            delay(config.frameMs.toLong())
        }
    }

    // ─── Rx loop: jitter → decode → output → ducking notifier ──────────

    private suspend fun rxLoop() = withContext(Dispatchers.Default) {
        while (isActive && running.get()) {
            val packet = com.motomesh.mesh.MotoMeshEngine.takeInboundFrame()

            // No packet this tick → pull whatever is buffered (includes PLC)
            val frame = if (packet != null) {
                val decoded = OpusCodec.decodeFrame(packet)
                if (decoded.isNotEmpty()) {
                    jitter.pushFrame(decoded)
                    jitter.pullFrame()
                } else null
            } else {
                jitter.pullFrame()
            }

            if (frame == null) {
                // PLC silence frame — no decoded speech available
                track!!.write(ShortArray(config.frameSamples) { 0 }, 0, config.frameSamples)
                delay(config.frameMs.toLong())
                continue
            }

            // frame is already ShortArray PCM from jitter / decode — write straight out
            if (frame.isNotEmpty()) {
                val rmsVal = computeFrameRms(frame)
                onRms(rmsVal)           // DuckingController ← frame energy
                track!!.write(frame, 0, frame.size)
            }
            delay(config.frameMs.toLong())
        }
    }

    /**
     * Compute the magnitude of the PCM RMS of a decoded frame.
     * Marked private to avoid name-clash with the (now removed) public OpusCodec.rms().
     */
    private fun computeFrameRms(buf: ShortArray): Short {
        var sum = 0L
        for (s in buf) sum += (s * s).toInt().toLong()
        return kotlin.math.sqrt(sum.toDouble() / buf.size).toInt().toShort()
    }
}
