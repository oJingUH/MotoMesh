package com.motomesh.mesh

/**
 * NodeRecord — one rider node in the mesh.
 *
 * Populated by MeshForwarder / RYLR993Ble as beacons arrive;
 * observed (read-only) by NodeAdapter and MainActivity.
 */
data class NodeRecord(
    val nodeId: Int,
    val rssi: Int,
    var lossRate: Float = 0f,
    var lastSeenMs: Long = System.currentTimeMillis()
) {
    val isAlive: Boolean
        get() = (System.currentTimeMillis() - lastSeenMs) < 80_000L

    companion object Factory {
        fun snapshotOnly(): List<NodeRecord> = emptyList()
    }
}

/**
 * NodeTable — thread-safe node registry shared across the app.
 *
 * Updated by MeshForwarder on inbound beacon / RYLR993 packets.
 * Read by MainActivity for the rider-list RecyclerView.
 */
object NodeTable {
    private val lock = Any()
    private val nodes = java.util.HashMap<Int, NodeRecord>()

    fun touch(nodeId: Int, rssi: Int) = synchronized(lock) {
        nodes[nodeId] = NodeRecord(nodeId, rssi)
    }

    fun markIdle(nodeId: Int, rssi: Int, plc: Float) = synchronized(lock) {
        val prev = nodes[nodeId]
        nodes[nodeId] = prev?.copy(lastSeenMs = System.currentTimeMillis(), lossRate = plc)
            ?: NodeRecord(nodeId, rssi)
    }

    fun purgeStale() = synchronized(lock) { nodes.values.removeIf { !it.isAlive } }

    val snapshot: List<NodeRecord>
        get() = synchronized(lock) { nodes.values.toList() }
}
