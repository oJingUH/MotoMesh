package com.motomesh.lora

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * BLE driver for the RYLR993 LoRa module.
 *
 * RYLR993 exposes a GATT serial service. Packet exchange flows over
 * a single notify characteristic; we write commands to the write characteristic.
 *
 * BLE characteristic UUIDs (from RYLR993 datasheet):
 *   Service   : 0000fff0-0000-1000-8000-00805f9b34fb
 *   Rx (write → module) : 0000fff1-0000-1000-8000-00805f9b34fb
 *   Tx (notify ← module) : 0000fff2-0000-1000-8000-00805f9b34fb
 *
 * The module streams binary packets in the ASCII raw-response format.
 * We receive the string "AT+..." on Rx and the binary LoRa payload on Tx.
 * This driver handles the protocol at the GATT transport layer; packet
 * dissection (Signaturebyte, preamble, RSSI + SNR) is done by the caller.
 */
object RYLR993Ble {

    private const val TAG = "RYLR993Ble"

    // ─── GATT UUIDs ─────────────────────────────────────────────

    private val SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val CHAR_WRITE: UUID  = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
    private val CHAR_NOTIFY: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")

    // ─── State ──────────────────────────────────────────────────

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    // Inbound binary packets from the LoRa radio — null means no packet received
    private val _rxPackets = MutableStateFlow<ByteArray?>(null)
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /**
     * Call at app init from a Context. Acquires the BluetoothAdapter.
     * Returns true if BLE is present and Bluetooth is ON.
     */
    @SuppressLint("MissingPermission")
    fun initialize(context: Context): Boolean {
        if (bluetoothAdapter != null) return true
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter
        return bluetoothAdapter != null && bluetoothAdapter!!.isEnabled
    }

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

    /**
     * Connect to a paired RYLR993 device by name prefix (e.g. "RYLR993_").
     * Blocks até connected or failed. Call from a background thread.
     */
    fun connect(deviceNamePrefix: String = "RYLR993_") {
        _connectionState.value = ConnectionState.CONNECTING
        val adapter = bluetoothAdapter ?: run {
            _connectionState.value = ConnectionState.FAILED
            return
        }
        val device: BluetoothDevice? = adapter.bondedDevices
            .firstOrNull { it.name.startsWith(deviceNamePrefix) }

        if (device == null) {
            Log.e(TAG, "No bonded RYLR993 device found — pair in system Bluetooth first")
            _connectionState.value = ConnectionState.FAILED
            return
        }

        val callback = GattConnectCallback()
        gatt = device.connectGatt(null, false, callback)

        _connectionState.value = if (callback.await()) ConnectionState.CONNECTED
        else ConnectionState.FAILED
    }

    /**
     * Transmit a binary LoRa packet to the LoRa module for over-the-air broadcast.
     * The module will forward it through its modem; the actual LoRa frequency/spreading factor
     * configuration is done on the module itself via AT commands before use.
     */
    @SuppressLint("MissingPermission")
    suspend fun sendPacket(raw: ByteArray) {
        val wr = writeCharacteristic ?: return
        setGattCharacteristicValue(wr, raw)
        val success = gatt?.writeCharacteristic(wr) ?: false
        if (!success) Log.w(TAG, "GATT write failed")
    }

    /**
     * Observe inbound binary LoRa payloads from other riders.
     * Each item is a raw packet ByteArray or null (no packet this tick).
     */
    fun rxPackets(): Flow<ByteArray?> = _rxPackets.asStateFlow()

    // ─── Disconnect ─────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun disconnect() {
        try {
            gatt?.let {
                it.writeCharacteristic(writeCharacteristic)
                it.disconnect()
                it.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "disconnect error: ${e.message}")
        }
        gatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    // ─── GATT helpers ───────────────────────────────────────────

    private fun setGattCharacteristicValue(c: BluetoothGattCharacteristic, data: ByteArray) {
        @Suppress("DEPRECATION") // setValue(byte[]) deprecated from API 33, still works
        c.value = data
    }

    // ─── BluetoothGattCallback ───────────────────────────────────

    private class GattConnectCallback : BluetoothGattCallback() {

        // A minimal condvar to turn the callback into a suspend result
        private val result = java.util.concurrent.CountDownLatch(1)
        private var ok = false

        fun await(): Boolean {
            result.await()
            return ok
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ok = false
                result.countDown()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val svc = gatt.getService(SERVICE_UUID) ?: run {
                ok = false; result.countDown(); return
            }
            writeCharacteristic = svc.getCharacteristic(CHAR_WRITE)
            notifyCharacteristic = svc.getCharacteristic(CHAR_NOTIFY)

            notifyCharacteristic?.let { ch ->
                gatt.setCharacteristicNotification(ch, true)
                // RYLR993 requires this to actually deliver notifications
                val cccd = ch.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                cccd?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                cccd?.let { gatt.writeDescriptor(it) }
            }

            if (writeCharacteristic != null) {
                ok = true
            } else {
                ok = false
            }
            result.countDown()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == CHAR_NOTIFY) {
                characteristic.value?.let { packet ->
                    // Push into StateFlow; MeshEngine.rxFrames collects it
                    _rxPackets.value = packet
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Write failed: status=$status")
            }
        }
    }
}
