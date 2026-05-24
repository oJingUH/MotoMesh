package com.motomesh.ui

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.motomesh.R
import com.motomesh.databinding.ActivityMainBinding
import com.motomesh.lora.LoRaDriver
import com.motomesh.mesh.MotoMeshEngine
import com.motomesh.mesh.MotoMeshEngine.TransportMode
import com.motomesh.mesh.NodeRecord
import com.motomesh.mesh.NodeTable
import com.motomesh.service.MotoMeshService
import com.motomesh.cellular.CellularBridge

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * MainActivity — entry point.
 *
 * Transport modes:
 *   LOOPBACK   — mic → Opus → headphones, no network at all
 *   LORA       — mic → Opus → RYLR993 BLE → LoRa radio to nearby riders
 *   CELLULAR    — mic → Opus → TCP relay socket → cellular network
 *
 * Bottom status bar always shows: transport dot · mode label · RSSI (or N/A)
 *                               · node count · VOX dot
 *
 * Rider list shows all NodeRecords from NodeTable with a motorcycle icon that
 * lights up bright while the rider is alive and dims when they go stale.
 */
class MainActivity : ComponentActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var nodeAdapter: NodeAdapter

    // Transport mode — enums live in MotoMeshEngine.TransportMode
    private var transportMode = TransportMode.LOOPBACK

    // Mute state
    private var isMuted = false

    // Permission handle
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val allOk = granted.values.all { it }
        if (!allOk) {
            Toast.makeText(this, "Permissions required for audio + transport", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        if (transportMode == TransportMode.CELLULAR) {
            val relayHost = if (android.os.Build.FINGERPRINT.contains("generic")) "10.0.2.2" else "0.0.0.0"
            CellularBridge.init(this)
            CellularBridge.connect(relayHost, 60005)
            observeCellular()
            Log.i("MotoMesh", "permLauncher: CELLULAR perms OK -> connecting to $relayHost:60005")
        }
    }

    // Observer jobs — cancel on stop/transition
    private var connStateJob: Job? = null
    private var rssiJob: Job? = null
    private var cellularObserver: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        setupRecycler()
        setupButtons()
        requestPermissions(transportMode == TransportMode.LOOPBACK)
        MotoMeshService.start(this, transport = transportMode)
        updateConnectButton()
    }

    // ─── Recycler ───────────────────────────────────────────────────

    private fun setupRecycler() {
        nodeAdapter = NodeAdapter { node ->
            // TODO: node detail — RSSI + loss-rate history overlay
        }
        b.nodeList.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = nodeAdapter
        }
    }

    // ─── Node table observer ─────────────────────────────────────────

    private fun observeNodeTable() {
        lifecycleScope.launchWhenStarted {
            while (isActive) {
                val snapshot = NodeTable.snapshot
                nodeAdapter.submitList(snapshot)
                val count = snapshot.size
                b.tvNodeCount.text = if (count == 1) "1 node" else "$count nodes"
                delay(250)
            }
        }
    }

    // ─── Connection state observer (LoRa) ────────────────────────────

    private fun observeConnectionState() {
        connStateJob?.cancel()
        connStateJob = lifecycleScope.launchWhenStarted {
            LoRaDriver.connectionState.collectLatest { state ->
                val (dotColor, dotText) = when (state) {
                    LoRaDriver.ConnectionState.DISCONNECTED -> R.color.lo_red    to R.string.conn_disconnected
                    LoRaDriver.ConnectionState.CONNECTING  -> R.color.lo_yellow to R.string.conn_connecting
                    LoRaDriver.ConnectionState.CONNECTED   -> R.color.lo_green  to R.string.conn_connected
                    LoRaDriver.ConnectionState.FAILED      -> R.color.lo_red    to R.string.conn_disconnected
                }
                b.vDot.setBackgroundColor(ContextCompat.getColor(this@MainActivity, dotColor))
                b.tvConnState.text = getString(dotText)
                b.tvConnState.setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (state == LoRaDriver.ConnectionState.CONNECTED) R.color.lo_green else R.color.lo_red
                    )
                )
                b.tvConnMode.setText(R.string.conn_lora)
                updateConnectButton()
            }
        }
    }

    // ─── RSSI observer (LoRa) ────────────────────────────────────────

    private fun observeRssi() {
        rssiJob?.cancel()
        rssiJob = lifecycleScope.launchWhenStarted {
            LoRaDriver.loRaRssi.collectLatest { rssi ->
                b.tvRssi.text = rssi?.let { "$it dBm" } ?: getString(R.string.hint_rssi)
            }
        }
    }

    // ─── Cellular state observer ────────────────────────────────────

    private fun observeCellular() {
        cellularObserver?.cancel()
        cellularObserver = lifecycleScope.launchWhenStarted {
            CellularBridge.cellularState.collect { state ->
                val (dotColor, dotText) = when (state) {
                    CellularBridge.CellularState.AVAILABLE -> R.color.lo_green to R.string.conn_cellular_connected
                    CellularBridge.CellularState.CHECKING  -> R.color.lo_yellow to R.string.conn_cellular_connecting
                    else -> R.color.lo_red to R.string.conn_cellular_failed
                }
                b.vDot.setBackgroundColor(ContextCompat.getColor(this@MainActivity, dotColor))
                b.tvConnState.text = getString(dotText)
                b.tvConnState.setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (state == CellularBridge.CellularState.AVAILABLE) R.color.lo_green else R.color.lo_red
                    )
                )
                b.tvConnMode.setText(R.string.conn_cellular_connected)
                updateConnectButton()
            }
        }
    }

    // ─── Transport-choice dialog (loopback entry point) ───────────────

    /**
     * Called when the connect button is pressed while we are in loopback mode.
     * Presents a two-choice dialog: LoRa BLE or WiFi Direct.
     */
    private fun askTransport() {
        val items = arrayOf(getString(R.string.transport_lora), getString(R.string.transport_cellular))
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.dialog_pick_transport_title)
            .setAdapter(adapter) { _, which ->
                when (which) {
                    0 -> transitionToLoRa()
                    1 -> transitionToCellular()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ─── Connect / disconnect button ─────────────────────────────────

    private fun updateConnectButton() {
        val state = currentConnectionState()
        // Cellular: enabling logic will be added in phase 2 (TCP relay connected check)
        val isConnecting = state is LoRaDriver.ConnectionState && state == LoRaDriver.ConnectionState.CONNECTING
        b.btnConnect.isEnabled = !isConnecting
        b.btnConnect.text = when (transportMode) {
            TransportMode.LOOPBACK -> getString(R.string.btn_connect)
            TransportMode.LORA -> when (state) {
                LoRaDriver.ConnectionState.CONNECTED -> "Disconnect"
                LoRaDriver.ConnectionState.CONNECTING -> getString(R.string.conn_scanning)
                else -> getString(R.string.btn_connect)
            }
            TransportMode.CELLULAR -> when (state) {
                CellularBridge.CellularState.AVAILABLE -> "Disconnect"
                CellularBridge.CellularState.CHECKING -> getString(R.string.conn_connecting)
                else -> getString(R.string.btn_connect)
            }
        }
    }

    /**
     * Returns the relevant connection state depending on the active transport.
     * Used by updateConnectButton() to avoid type-cast errors.
     */
    private fun currentConnectionState(): Any = when (transportMode) {
        TransportMode.LORA -> LoRaDriver.connectionState.value
        TransportMode.CELLULAR -> CellularBridge.cellularState.value
        else -> ""   // LOOPBACK — no connection state to report
    }

    private fun setupButtons() {
        b.btnConnect.setOnClickListener {
            Log.d("TAP", "btnConnect tapped — transport=$transportMode")
            when (transportMode) {
                TransportMode.LOOPBACK -> askTransport()
                TransportMode.LORA -> when (LoRaDriver.connectionState.value) {
                    LoRaDriver.ConnectionState.CONNECTED -> disconnectLoRa()
                    LoRaDriver.ConnectionState.FAILED -> LoRaDriver.connectionState.value = LoRaDriver.ConnectionState.DISCONNECTED
                    else -> startLoRaConnect()
                }
                TransportMode.CELLULAR -> when (CellularBridge.cellularState.value) {
                    CellularBridge.CellularState.AVAILABLE -> stopCellular()
                    CellularBridge.CellularState.FAILED -> {
                        CellularBridge.cellularState.value = CellularBridge.CellularState.IDLE
                        updateConnectButton()
                    }
                    else -> startCellularTransfer()
                }
            }
        }

        b.btnMute.setOnClickListener {
            isMuted = !isMuted
            b.btnMute.text = if (isMuted) getString(R.string.btn_unmute) else getString(R.string.btn_mute)
            b.btnMute.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (isMuted) R.color.lo_red else R.color.accent_green
                )
            )
            b.vVox.isVisible = !isMuted
        }
    }

    // ─── Transport transitions ───────────────────────────────────────

    private fun transitionToLoRa() {
        stopCurrentTransport()
        transportMode = TransportMode.LORA
        MotoMeshEngine.stop()
        stopService(Intent(this, MotoMeshService::class.java))
        MotoMeshService.start(this, transport = TransportMode.LORA)
        requestPermissions(false)
        observeConnectionState()
        observeRssi()
        updateConnectButton()
        Toast.makeText(this, "Switching to LoRa — scan for a module…", Toast.LENGTH_SHORT).show()
    }

    private fun transitionToCellular() {
        stopCurrentTransport()   // cancels jobs, closes LoRa
        transportMode = TransportMode.CELLULAR
        MotoMeshEngine.stop()
        stopService(Intent(this, MotoMeshService::class.java))
        MotoMeshService.start(this, transport = TransportMode.CELLULAR)
        requestPermissions(false)
        // CellularBridge.init(this) will be called; TCP relay stub active
        updateConnectButton()
        Toast.makeText(this, "Switching to Cellular — TCP relay stub", Toast.LENGTH_SHORT).show()
    }

    /**
     * Cancel jobs and close any active transport (LoRa or WiFi), but keep
     * the mode variable at its current value so updateConnectButton() is
     * still type-safe.
     */
    private fun stopCurrentTransport() {
        connStateJob?.cancel()
        connStateJob = null
        rssiJob?.cancel()
        rssiJob = null
        cellularObserver?.cancel()
        cellularObserver = null
        LoRaDriver.close()
        MotoMeshEngine.stop()
        stopService(Intent(this, MotoMeshService::class.java))
    }

    // ─── LoRa connect flow ───────────────────────────────────────────

    private fun startLoRaConnect() {
        val scanOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED
        } else true

        if (!scanOk) {
            permLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN))
            return
        }

        lifecycleScope.launchWhenStarted {
            b.btnConnect.isEnabled = false
            b.tvConnState.setText(R.string.conn_scanning)
            b.tvConnMode.setText(R.string.conn_lora)

            try {
                val devices = LoRaDriver.scanForDevices()
                if (devices.isEmpty()) {
                    Toast.makeText(this@MainActivity, R.string.toast_no_ble_devices, Toast.LENGTH_LONG).show()
                    LoRaDriver.connectionState.value = LoRaDriver.ConnectionState.FAILED
                    updateConnectButton()
                    return@launchWhenStarted
                }
                showLoRaDevicePicker(devices)
            } catch (e: Exception) {
                Log.w("MainActivity", "BLE scan error: ${e.message}")
                Toast.makeText(this@MainActivity, "Scan failed: ${e.message}", Toast.LENGTH_LONG).show()
                updateConnectButton()
            }
        }
    }

    private fun showLoRaDevicePicker(devices: List<BluetoothDevice>) {
        val names = devices.map { it.name ?: it.address }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.dialog_scanning_title)
            .setAdapter(adapter) { _, which ->
                lifecycleScope.launchWhenStarted {
                    LoRaDriver.connectToDevice(devices[which])
                    if (LoRaDriver.connectionState.value == LoRaDriver.ConnectionState.CONNECTED) {
                        Toast.makeText(this@MainActivity,
                            "Connected to ${devices[which].name}", Toast.LENGTH_SHORT).show()
                    }
                    updateConnectButton()
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> updateConnectButton() }
            .create()
            .show()
    }

    private fun disconnectLoRa() {
        lifecycleScope.launchWhenStarted {
            LoRaDriver.close()
            MotoMeshEngine.stop()
            MotoMeshEngine.start(this@MainActivity, lifecycleScope, transport = TransportMode.LORA)
            updateConnectButton()
            Toast.makeText(this@MainActivity, "Disconnected", Toast.LENGTH_SHORT).show()
            connStateJob?.cancel()
            connStateJob = null
            rssiJob?.cancel()
            rssiJob = null
        }
    }

    // ─── Cellular transport stubs ──────────────────────────────────────

    private fun startCellularTransfer() {
        // Phase 3: default relay = Android emulator host loopback; real device = actual relay IP
        val relayHost = if (android.os.Build.FINGERPRINT.contains("generic")) "10.0.2.2" else "0.0.0.0"
        CellularBridge.init(this)
        CellularBridge.connect(relayHost, 60005)
        Log.i("MainActivity", "startCellularTransfer: connecting to $relayHost:60005  state=${CellularBridge.cellularState.value}")
    }

    private fun stopCellular() {
        cellularObserver?.cancel()
        cellularObserver = null
        Log.i("MainActivity", "stopCellular: closing CellularBridge, resetting state")
        CellularBridge.close()
        CellularBridge.cellularState.value = CellularBridge.CellularState.IDLE
        updateConnectButton()
        Toast.makeText(this@MainActivity, "Cellular disconnected", Toast.LENGTH_SHORT).show()
    }

    // ─── Permissions ───────────────────────────────────────────────────

    private fun requestPermissions(loopback: Boolean) {
        val perms = if (loopback) {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        } else {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        permLauncher.launch(perms)
        // TODO cellular: add relay connect callback after perms granted
    }

    // ─── Lifecycle ───────────────────────────────────────────────────

    override fun onDestroy() {
        connStateJob?.cancel()
        rssiJob?.cancel()
        cellularObserver?.cancel()
        cellularObserver = null
        super.onDestroy()
    }
}
