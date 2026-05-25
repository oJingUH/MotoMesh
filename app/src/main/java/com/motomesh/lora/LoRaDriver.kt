package com.motomesh.lora

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.motomesh.lora.RYLR993Ble
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * LoRaDriver — thin state machine over RYLR993Ble.
 *
 * Public API:
 *   open(context)            — init BLE adapter
 *   scanForDevices()         — suspend; returns list of nearby RYLR993 modules
 *   connectToDevice(device)  — suspend; connects by BluetoothDevice reference
 *   connect()                — suspend; connects to first bonded RYLR993 (two-way mesh)
 *   rxFrames                 — StateFlow<ByteArray?> from BLE notify
 *   connectionState          — MutableStateFlow<ConnectionState> — read + write from UI
 *   sendPacket(raw)          — write one frame to the GATT (serialised)
 *   close()                  — BLE disconnect + reset
 *   loRaRssi                 — StateFlow<Int?> — RSSI dBm or null when not connected
 */
object LoRaDriver {

    const val TAG = "LoRaDriver"

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

    // Public state flows
    val connectionState: MutableStateFlow<ConnectionState> =
        MutableStateFlow(ConnectionState.DISCONNECTED)

    private val _rssi = MutableStateFlow<Int?>(null)
    val loRaRssi: StateFlow<Int?> = _rssi.asStateFlow()

    private var txInFlight = false

    // ─── Initialise ────────────────────────────────────────────────

    private var _appContext: Context? = null
    private fun appContext(): Context =
        _appContext ?: error("LoRaDriver.open() has not been called yet")

    fun open(context: Context): Boolean =
        RYLR993Ble.initialize(context).also { ok ->
            _appContext = context.applicationContext
            Log.d(TAG, "BLE adapter ready=$ok")
        }

    // ─── Scan ──────────────────────────────────────────────────────

    /** Discover nearby RYLR993 modules by service UUID 0000FFF0. */
    suspend fun scanForDevices(): List<BluetoothDevice> =
        withContext(Dispatchers.Main.immediate) {
            RYLR993Ble.scanForDevices(timeoutMs = 8_000L)
        }

    // ─── Connect ──────────────────────────────────────────────────

    /** Connect by [device] — works with bonded and freshly-scanned modules. */
    suspend fun connectToDevice(device: BluetoothDevice) {
        if (connectionState.value in listOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED)) return
        connectionState.value = ConnectionState.CONNECTING
        try {
            val ok = withContext(Dispatchers.Default) {
                RYLR993Ble.connectToDeviceSync(device)
            }
            if (ok) {
                configureModule(channelConfig())
            } else {
                connectionState.value = ConnectionState.FAILED
            }
        } catch (e: Exception) {
            Log.e(TAG, "ConnectToDevice: ${e.message}")
            connectionState.value = ConnectionState.FAILED
        }
    }

    /** Connect to first bonded RYLR993 device (two-way production mesh). */
    suspend fun connect() {
        if (connectionState.value in listOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED)) return
        connectionState.value = ConnectionState.CONNECTING
        try {
            // Run the blocking connectGatt + await() off the Main dispatcher so
            // the GATT callback thread (which may be Main on Pixel 9) is never
            // the same thread currently blocking on cb.await().
            withContext(Dispatchers.Default) { RYLR993Ble.connect() }
            when (RYLR993Ble.connectionState.value) {
                RYLR993Ble.ConnectionState.CONNECTED -> { configureModule(channelConfig()); connectionState.value = ConnectionState.CONNECTED }
                RYLR993Ble.ConnectionState.FAILED  -> connectionState.value = ConnectionState.FAILED
                else                               -> connectionState.value = ConnectionState.CONNECTING
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connect: ${e.message}")
            connectionState.value = ConnectionState.FAILED
        }
    }

    // ─── Tx / Rx ──────────────────────────────────────────────────

    /** Send one raw frame. Serialised to avoid flooding the GATT. */
    suspend fun sendPacket(raw: ByteArray) {
        if (txInFlight) delay(20)
        txInFlight = true
        try {
            RYLR993Ble.sendPacket(raw)
        } catch (e: Exception) {
            Log.w(TAG, "sendPacket: ${e.message}")
        } finally {
            txInFlight = false
        }
    }

    val rxFrames: StateFlow<ByteArray?> = RYLR993Ble.rxPackets() as StateFlow<ByteArray?>

    // ─── RSSI extract ─────────────────────────────────────────────

    /**
     * Extract RSSI from a raw RYLR993 packet (last byte = signed dBm value)
     * and publish it to [loRaRssi]. Called from the inbound-packet path.
     */
    fun publishRssi(raw: ByteArray?) {
        _rssi.value = raw?.let { deriveRssi(it) }
    }

    private fun deriveRssi(raw: ByteArray): Int {
        // last byte = signed raw RSSI (-128..127 range)
        return (raw.last().toInt() shl 24 shr 24)
    }

    // ─── Radio config (AT commands on the RYLR993 module) ───────────

    /** Read channel from SharedPreferences and build a RadioConfig. */
    private fun channelConfig(): RadioConfig {
        val ctx = appContext()
        val channel = ctx.getSharedPreferences("moto_settings", Context.MODE_PRIVATE)
            .getInt("channel", 0)
            .coerceIn(0, 15)
        return RadioConfig(networkId = channel)
    }

    /**
     * Radio parameters sent to the module via AT commands after BLE connects.
     * Non-zero values override defaults; pass `RadioConfig()` for EU defaults.
     */
    data class RadioConfig(
        val nodeAddress:     Int    = 0,
        val networkId:       Int    = 1,
        val frequencyMHz:    Double = 868.0,          // EU default; use 915.0 for US
        val spreadingFactor: Int    = 9,              // 7 = fast range, 12 = max range
        val bandwidthKHz:    Int    = 125,
        val codingRate:      Int    = 2,              // 0=4/5, 1=4/6, 2=4/7, 3=4/8
        val loraWanMode:     Boolean = true           // false = P2P test mode
    )

    private suspend fun configureModule(config: RadioConfig = RadioConfig()) {
        require(connectionState.value == ConnectionState.CONNECTED) {
            "LoRaDriver: must be CONNECTED before running configureModule()"
        }
        val cmds = listOf(
            "NWM=${if (config.loraWanMode) 1 else 0}",
            "ADDRESS=${config.nodeAddress}",
            "NETWORKID=${config.networkId}",
            "FREQ=${config.frequencyMHz}",
            "SF=${config.spreadingFactor}",
            "BW=${config.bandwidthKHz}",
            "CRF=${config.codingRate}"
        )
        for (cmd in cmds) {
            Log.i(TAG, "AT+$cmd  …")
            val resp = RYLR993Ble.sendAtCommand(cmd)
            check(resp.contains("+OK")) {
                "AT+$cmd returned '$resp' — expected +OK"
            }
            Log.i(TAG, "  -> $resp  ✓")
        }
        Log.i(TAG, "Module configured: $config")
    }

    // ─── Disconnect ───────────────────────────────────────────────

    fun close() {
        RYLR993Ble.disconnect()
        connectionState.value = ConnectionState.DISCONNECTED
        _rssi.value = null
        txInFlight = false
    }
}
