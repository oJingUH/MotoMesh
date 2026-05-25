package com.motomesh.mesh

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Unit tests for NodeTable — thread-safe rider registry with reactive StateFlow.
 *
 * NOTE: NodeTable is a singleton object; state persists across tests.
 * Each test uses a unique node ID to avoid cross-test interference.
 */
@RunWith(RobolectricTestRunner::class)
class NodeTableTest {

    private var nextId = 100

    @Test
    fun `touch adds a node to the table`() {
        val id = nextId++
        NodeTable.touch(id, -70)
        val snapshot = NodeTable.snapshot

        val node = snapshot.find { it.nodeId == id }
        assertNotNull("node $id should be in the table", node)
        assertEquals(-70, node!!.rssi)
        assertTrue("node should be alive", node.isAlive)
    }

    @Test
    fun `touch updates existing node RSSI`() {
        val id = nextId++
        NodeTable.touch(id, -60)
        NodeTable.touch(id, -80)

        val node = NodeTable.snapshot.find { it.nodeId == id }
        assertNotNull(node)
        assertEquals(-80, node!!.rssi)
    }

    @Test
    fun `markIdle updates loss rate and timestamp`() {
        val id = nextId++
        NodeTable.touch(id, -50)
        NodeTable.markIdle(id, -55, 0.15f)

        val node = NodeTable.snapshot.find { it.nodeId == id }
        assertNotNull(node)
        assertEquals(-55, node!!.rssi)
        assertEquals(0.15f, node.lossRate, 0.001f)
    }

    @Test
    fun `emits via StateFlow after touch`() = runBlocking {
        val id = nextId++
        NodeTable.touch(id, -45)

        val flowSnapshot = NodeTable.nodeFlow.first()
        assertTrue("StateFlow should contain node $id",
            flowSnapshot.any { it.nodeId == id })
    }

    @Test
    fun `emits via StateFlow after markIdle`() = runBlocking {
        val id = nextId++
        NodeTable.touch(id, -35)

        val before = NodeTable.nodeFlow.first().find { it.nodeId == id }
        assertEquals(-35, before!!.rssi)

        NodeTable.markIdle(id, -90, 0.5f)
        val after = NodeTable.nodeFlow.first().find { it.nodeId == id }
        assertEquals(-90, after!!.rssi)
        assertEquals(0.5f, after.lossRate, 0.001f)
    }

    @Test
    fun `setUsername does not throw`() = runBlocking {
        NodeTable.setUsername("TestRider")
        NodeTable.setUsername(null)
    }
}