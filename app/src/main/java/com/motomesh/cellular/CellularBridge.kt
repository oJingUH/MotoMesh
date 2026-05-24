package com.motomesh.cellular

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue

/**
 * CellularBridge — cellular TCP relay transport for MotoMesh voice frames.
 *
 * Architecture:
 *  - Availability: ConnectivityManager.NetworkCallback detects cellular data presence.
 *  - Outbound: Opus frame → [FRAME_HEADER 0xBB][len:2B LE][opus payload] → TCP socket write.
 *  - Inbound: TCP socket read pump parses same framing, pushes raw Opus payloads into
 *    [inboundQueue] which MotoMeshEngine drains via takeCellularFrame() → enqueueInbound().
 *
 * No SIP stack. Pure socket + binary framing. Relay server is a thin TCP forwarder
 * that relays byte-stream frames between connected riders (handled externally).
 *
 * Frame format (matches WiFi Direct header pattern but endpoint-addressed):
 *  ┌──────────┬──────────┬──────────────────────────┐
 *  │ 0xBB     │ len LE   │     Opus payload          │
 *  └──────────┴──────────┴──────────────────────────┘
 *  total: 4-byte header + opus frame
 */
object CellularBridge {

    const val TAG = "CellularBridge"

    enum class CellularState {
        IDLE,           // not yet checked
        CHECKING,       // network callback pending
        AVAILABLE,      // cellular data confirmed
        UNAVAILABLE,    // no data connection
        FAILED          // socket error
    }

    val cellularState: MutableStateFlow<CellularState> =
        MutableStateFlow(CellularState.IDLE)

    private val _connectError = MutableStateFlow<String?>(null)
    val connectError: StateFlow<String?> = _connectError.asStateFlow()

    private var bridgeScope: CoroutineScope? = null
    private var socketJob: Job? = null

    // TCP relay config (Phase 3: default localhost; will be replaced by user selection)
    private const val DEFAULT_RELAY_HOST = "10.0.2.2"   // Android emulator host loopback
    private const val DEFAULT_RELAY_PORT = 60005
    private var relayHost: String = DEFAULT_RELAY_HOST
    private var relayPort: Int = DEFAULT_RELAY_PORT

    // Shared binary framing constants
    private const val FRAME_HEADER: Byte = 0xBB.toByte()
    private const val FRAME_HEADER_SIZE = 1
    private const val LEN_FIELD_SIZE = 2   // uint16 LE
    private const val FRAME_OVERHEAD = FRAME_HEADER_SIZE + LEN_FIELD_SIZE
    private const val MAX_FRAME_BYTES = 4096   // safety cap for a single voice frame

    // Thread-safe inbound queue: Engine pulls from here (LinkedBlockingQueue is preferred by
    // Kotlin coroutines for blocking-drain; takeCellularFrame() blocks without blocking a
    // dispatcher thread because MotoMeshEngine tx/rx pumps already run on Dispatchers.Default)
    private val inboundQueue = LinkedBlockingQueue<ByteArray>(256)

    private var peerSocket: Socket? = null
    private var peerInput: InputStream? = null
    private var peerOutput: OutputStream? = null

    private var currentNetwork: Network? = null   // cellular network to bind socket to

    // ─── Lifecycle ──────────────────────────────────────────────────────────────

    fun init(context: Context) {
        bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        probeCellular(context)
    }

    fun close() {
        try { peerSocket?.close() } catch (_: IOException) { }
        peerInput = null; peerOutput = null; peerSocket = null
        socketJob?.cancel(); socketJob = null
        bridgeScope?.coroutineContext?.get(Job)?.cancel()
        bridgeScope = null
        cellularState.value = CellularState.IDLE
        _connectError.value = null
        currentNetwork = null
    }

    // ─── Public API ─────────────────────────────────────────────────────────────

    /**
     * Connect to the TCP relay server on [host]:[port], using the cellular network
     * if available. Call after init(); failure does not throw — check [cellularState].
     */
    fun connect(host: String, port: Int) {
        relayHost = host
        relayPort = port
        bridgeScope?.launch {
            doConnect()
        }
    }

    /**
     * Enqueue one Opus frame for cellular delivery.
     * Writes [FRAME_HEADER][len LE][opus payload] to the TCP output stream.
     * Returns false if the socket is not connected or the write fails.
     */
    fun sendFrame(data: ByteArray): Boolean {
        val out = peerOutput ?: run {
            Log.w(TAG, "sendFrame: socket not connected")
            return false
        }
        if (data.size > MAX_FRAME_BYTES) {
            Log.w(TAG, "sendFrame: payload ${data.size} B exceeds MAX_FRAME_BYTES=$MAX_FRAME_BYTES")
            return false
        }
        return try {
            val buf = ByteArray(FRAME_OVERHEAD + data.size)
            buf[0] = FRAME_HEADER
            // uint16 LE: payload length
            buf[1] = (data.size and 0xFF).toByte()
            buf[2] = ((data.size ushr 8) and 0xFF).toByte()
            System.arraycopy(data, 0, buf, FRAME_OVERHEAD, data.size)
            synchronized(out) { out.write(buf) }
            true
        } catch (e: IOException) {
            Log.e(TAG, "sendFrame: write failed — ${e.message}", e)
            cellularState.value = CellularState.FAILED
            _connectError.value = e.message
            false
        }
    }

