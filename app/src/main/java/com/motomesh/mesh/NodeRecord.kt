package com.motomesh.mesh

import com.motomesh.mesh.MotoMeshEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    var lastSeenMs: Long = System.currentTimeMillis(),
    val username: String? = null
) {
    val isAlive: Boolean
        get() = (System.currentTimeMillis() - lastSeenMs) < 80_000L

    /** Display label: "Rider N" or "Rider N (callsign)" */
    val displayName: String
        get() = if (username.isNullOrBlank()) "Rider $nodeId" else "Rider $nodeId ($username)"

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

    // Current user's callsign — injected into every self-record before showing
    @Volatile private var _currentUsername: String? = null

    // Reactive snapshot flow — observed by MainActivity instead of polling every 250ms
    private val _nodeFlow = MutableStateFlow<List<NodeRecord>>(emptyList())
    val nodeFlow: StateFlow<List<NodeRecord>> = _nodeFlow.asStateFlow()

    /** Convenience accessor for non-flow callers; returns a snapshot copy. */
    val snapshot: List<NodeRecord>
        get() = _nodeFlow.value

    /** Start periodic stale-node purging. Call once from Application.onCreate(). */
    fun startPurgeLoop(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                delay(10_000)
                purgeStale()
            }
        }
    }

    private fun emitSnapshot() {
        _nodeFlow.value = synchronized(lock) {
            nodes.values.map { it.withUsername() }.toList()
        }
    }

    fun setUsername(name: String?) {
        _currentUsername = name?.ifBlank { null }
        emitSnapshot()
    }

    private fun NodeRecord.withUsername(): NodeRecord =
        if (nodeId == MotoMeshEngine.thisNodeId && _currentUsername != null)
            copy(username = _currentUsername) else this

    fun touch(nodeId: Int, rssi: Int) = synchronized(lock) {
        val rec = NodeRecord(nodeId, rssi)
        nodes[nodeId] = if (nodeId == MotoMeshEngine.thisNodeId) rec.withUsername() else rec
        emitSnapshot()
    }

    fun markIdle(nodeId: Int, rssi: Int, plc: Float) = synchronized(lock) {
        val prev = nodes[nodeId]
        nodes[nodeId] = prev?.copy(lastSeenMs = System.currentTimeMillis(), lossRate = plc, rssi = rssi)?.withUsername()
            ?: NodeRecord(nodeId, rssi).withUsername()
        emitSnapshot()
    }

    fun purgeStale() = synchronized(lock) {
        val before = nodes.size
        nodes.values.removeIf { !it.isAlive }
        if (nodes.size != before) emitSnapshot()
    }

}
