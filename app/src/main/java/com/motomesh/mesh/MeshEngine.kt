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

                // Rx pump: BLE notify → inboundQueue
                scope!!.launch(Dispatchers.Default) {
                    LoRaDriver.rxFrames.collect { raw ->
                        raw?.let { enqueueInbound(it) }
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
                Log.i(tag, "Cellular transport — LoRa BLE stack skipped; tx pump → CellularBridge")
            }
        }
    }

    fun stop() {
        scope?.coroutineContext?.get(Job)?.cancel()
        scope = null
        LoRaDriver.close()
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
                // Phase 2: send via TCP relay stub — CellularBridge handles framing
                CellularBridge.sendFrame(opusPayload)
            }
        }
    }

    /** Polled by AudioPipeline rxLoop — returns next inbound Opus frame or null (PLC). */
    fun takeInboundFrame(): ByteArray? = inboundQueue.poll()

    /** Called from LoRaDriver BLE notify — push raw packet into engine. */
    fun enqueueInbound(packet: ByteArray) {
        if (!inboundQueue.offer(packet)) {
            Log.w(TAG, "Inbound queue saturated — dropping packet")
        }
    }
}
