package com.motomesh.mesh

import android.util.Log

/**
 * Jitter buffer for Opus frames arriving over LoRa.
 *
 * LoRa packets are bursty — 2–3 arrive in a 60 ms burst, then a 100 ms gap.
 * The jitter buffer smooths this into a steady 50 fps (50 Hz) playback stream.
 *
 * Design constraint: the voice frame period is 20 ms = 50 frames/sec.
 * The buffer holds a rolling window of frame slots and fills gaps with PLC.
 */
class JitterBuffer(
    private val frameDurationMs: Int = 20,
    private val targetFps: Int = 50
) {

    private val TAG = "JitterBuffer"

    /**
     * Each slot corresponds to one 20 ms frame window.
     * filled = false → this slot is still empty → push synthetic silence (PLC)
     * filled = true  → real Opus frame has been placed here
     */
    data class Slot(
        val buf: ByteArray = ByteArray(OpusCodec.MAX_PAYLOAD),
        var filled: Boolean = false,
        var rmsAvg: Short = 0
    )

    private val slots = Array(targetFps) { Slot() }
    private var writePos = 0      // where we push incoming frames
    private var readPos  = 0      // where playback polls
    private val lock = Object()
    private var running = false

    // Stats
    private var totalFrames = 0
    private var lostFrames  = 0

    /** Push one decoded Opus payload for the *current* write window. */
    fun pushFrame(opusPayload: ByteArray, rms: Short) {
        if (opusPayload.isEmpty()) return
        synchronized(lock) {
            slots[writePos].buf = opusPayload.copyOf(OpusCodec.MAX_PAYLOAD)
            slots[writePos].filled = true
            slots[writePos].rmsAvg = rms
            writePos = (writePos + 1) % slots.size
            totalFrames++
        }
    }

    /**
     * Pull one Opus frame at playback time. Must be called every 20 ms
     * from the playback thread / AudioTrack write callback.
     *
     * Returns null if the slot is empty → PLC silence.
     */
    fun pullFrame(): ByteArray? {
        synchronized(lock) {
            val slot = slots[readPos]
            readPos = (readPos + 1) % slots.size
            if (!slot.filled) {
                lostFrames++
                return null    // signal PLC to the decoder
            }
            slot.filled = false
            return slot.buf
        }
    }

    /**
     * Current PLC (packet loss concealment) rate as a fraction [0..1].
     */
    fun lossRate(): Float = if (totalFrames == 0) 0f else lostFrames.toFloat() / totalFrames

    /**
     * Reset the buffer — flush all pending frames, reset counters.
     * Call after re-sync (e.g. group rejoin / module reconnect).
     */
    fun reset() {
        synchronized(lock) {
            slots.forEach { it.filled = false }
            writePos = 0
            readPos  = 0
            totalFrames = 0
            lostFrames  = 0
        }
    }
}

/**
 * NodeTable keeps track of our known riders
 * Decay rates awake working nodes 0..N with runtime id.
 *
 * Loss rate was added (average loss rate per node id),
 * Ping sustain, and a small set of others.
 */
data class NodeRecord(
    val nodeId: Int,
    val rssi: Int,
    var lossRate: Float = 0f,
    var lastSeenMs: Long = System.currentTimeMillis()
) {
    val isAlive: Boolean
        get() = (System.currentTimeMillis() - lastSeenMs) < 80_000
}

class NodeTable {

    private val lock = Any()
    private val nodes = HashMap<Int, NodeRecord>()

    fun touch(nodeId: Int, rssi: Int) = synchronized(lock) {
        val prev  = nodes[nodeId]
        val decay = prev?.lossRate ?: 0f
        nodes[nodeId] = NodeRecord(nodeId, rssi, decay)
    }

    fun markIdle(nodeId: Int, rssi: Int, plcLossRateSample: Float) = synchronized(lock) {
        val prev = nodes[nodeId] ?: run { nodes[nodeId] = NodeRecord(nodeId, rssi); return }
        nodes[nodeId] = prev.copy(lastSeenMs = System.currentTimeMillis(), lossRate = plcLossRateSample)
    }

    fun purgeStale() = synchronized(lock) {
        nodes.values.removeIf { !it.isAlive }
    }

    val snapshot: List<NodeRecord>
        get() = synchronized(lock) { nodes.values.toList() }

    val aliveCount: Int
        get() = synchronized(lock) { nodes.values.count { it.isAlive } }
}