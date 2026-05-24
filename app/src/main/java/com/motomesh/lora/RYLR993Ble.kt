package com.motomesh.lora

import android.Manifest
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
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * BLE GATT driver for the RYLR993 LoRa module.
 *
 * Workflows:
 *  - Bonded connect   : connect() scans bonded devices → connect to first match
 *                       (two-way mesh, production path)
 *  - Scan + connect   : scanForDevices() discovers module by advertising name →
 *                       caller picks → connectToDeviceSync() connects directly
 *                       (direct-link test, no prior bonding needed)
 *
 * GATT UUIDs (RYLR993 datasheet):
 *   Service   : 0000fff0-0000-1000-8000-00805f9b34fb
 *   Write char: 0000fff1-0000-1000-8000-00805f9b34fb
 *   Notify    : 0000fff2-0000-1000-8000-00805f9b34fb
 */
object RYLR993Ble {

    private const val TAG = "RYLR993Ble"

    private val SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val CHAR_WRITE:  UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
    private val CHAR_NOTIFY: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    private val _rxPackets = MutableStateFlow<ByteArray?>(null)
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // ─── Initialize ─────────────────────────────────────────────┐
    // Scans and connects should be called after this returns true.  │
    // Caller must have BLUETOOTH_CONNECT (+ SCAN on Android 12+).   │
    // ─────────────────────────────────────────────────────────────│

    @SuppressLint("MissingPermission")
    fun initialize(context: Context): Boolean {
        if (bluetoothAdapter != null) return true
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter
        return bluetoothAdapter != null && bluetoothAdapter!!.isEnabled
    }

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

    // ─── Bonded-device connect (two-way mesh) ─────────────────────┐
    // Finds the first system-bonded device whose name starts with     │
    // deviceNamePrefix. Pair in system Bluetooth first, then call ⊕  │
    // ───────────────────────────────────────────────────────────────//

    @SuppressLint("MissingPermission")
    fun connect(deviceNamePrefix: String = "RYLR993_") {
        _connectionState.value = ConnectionState.CONNECTING
        val adapter = bluetoothAdapter ?: run {
            _connectionState.value = ConnectionState.FAILED
            return
        }
        val device: BluetoothDevice? = adapter.bondedDevices
            .firstOrNull { it.name?.startsWith(deviceNamePrefix) == true }

        if (device == null) {
            Log.e(TAG, "No bonded RYLR993 device found — pair in system Bluetooth first")
            _connectionState.value = ConnectionState.FAILED
            return
        }

        val cb = GattConnectCallback()
        gatt = device.connectGatt(null, false, cb)

        _connectionState.value = if (cb.await()) ConnectionState.CONNECTED
        else ConnectionState.FAILED
    }

// ─── Scan → direct connect (new, no bonding required for the link) ────────
// Scans for 8 s, filters by the RYLR993 service UUID 0000FFF0.
// Returns all found module addresses; the activity shows a picker dialog.
// Call on the Main thread. Caller must hold BLUETOOTH_SCAN permission.

@SuppressLint("MissingPermission")
fun scanForDevices(timeoutMs: Long = 8_000L): List<BluetoothDevice> {
    val adapter = bluetoothAdapter ?: return emptyList()
    val scanner = adapter.bluetoothLeScanner ?: run {
        Log.w(TAG, "BLE scanner not available")
        return emptyList()
    }

    val found = mutableListOf<BluetoothDevice>()
    val latch = java.util.concurrent.CountDownLatch(1)

    val cb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            if (dev.name?.startsWith("RYLR993_") == true && dev !in found) {
                found += dev
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed: error=$errorCode")
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }
    }

    return try {
        var useFilter = true
        val filt = ScanFilter.Builder()
            .setServiceUuid(android.os.ParcelUuid(SERVICE_UUID))
            .build()
        scanner.startScan(listOf(filt), ScanSettings.Builder().build(), cb)

        if (!useFilter) {
            scanner.startScan(cb)
        }

        latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        found
    } finally {
        try { scanner.stopScan(cb) } catch (_: Exception) { /* ignore */ }
    }
}

    /**
     * Connect to an already-known BluetoothDevice (e.g. from scan results).
     * Returns true if connectGatt was accepted (not yet confirmed connected).
     */
    @SuppressLint("MissingPermission")
    fun connectToDeviceSync(device: BluetoothDevice): Boolean {
        if (_connectionState.value in listOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED)) return false
        _connectionState.value = ConnectionState.CONNECTING

        val cb = GattConnectCallback()
        gatt = device.connectGatt(null, false, cb)

        val ok = cb.await(15, java.util.concurrent.TimeUnit.SECONDS)
        _connectionState.value = if (ok) ConnectionState.CONNECTED else ConnectionState.FAILED
        return ok
    }

    // ─── Disconnect ──────────────────────────────────────────────┐
    // Closes the GATT link and resets state to DISCONNECTED / fills  │
    // the rx-packets channel with null to clear any stale frames.    │
    // ──────────────────────────────────────────────────────────────│

    @SuppressLint("MissingPermission")
    fun disconnect() {
        try {
            gatt?.let {
                it.disconnect()
                it.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "disconnect error: ${e.message}")
        }
        gatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        _rxPackets.value = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    // ─── Transmit ────────────────────────────────────────────────┐
    // Writes one raw LoRa frame into the BLE write characteristic.   │
    // Caller-calls this from LoRaDriver.sendPacket() (which already   │
    // serialises frames to avoid GATT flood).                         │
    // ──────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    suspend fun sendPacket(raw: ByteArray) {
        val wr = writeCharacteristic ?: return
        @Suppress("DEPRECATION") // setValue(byte[]) deprecated API 33 but still works
        wr.value = raw
        val ok = gatt?.writeCharacteristic(wr) ?: false
        if (!ok) Log.w(TAG, "GATT write failed")
    }

    fun rxPackets(): StateFlow<ByteArray?> = _rxPackets.asStateFlow()

    // ─── BLE GATT callback ───────────────────────────────────────

    @SuppressLint("MissingPermission")
    private class GattConnectCallback : BluetoothGattCallback() {

        private val latch = java.util.concurrent.CountDownLatch(1)
        private var ok = false

        fun await(timeoutMs: Long = 10_000L, unit: java.util.concurrent.TimeUnit = java.util.concurrent.TimeUnit.MILLISECONDS): Boolean {
            latch.await(timeoutMs, unit)
            return ok
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ok = false
                latch.countDown()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val svc = gatt.getService(SERVICE_UUID) ?: run {
                ok = false; latch.countDown(); return
            }
            writeCharacteristic  = svc.getCharacteristic(CHAR_WRITE)
            notifyCharacteristic = svc.getCharacteristic(CHAR_NOTIFY)

            notifyCharacteristic?.let { ch ->
                gatt.setCharacteristicNotification(ch, true)
                val cccd = ch.getDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                )
                cccd?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                cccd?.let { gatt.writeDescriptor(it) }
            }

            ok = writeCharacteristic != null
            latch.countDown()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == CHAR_NOTIFY) {
                characteristic.value?.let { _rxPackets.value = it }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "GATT write failed: status=$status")
            }
        }
    }
}
