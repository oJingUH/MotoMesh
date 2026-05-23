package com.motomesh.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.motomesh.R
import com.motomesh.databinding.ActivityMainBinding
import com.motomesh.mesh.MotoMeshEngine
import com.motomesh.mesh.MeshForwarder   // carries NodeTable + NodeRecord in same package
import com.motomesh.service.MotoMeshService
import kotlinx.coroutines.delay         // explicit: delay() in observeNodeTable loop

/**
 * MainActivity — entry point. Shows rider list, starts foreground service;
 * all audio/mesh logic lives in the service layer.
 *
 * Loopback mode (loopbackMode = true):  mic → Opus → headphones, no LoRa BLE.
 * Production mode (loopbackMode = false): mic → Opus → BLE → LoRa radio.
 */
class MainActivity : ComponentActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var nodeAdapter: NodeAdapter

    // Hard-code to true for loopback prototype; flip to false when RYLR993 hardware is ready
    private val loopbackMode = true

    // Runtime permission handle
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* service handles permission errors via log */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        setupRecycler()
        setupButtons()
        observeNodeTable()

        requestPermissions(loopbackMode)
        MotoMeshService.start(this, loopback = loopbackMode)
    }

    private fun setupRecycler() {
        nodeAdapter = NodeAdapter { node: com.motomesh.mesh.NodeRecord ->
            // TODO: node detail overlay showing RSSI + loss-rate history
        }
        b.nodeList.adapter = nodeAdapter
    }

    private fun setupButtons() {
        b.btnConnect.setOnClickListener {
            // TODO: LoRa scan + pick device (no-op in loopback)
        }

        b.btnMute.setOnClickListener {
            // TODO: mute/unmute local mic
        }
    }

    private fun observeNodeTable() {
        lifecycleScope.launchWhenStarted {
            // Poll NodeTable every 250ms; in a full build this would be a StateFlow
            while (true) {
                val snapshot = com.motomesh.mesh.NodeTable.snapshot
                nodeAdapter.submitList(snapshot)
                b.subtitle.text = if (loopbackMode) {
                    "Loopback — ${snapshot.size} node"
                } else {
                    "LoRa mesh — ${snapshot.size} riders"
                }
                delay(250)
            }
        }
    }

    private fun requestPermissions(loopback: Boolean) {
        val needed = if (loopback) {
            // Loopback: mic + notification only, skip BLE
            listOf(
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.POST_NOTIFICATIONS,
            )
        } else {
            // Production: mic + BLE + notification
            listOf(
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        permLauncher.launch(needed.toTypedArray())
    }

    override fun onDestroy() {
        MotoMeshEngine.stop()
        super.onDestroy()
    }
}
