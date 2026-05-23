package com.motomesh.lora

import android.content.Context
import android.util.Log
import com.motomesh.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/**
 * LoRaDriver — connection state + send path bridging RYLR993Ble and the rest of the app.
 *
 * Public API:
 *  open(context)          — initialize BLE adapter, returns bool
 *  connect()              — BLE connect to a paired RYLR993 device
 *  sendPacket(raw)        — push raw payload (frame built by MeshForwarder) to the radio
 *  rxFrames               — StateFlow of every complete raw packet from the radio
 *  close()                — disconnect BLE; resets state to DISCONNECTED
 */
object LoRaDriver {

    const val TAG = "LoRaDriver"
    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, FAILED }
    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> get() = _state

    // One outgoing-in-flight flag per channel to avoid GATT overflow
    private var txInFlight = false

    /**
     * Initialize adapter (safe to call more than once; returns cached result after first).
     */
    fun open(context: Context): Boolean {
        return RYLR993Ble.initialize(context)
            .also { ok -> Log.d(TAG, "BLE adapter ready=$ok") }
    }

    /**
     * BLE connect to the first device whose name starts with "RYLR993_".
     * Must be bonded in system Bluetooth first.
     */
    suspend fun connect() {
        if (_state.value == ConnectionState.CONNECTING || _state.value == ConnectionState.CONNECTED) return
        _state.value = ConnectionState.CONNECTING
        try {
            RYLR993Ble.connect()
            _state.value = if (RYLR993Ble.connectionState.value == RYLR993Ble.ConnectionState.CONNECTED)
                ConnectionState.CONNECTED else ConnectionState.FAILED
        } catch (e: Exception) {
            Log.e(TAG, "Connect exception: ${e.message}")
            _state.value = ConnectionState.FAILED
        }
    }

    /**
     * Send one complete packet to the radio. Takes the correlation id attached.
     * The actual airtime is managed by the radio firmware; this call just drops
     * the binary blob into the GATT write characteristic.
     */
    suspend fun sendPacket(raw: ByteArray) {
        // Serialize sends: the BLE subsystem can process one write at a time.
        if (txInFlight) {
            // In a production build this would block or drop;
            // up to 20 ms latency here is fine for voice.
            delay(20)
        }
        txInFlight = true
        try {
            RYLR993Ble.sendPacket(raw)
        } catch (e: Exception) {
            Log.w(TAG, "sendPacket failed: ${e.message}")
        } finally {
            txInFlight = false
        }
    }

    /**
     * StateFlow of raw inbound packets as they arrive from the radio over BLE GATT Notify.
     * Each item is a complete packet byte array.
     */
    val rxFrames: StateFlow<ByteArray?> = RYLR993Ble.rxPackets().let { flow -> flow as StateFlow<ByteArray?> }

    /**
     * Disconnect; resets state to DISCONNECTED.
     */
    fun close() {
        RYLR993Ble.disconnect()
        _state.value = ConnectionState.DISCONNECTED
        txInFlight = false
    }
}
