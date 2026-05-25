package com.motomesh.mesh

import android.content.Context
import android.util.Log
import com.motomesh.audio.OpusCodec
import com.motomesh.cellular.CellularBridge
import com.motomesh.lora.LoRaDriver
import kotlinx.coroutines.*
import java.util.concurrent.LinkedBlockingQueue

/**
 * MotoMeshEngine — the central bridge between audio pipeline, BLE transport,
 * and mesh forwarding.
 *
 * Launches two job trees from a single SupervisorJob:
 *  1. Rx pump — LoRa BLE notify → inboundQueue → MeshForwarder → AudioPipeline
 *  2. Tx pump — AudioPipeline encodes → outboundQueue → LoRaDriver → LoRa RF
 *
 * Call start() once (from MotoMeshService onStartCommand/onCreate),
 * call stop() once (from MotoMeshService.onDestroy).
 */
object MotoMeshEngine {

    const val TAG = "MotoMeshEngine"

    // ─── State ─────────────────────────────────────────────────────
    private var scope: CoroutineScope? = null

    // Inbound queue: raw LoRa BLE payloads → AudioPipeline rxLoop
    private val inboundQueue = LinkedBlockingQueue<ByteArray>(256)

    // Outbound queue: AudioPipeline txLoop encodes → LoRaDriver → radio
    private val outboundQueue = LinkedBlockingQueue<ByteArray>(512)

    private var transportMode: TransportMode = TransportMode.LOOPBACK
    private var localNodeId: Int = 0

    /** Public read-only access to the local node ID (set during start()). */
    val thisNodeId: Int get() = localNodeId

    enum class TransportMode { LOOPBACK, LORA, CELLULAR }

    // ─── Entry / Exit ───────────────────────────────────────────────
    fun start(context: Context, parentScope: CoroutineScope, transport: TransportMode = TransportMode.LOOPBACK) {
        this.transportMode = transport
        scope = CoroutineScope(SupervisorJob() + parentScope.coroutineContext)

        val tag = TAG  // capture for log lambdas

        // Node identity
        localNodeId = MeshForwarder.assignNodeId()
        Log.i(tag, "This rider is node $localNodeId  transport=$transport")

        when (transport) {
            TransportMode.LORA -> {
                // Production path: open LoRa BLE and start rx/tx pumps
                LoRaDriver.open(context)

                // Rx pump: BLE notify → publish RSSI → MeshForwarder → inbound queue
                scope!!.launch(Dispatchers.Default) {
                    LoRaDriver.rxFrames.collect { raw ->
                        if (raw != null) {
                            LoRaDriver.publishRssi(raw)
                            enqueueInbound(raw)
                        }
                    }
                }

                // Tx pump: drain outboundQueue → LoRaDriver.sendPacket()
                scope!!.launch(Dispatchers.Default) {
                    while (isActive) {
                        val packet = outboundQueue.take()
                        LoRaDriver.sendPacket(packet)
                    }
                }
            }
            TransportMode.LOOPBACK -> {
                Log.i(tag, "Loopback mode — LoRa BLE + TCP relay stacks skipped; rxLoop is self-bound")
            }
            TransportMode.CELLULAR -> {
                CellularBridge.init(context)
                // Inbound callback: register the speaking rider in NodeTable
                CellularBridge.onInboundFrame = { nodeId, _ ->
                    NodeTable.touch(nodeId, 0)
                }
                // Tx pump already handled in txFrame() → CellularBridge.sendFrame()
                // Rx pump: drain cellular TCP inbound → engine inboundQueue
                scope!!.launch(Dispatchers.Default) {
                    while (isActive) {
                        val frame = CellularBridge.takeCellularFrame()
                        if (frame != null) {
                            enqueueInbound(frame)
                        } else {
                            delay(10)   // nothing this tick, yield to dispatcher
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        scope?.coroutineContext?.get(Job)?.cancel()
        scope = null
        LoRaDriver.close()
        CellularBridge.onInboundFrame = null
        CellularBridge.close()
        inboundQueue.clear()
        outboundQueue.clear()
    }

    // ─── Public API ─────────────────────────────────────────────────

    /** Called by AudioPipeline txLoop — schedule one voice frame for broadcast or loopback. */
    fun txFrame(opusPayload: ByteArray) {
        when (transportMode) {
            TransportMode.LOOPBACK -> {
                // Direct ringback: encoded frame goes straight into the inbound queue
                // that rxLoop is already polling. Zero latency, no GATT, no radio.
                inboundQueue.offer(opusPayload)
            }
            TransportMode.LORA -> {
                val packet = MeshForwarder.buildOutbound(opusPayload)
                outboundQueue.offer(packet)
            }
            TransportMode.CELLULAR -> {
                // Send Opus frame via TCP to relay server with this rider's node ID
                CellularBridge.sendFrame(opusPayload, nodeId = localNodeId)
            }
        }
    }

    /** Polled by AudioPipeline rxLoop — returns next inbound Opus frame or null (PLC). */
    fun takeInboundFrame(): ByteArray? = inboundQueue.poll()

    /**
     * Central inbound integration point.
     *
     * Called when a packet arrives from any transport (LoRa BLE notify, Cellular TCP, or loopback).
     *
     * **LoRa**: passes through MeshForwarder.processIncoming() for:
     *          - dedup (seen-table, 5s sliding window)
     *          - TTL-limited flood-gossip re-forwarding (increments node ID byte as hop count)
     *          - node ID extraction → NodeTable registration
     *          - header stripping → pushes pure Opus payload to inboundQueue for AudioPipeline
     *
     * **Cellular**: pushes raw Opus frames through directly (node-ID already extracted
     *             via onInboundFrame callback → NodeTable registration).
     *
     * **Loopback**: pushes raw Opus frames directly (self-ringback).
     */
    fun enqueueInbound(packet: ByteArray) {
        when (transportMode) {
            TransportMode.LORA -> {
                // Run through MeshForwarder for dedup, re-forwarding, and node-ID extraction
                val forwarded = mutableListOf<ByteArray>()
                val result = MeshForwarder.processIncoming(packet, forwarded)
                if (result != null) {
                    val (nodeId, _) = result
                    // Re-forward to mesh (flood-gossip TTL, MeshForwarder handles hop increment)
                    for (fwd in forwarded) {
                        outboundQueue.offer(fwd)
                    }
                    // Register in node table with current RSSI from LoRaDriver
                    val rssi = LoRaDriver.loRaRssi.value ?: 0
                    NodeTable.touch(nodeId.toInt(), rssi)
                    // Strip 4-byte MeshForwarder header → push pure Opus payload to decoder
                    val opusPayload = packet.copyOfRange(MeshForwarder.PAYLOAD_OFFSET, packet.size)
                    if (!inboundQueue.offer(opusPayload)) {
                        Log.w(TAG, "Inbound queue saturated — dropping LoRa frame")
                    }
                } // else: duplicate or self-frame — drop silently
            }
            TransportMode.CELLULAR -> {
                // Cellular frames arrive as raw Opus payloads (CellularBridge strips TCP framing)
                if (!inboundQueue.offer(packet)) {
                    Log.w(TAG, "Inbound queue saturated — dropping cellular frame")
                }
            }
            TransportMode.LOOPBACK -> {
                // Raw Opus frames, direct ringback
                if (!inboundQueue.offer(packet)) {
                    Log.w(TAG, "Inbound queue saturated — dropping loopback frame")
                }
            }
        }
    }
}
