package com.motomesh.cellular

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * CellularBridge — cellular data transport for MotoMesh voice frames.
 *
 * Architecture (Phase 1 stub):
 *  - CellularBridge detects whether a cellular data network is available
 *  - When CELLULAR transport is selected, the engine routes outbound Opus frames
 *    to the outboundQueue as-is; they travel via the existing MeshForwarder path
 *    and then into a TCP socket to a remotely-registered forwarding server
 *    (not yet implemented; placeholder for Phase 2).
 *
 * Phase 2 implementation will:
 *  1. Register Opus frames with a relay node over TCP
 *  2. Use standard Android Sockets API (no SIP stack — Opus-in-TCP framing
 *     matches the WiFi Direct frame header but is endpoint-addressed, not
 *     link-local)
 *  3. Use ConnectivityManager to bind the socket to the cellular network when
 *     WiFi is also present (prevents Android from routing the media socket over WiFi)
 *
 * Current stub mirrors the WifiDirectBridge.kt StateFlow interface so MainActivity
 * can monitor connection state with zero code changes to the observer pattern.
 */
object CellularBridge {

    const val TAG = "CellularBridge"

    enum class CellularState {
        IDLE,           // not yet checked
        CHECKING,       // network callback pending
        AVAILABLE,      // cellular data confirmed + socket bound
        UNAVAILABLE,    // no data connection
        FAILED          // socket error
    }

    val cellularState: MutableStateFlow<CellularState> =
        MutableStateFlow(CellularState.IDLE)

    private val _connectError = MutableStateFlow<String?>(null)
    val connectError: StateFlow<String?> = _connectError.asStateFlow()

    private var bridgeScope: CoroutineScope? = null
    private var socketJob: Job? = null

    private var peerSocket: java.net.Socket? = null
    private var peerInput: java.io.InputStream? = null
    private var peerOutput: java.io.OutputStream? = null

    private const val RELAY_PORT = 60005   // Phase 2 relay-server port
    private const val FRAME_HEADER = 0xBB.toByte()  // different header from WiFi for debug

    // ─── Lifecycle ──────────────────────────────────────────────────────────

    fun init(context: Context) {
        bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // Probe cellular availability immediately; also register a live callback.
        probeCellular(context)
    }

    fun close() {
        try { peerSocket?.close() } catch (_: Exception) { }
        peerInput = null; peerOutput = null; peerSocket = null
        socketJob?.cancel(); socketJob = null
        bridgeScope?.coroutineContext?.get(Job)?.cancel()
        bridgeScope = null
        cellularState.value = CellularState.IDLE
        _connectError.value = null
    }

    // ─── Availability probe ─────────────────────────────────────────────────

    private fun probeCellular(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()

        cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Cellular network AVAILABLE: $network")
                cellularState.value = CellularState.AVAILABLE
                // Phase 2: bind socket to this network
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Cellular network LOST")
                if (cellularState.value == CellularState.AVAILABLE) {
                    cellularState.value = CellularState.UNAVAILABLE
                }
            }

            override fun onUnavailable() {
                Log.w(TAG, "Cellular network UNAVAILABLE")
                cellularState.value = CellularState.UNAVAILABLE
            }
        })
    }

    // ─── Frame send (called from MeshEngine tx pump) ─────────────────────────

    /**
     * Enqueue one Opus frame for cellular delivery.
     * Phase 2: opens a TCP channel to a relay server and writes the framed payload.
     * Currently a no-op with error trace — the relay server does not yet exist.
     */
    fun sendFrame(data: ByteArray): Boolean {
        if (cellularState.value != CellularState.AVAILABLE) {
            Log.w(TAG, "sendFrame called but cellular state is ${cellularState.value}")
            return false
        }
        Log.i(TAG, "sendFrame stub: ${data.size} bytes — relay server not yet implemented (Phase 2)")
        return false
    }

    val isConnected: Boolean get() = cellularState.value == CellularState.AVAILABLE
}
