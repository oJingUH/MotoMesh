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
     * Sequence wraps at 65536 (16-bit), matching processIncoming() on the receiver side.
     */
    fun buildOutbound(data: ByteArray): ByteArray {
        val seq = frameSeq.getAndIncrement() and 0xFFFF
        val buf = ByteArray(PAYLOAD_OFFSET + data.size)
        // Byte 0: signature (0x00 per RYLR993 raw mode)
        buf[0] = 0x00
        // Bytes 1–2: 16-bit sequence (little-endian, wraps at 65536)
        //   buf[1] = seq lo, buf[2] = seq hi
        // Byte 3: local node ID
        // processIncoming() reconstructs: seq = raw[1] or (raw[2] shl 8), nodeId = raw[3]
        buf[1] = (seq and 0xFF).toByte()          // LSB of 16-bit seq
        buf[2] = ((seq ushr 8) and 0xFF).toByte() // MSB of 16-bit seq
        buf[3] = (localNodeId and 0xFF).toByte()  // node ID
        System.arraycopy(data, 0, buf, PAYLOAD_OFFSET, data.size.coerceAtMost(data.size))
        return buf
    }

    /**
     * Process an inbound raw LoRa packet.
     *
     * Wire layout matches buildOutbound():
     *   buf[0] = 0x00, buf[1] = seq_lo, buf[2] = seq_hi, buf[3] = nodeId, payload @ PAYLOAD_OFFSET
     *
     * @return Pair of (nodeId that spoke, rms energy of this frame) or null if dropped.
     */
    fun processIncoming(raw: ByteArray, out: MutableList<ByteArray>): Pair<Short, Short>? {
        if (raw.size < PAYLOAD_OFFSET + 1) {
            Log.w(TAG, "Underlength frame ${raw.size}B")
            return null
        }
        // Bytes 1–2: 16-bit sequence number (little-endian)
        val seq    = (raw[1].toInt() and 0xFF) or ((raw[2].toInt() and 0xFF) shl 8)
        // Byte 3: source node ID
        val nodeId = (raw[3].toInt() and 0xFF).toShort()

        if (nodeId == localNodeId.toShort()) {
            // Loopback echo — don't re-route our own frame
            return null
        }

        val now = System.currentTimeMillis()
        val fingerprint = (nodeId.toLong() shl 16) or (seq.toLong() and 0xFFFF)
        val last = seen.put(fingerprint, now) ?: -1L
        if (now - last < DEDUPE_WINDOW_MS) return null

        // Evict stale fingerprints
        val stale = now - DEDUPE_WINDOW_MS
        seen.entries.removeIf { it.value < stale }

        // Re-encode for forwarding : increment hops byte (at index 3 so payload stays intact)
        val forwarded = raw.clone()
        forwarded[3] = ((forwarded[3].toInt() and 0xFF) + 1).toByte()
        if (forwarded[3].toInt() and 0xFF <= MAX_HOPS) {
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

}
