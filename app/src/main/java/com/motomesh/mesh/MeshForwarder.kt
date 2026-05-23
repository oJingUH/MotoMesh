package com.motomesh.mesh

import android.util.Log
import com.motomesh.audio.OpusCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * MeshForwarder — flood-gossip dedup + forward for Opus voice frames.
 *
 * Contract:
 *  incoming frame → processIncoming() → returns (nodeId, meta) or null (drop)
 *  outbound frame → prependNodeId() + send via MeshEngine/LoRaDriver
 *
 * Seen-eviction: 5 s sliding window prevents expanding seen-table forever.
 */
object MeshForwarder {

    const val TAG = "MeshForwarder"
    const val MAX_HOPS = 5
    private const val DEDUPE_WINDOW_MS = 5_000L
    const val PAYLOAD_OFFSET = 4    // payload starts at byte 4 of the frame

    private val seen = ConcurrentHashMap<Long, Long>()
    private val frameSeq = AtomicLong(0)
    private val nextNodeId = AtomicLong(1)  // each rider gets a unique node ID

    // Outbound queue from upper layers → LoRaDriver
    private val txOut = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    private var localNodeId: Int = 0

    init {
        // Start pumping our outbound queue into LoRaDriver in a long-running coroutine.
        // The caller supplies the scope — see start() below in companion object bridge
        // or let MotoMeshEngine own it.
    }

    private val txInFlight = ThreadLocal.withInitial { false }

    // ─── Entry points ───────────────────────────────────────────

    /**
     * Assign node ID for this rider. Called once at engine start.
     */
    fun assignNodeId(): Int {
        localNodeId = nextNodeId.getAndIncrement().toInt()
        return localNodeId
    }

    /**
     * Build an outbound frame for the next voice transmission.
     * Wraps the raw Opus payload with the LoRa transport header.
     */
    fun buildOutbound(data: ByteArray): ByteArray {
        val seq = frameSeq.getAndIncrement()
        val buf = ByteArray(PAYLOAD_OFFSET + data.size)
        // Byte 0: signature (0x00 per RYLR993 raw mode)
        buf[0] = 0x00
        // Bytes 1–3: sequence + node + flags packed
        val hi  = (seq        and 0xFF).toByte()
        val mi  = (localNodeId and 0xFF).toByte()
        val lo  = (0           and 0xFF).toByte()  // flags / hop-count start at 0
        buf[1] = hi
        buf[2] = mi
        buf[3] = lo
        System.arraycopy(data, 0, buf, PAYLOAD_OFFSET, data.size.coerceAtMost(data.size))
        return buf
    }

    /**
     * Process an inbound raw LoRa packet.
     *
     * @return Pair of (nodeId that spoke, rms energy of this frame) or null if dropped.
     */
    fun processIncoming(raw: ByteArray, out: MutableList<ByteArray>): Pair<Short, Short>? {
        if (raw.size < PAYLOAD_OFFSET + 1) {
            Log.w(TAG, "Underlength frame ${raw.size}B")
            return null
        }
        val seq   = (raw[1].toInt() and 0xFF) or ((raw[2].toInt() and 0xFF) shl 8)
        val nodeId = (raw[2].toInt() and 0xFF).toShort()
        val hops  = (raw[3].toInt() and 0xFF)

        if (hops > MAX_HOPS) {
            Log.d(TAG, "Hop limit $hops > $MAX_HOPS — dropping")
            return null
        }

        val now = System.currentTimeMillis()
        val fingerprint = (nodeId.toLong() shl 16) or (seq.toLong() and 0xFFFF)
        val last = seen.put(fingerprint, now) ?: -1L
        if (now - last < DEDUPE_WINDOW_MS) return null

        // Evict stale fingerprints
        val stale = now - DEDUPE_WINDOW_MS
        seen.entries.removeIf { it.value < stale }

        // Re-encode for forwarding (increment hop count)
        if (hops < MAX_HOPS) {
            val forwarded = raw.clone()
            forwarded[3] = (hops + 1).toByte()
            out.add(forwarded)
        }

        // Peel out the Opus payload
        val payload = ByteArray(raw.size - PAYLOAD_OFFSET)
        System.arraycopy(raw, PAYLOAD_OFFSET, payload, 0, payload.size)

        // Estimate RMS before returning to AudioPipeline
        val rms = estimateRms(payload)
        return Pair(nodeId, rms)
    }

    // ─── Helpers ────────────────────────────────────────────────

    /**
     * Extremely rough RMS estimate from un-decoded Opus bytes.
     * Accurate enough for ducking thresholding; not a replacement for decoding.
     */
    private fun estimateRms(payload: ByteArray): Short {
        var sum = 0L
        for (b in payload) {
            sum += (b.toInt() and 0xFF) * (b.toInt() and 0xFF)
        }
        if (sum == 0L) return 0
        return kotlin.math.sqrt(sum.toDouble() / payload.size).toFloat().toInt().toShort()
    }

    /**
     * Scale factor: simple scaled RMS of ByteArray for duck edge.
     */
    fun rmsShort(buf: ShortArray): Short {
        var sum: Long = 0
        for (s in buf) sum += (s.toLong() * s.toLong())
        return kotlin.math.sqrt(sum.toDouble() / buf.size).toInt().toShort()
    }

// ─── Node helpers ──────────────────────────────────────────────────────────

data class NodeRecord(
    val nodeId: Int,
    val rssi: Int,
    var lossRate: Float = 0f,
    var lastSeenMs: Long = System.currentTimeMillis()
) {
    val isAlive: Boolean
        get() = (System.currentTimeMillis() - lastSeenMs) < 80_000L
}

object NodeRecord {
    fun snapshotOnly(): List<NodeRecord> = emptyList() // stub: reachable via MeshForwarder
}

object NodeTable {
    private val lock = Any()
    private val nodes = java.util.HashMap<Int, NodeRecord>()
    fun touch(nodeId: Int, rssi: Int) = synchronized(lock) {
        nodes[nodeId] = NodeRecord(nodeId, rssi)
    }
    fun markIdle(nodeId: Int, rssi: Int, plc: Float) = synchronized(lock) {
        val prev = nodes[nodeId]
        nodes[nodeId] = prev?.copy(lastSeenMs = System.currentTimeMillis(), lossRate = plc) ?: NodeRecord(nodeId, rssi)
    }
    fun purgeStale() = synchronized(lock) { nodes.values.removeIf { !it.isAlive } }
    val snapshot: List<NodeRecord>
        get() = synchronized(lock) { nodes.values.toList() }
}
}
