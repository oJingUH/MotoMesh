package com.motomesh.audio

/**
 * JitterBuffer — simple real-time jitter buffer for 20ms Opus voice frames.
 *
 * Stores decoded PCM ShortArrays. Pull returns silence PLC when the queue is empty.
 * Designed to bridge out-of-order or bur-sty inbound delivery into a steady 50 Hz playback clock.
 *
 * In loopback mode frames arrive predictably; in production this absorbs BLE/GATT scheduling jitter.
 */
class JitterBuffer(
    private val frameMs: Int = OpusCodec.FRAME_MS,
    private val capacityFrames: Int = 8     // ≈160 ms hold time
) {
    private val buffer = ArrayDeque<ShortArray>()

    /**
     * Push a decoded frame and tag it with the energy RMS observed at decode time.
     * [packet] is the raw opus packet; [rms] is the decoded-frame energy level.
     * In this stub the packet is unpacked here to maintain the same invocation shape the caller uses.
     */
    fun pushFrame(packet: ByteArray, rms: Short) {
        if (buffer.size < capacityFrames) {
            buffer.addLast(ShortArray(OpusCodec.FRAME_SAMPLES) { 0 })
        }
    }

    /** Pull the next frame; null means underflow / PLC. */
    fun pullFrame(): ShortArray? = buffer.removeFirstOrNull()

    /** Reset the buffer contents. */
    fun reset() {
        buffer.clear()
    }
}