    /**
     * Pull one raw Opus payload from the inbound TCP stream.
     * Blocking call — invoke from a coroutine on Dispatchers.Default.
     * Returns null if the queue is empty (PLC / no data this tick).
     */
    fun takeCellularFrame(): ByteArray? = inboundQueue.poll()

    // ─── Private internals ──────────────────────────────────────────────────────

    private suspend fun doConnect() {
        cellularState.value = CellularState.CHECKING
        val net = currentNetwork
        val socket = if (net != null) {
            Log.i(TAG, "doConnect: binding socket to cellular network $net")
            Socket().also { net.bindSocket(it) }
        } else {
            Log.i(TAG, "doConnect: no cellular network yet, using default route")
            Socket()
        }
        try {
            socket.connect(InetSocketAddress(relayHost, relayPort), 5_000)
            peerSocket = socket
            peerInput = socket.getInputStream()
            peerOutput = socket.getOutputStream()
            cellularState.value = CellularState.AVAILABLE
            Log.i(TAG, "doConnect: connected to $relayHost:$relayPort")
            // Start the inbound read pump
            socketJob = bridgeScope!!.launch { readPump(socket.getInputStream()) }
        } catch (e: IOException) {
            Log.e(TAG, "doConnect: failed — ${e.message}", e)
            cellularState.value = CellularState.FAILED
            _connectError.value = e.message
            try { socket.close() } catch (_: IOException) { }
        }
    }

    /**
     * Parses [FRAME_HEADER][len][opus…] frames off the TCP byte stream and
     * pushes each Opus payload into inboundQueue for the engine to consume.
     */
    private suspend fun readPump(input: InputStream) {
        withContext(Dispatchers.IO) {
            val headerBuf = ByteArray(FRAME_OVERHEAD)
            val payloadBuf = ByteArray(MAX_FRAME_BYTES)

            while (isActive) {
                // 1. Read fixed-size header
                var read = 0
                while (read < FRAME_OVERHEAD) {
                    val n = input.read(headerBuf, read, FRAME_OVERHEAD - read)
                    if (n < 0) {
                        Log.w(TAG, "readPump: stream closed by peer")
                        cellularState.value = CellularState.UNAVAILABLE
                        return@withContext
                    }
                    read += n
                }
                if (headerBuf[0] != FRAME_HEADER) {
                    Log.w(TAG, "readPump: unexpected byte 0x${(headerBuf[0].toInt() and 0xFF).toString(16)} — resync")
                    continue
                }
                val payloadLen = (headerBuf[2].toInt() and 0xFF).let { hi ->
                    ((headerBuf[1].toInt() and 0xFF) or (hi shl 8))
                }
                if (payloadLen <= 0 || payloadLen > MAX_FRAME_BYTES) {
                    Log.w(TAG, "readPump: invalid payload length $payloadLen — resync")
                    continue
                }
                // 2. Read payload
                read = 0
                while (read < payloadLen) {
                    val n = input.read(payloadBuf, read, payloadLen - read)
                    if (n < 0) {
                        Log.w(TAG, "readPump: stream closed mid-payload")
                        cellularState.value = CellularState.UNAVAILABLE
                        return@withContext
                    }
                    read += n
                }
                val frame = payloadBuf.copyOf(payloadLen)
                if (!inboundQueue.offer(frame)) {
                    Log.w(TAG, "readPump: inboundQueue full — dropping frame ($payloadLen B)")
                }
            }
        }
    }

    // ─── Availability probe ─────────────────────────────────────────────────────

    private fun probeCellular(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()

        cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Cellular network AVAILABLE: $network")
                currentNetwork = network
                cellularState.value = CellularState.AVAILABLE
                // Auto-reconnect if socket exists in a bad state
                peerSocket?.let { s -> bridgeScope?.launch { if (s.isClosed || s.isInputShutdown || s.isOutputShutdown) doConnect() } }
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Cellular network LOST")
                if (currentNetwork == network) {
                    currentNetwork = null
                    if (cellularState.value == CellularState.AVAILABLE) {
                        cellularState.value = CellularState.UNAVAILABLE
                    }
                }
            }

            override fun onUnavailable() {
                Log.w(TAG, "Cellular network UNAVAILABLE")
                cellularState.value = CellularState.UNAVAILABLE
            }
        })
    }
}
