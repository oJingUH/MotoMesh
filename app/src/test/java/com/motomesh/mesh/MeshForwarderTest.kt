package com.motomesh.mesh

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for MeshForwarder — frame building, dedup, flooding.
 *
 * NOTE: MeshForwarder is a singleton object; internal state (nodeId, frameSeq, seen table)
 * persists across tests. Tests are ordered and must work within this constraint.
 */
@RunWith(RobolectricTestRunner::class)
class MeshForwarderTest {

    private val localId: Int by lazy { MeshForwarder.assignNodeId() }

    @Before
    fun setUp() {
        // Ensure local node ID is assigned before any test runs
        localId
    }

    @Test
    fun `buildOutbound creates frame with correct header layout`() {
        val opus = byteArrayOf(0x01, 0x02, 0x03, 0x04)

        val frame = MeshForwarder.buildOutbound(opus)

        // Byte 0: signature (0x00)
        assertEquals(0x00.toByte(), frame[0])
        // Byte 3: node ID must be non-zero (assigned by assignNodeId)
        assertNotEquals(0, frame[3].toInt() and 0xFF)
        // Payload offset 4: original opus data
        assertArrayEquals(opus, frame.copyOfRange(MeshForwarder.PAYLOAD_OFFSET, frame.size))
        // Total frame size = PAYLOAD_OFFSET + opus size
        assertEquals(MeshForwarder.PAYLOAD_OFFSET + opus.size, frame.size)
    }

    @Test
    fun `buildOutbound sequence increments monotonically`() {
        val opus = byteArrayOf(0x55.toByte())
        val seq1 = extractSeq(MeshForwarder.buildOutbound(opus))
        val seq2 = extractSeq(MeshForwarder.buildOutbound(opus))

        assertEquals("sequence should increase by 1", seq1 + 1, seq2)
    }

    @Test
    fun `processIncoming returns nodeId and rms for remote frame`() {
        val opus = ByteArray(20) { it.toByte() }
        // Build a frame with a node ID that differs from local (fits in 1 byte)
        val frame = makeRemoteFrame(opus, nodeId = 42)

        val forwarded = mutableListOf<ByteArray>()
        val result = MeshForwarder.processIncoming(frame, forwarded)

        assertNotNull("valid remote frame should not be dropped", result)
        val (nodeId, rms) = result!!
        assertEquals(42, nodeId.toInt())
        assertTrue("rms should be >= 0", rms >= 0)
    }

    @Test
    fun `processIncoming deduplicates identical frames`() {
        val opus = ByteArray(10) { 0x42.toByte() }
        val frame = makeRemoteFrame(opus, nodeId = 42)
        val fwd1 = mutableListOf<ByteArray>()
        val fwd2 = mutableListOf<ByteArray>()

        val first = MeshForwarder.processIncoming(frame, fwd1)
        val second = MeshForwarder.processIncoming(frame, fwd2)

        assertNotNull("first frame should be accepted", first)
        assertNull("duplicate frame should be dropped", second)
    }

    @Test
    fun `processIncoming drops own-node frames`() {
        // Build a frame whose node ID matches the local node
        val ownId = extractNodeId(MeshForwarder.buildOutbound(byteArrayOf(0x10)))
        val opus = byteArrayOf(0x20, 0x30)
        val frame = makeRemoteFrame(opus, nodeId = ownId)
        val forwarded = mutableListOf<ByteArray>()

        val result = MeshForwarder.processIncoming(frame, forwarded)

        assertNull("own-node frame should be dropped", result)
    }

    @Test
    fun `processIncoming underlength frame returns null`() {
        val shortFrame = byteArrayOf(0x00, 0x01)
        val forwarded = mutableListOf<ByteArray>()

        val result = MeshForwarder.processIncoming(shortFrame, forwarded)

        assertNull("underlength frame should be dropped", result)
    }

    @Test
    fun `processIncoming forwards remote frames`() {
        val opus = ByteArray(8) { 0x55.toByte() }
        val frame = makeRemoteFrame(opus, nodeId = 77)
        val forwarded = mutableListOf<ByteArray>()

        MeshForwarder.processIncoming(frame, forwarded)

        assertTrue("should produce at least one forwarded frame", forwarded.isNotEmpty())
        for (fwd in forwarded) {
            assertTrue("forwarded frame should have opus payload",
                fwd.size >= MeshForwarder.PAYLOAD_OFFSET)
        }
    }

    @Test
    fun `buildOutbound sequence wraps at 65536`() {
        // Force many builds to verify wrapping
        val opus = byteArrayOf(0xFF.toByte())
        var lastSeq = -1
        var wrapCount = 0

        repeat(66000) {
            val frame = MeshForwarder.buildOutbound(opus)
            val seq = extractSeq(frame)
            if (seq < lastSeq && lastSeq > 65000) wrapCount++
            lastSeq = seq
        }

        assertTrue("sequence should wrap at least once in 66000 frames", wrapCount >= 1)
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private fun extractSeq(frame: ByteArray): Int =
        (frame[1].toInt() and 0xFF) or ((frame[2].toInt() and 0xFF) shl 8)

    private fun extractNodeId(frame: ByteArray): Int =
        frame[3].toInt() and 0xFF

    /** Build a frame that looks like it came from a remote rider (different node ID). */
    private fun makeRemoteFrame(opus: ByteArray, nodeId: Int): ByteArray {
        val local = MeshForwarder.buildOutbound(opus)
        val seq = extractSeq(local)
        // Header: sig(1) + seq(2) + nodeId(1) + hopCount(1) = 5 bytes
        val hdrSize = MeshForwarder.PAYLOAD_OFFSET  // 5
        val frame = ByteArray(hdrSize + opus.size)
        frame[0] = 0x00
        frame[1] = (seq and 0xFF).toByte()
        frame[2] = ((seq ushr 8) and 0xFF).toByte()
        frame[3] = (nodeId and 0xFF).toByte()
        frame[4] = 0  // hop count starts at 0
        System.arraycopy(opus, 0, frame, hdrSize, opus.size)
        return frame
    }
}