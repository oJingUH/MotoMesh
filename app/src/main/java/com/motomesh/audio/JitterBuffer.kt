package com.motomesh.audio

/**
 * JitterBuffer — simple real-time jitter buffer for 20ms Opus voice frames.
 *
 * Stores decoded PCM ShortArrays. Pull returns silence PLC when the queue is empty.
 * Designed to bridge out-of-order or bursty inbound delivery into a steady 50 Hz playback clock.
 *
 * In loopback mode frames arrive predictably; in production this absorbs BLE/GATT scheduling jitter.
 */
class JitterBuffer(
    private val frameMs: Int = OpusCodec.FRAME_MS,
    private val capacityFrames: Int = 8
) {
    private val buffer = ArrayDeque<ShortArray>()

    /**
     * Push a decoded PCM frame into the buffer. The frame is dropped (PLC) if the buffer
     * is already at capacity; this keeps latency bounded under sustained burst.
     */
    fun pushFrame(frame: ShortArray) {
        if (buffer.size >= capacityFrames) {
            // Dropped — buffer full, caller's frame will play as PLC silence this tick
            return
        }
        buffer.addLast(frame)
    }

    /** Pull the next frame; null means underflow / PLC. */
    fun pullFrame(): ShortArray? = buffer.removeFirstOrNull()

    /** Reset the buffer contents. */
    fun reset() {
        buffer.clear()
    }
}
