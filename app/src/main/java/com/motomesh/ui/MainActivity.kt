package com.motomesh.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.motomesh.R
import com.motomesh.databinding.ActivityMainBinding
import com.motomesh.mesh.MotoMeshEngine
import com.motomesh.mesh.MeshForwarder
import com.motomesh.mesh.NodeTable
import com.motomesh.service.MotoMeshService
// import kotlinx.coroutines.flow.collectLatest  // not used, remove

/**
 * MainActivity — entry point. Shows rider list, connects to LoRa, starts foreground
 * service; all audio/mesh logic lives in the service layer.
 *
 * Activity is a thin UI controller.
 */
class MainActivity : ComponentActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var nodeAdapter: NodeAdapter

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

        requestPermissions()
        MotoMeshService.start(this)
    }

    private fun setupRecycler() {
        nodeAdapter = NodeAdapter { node ->
            // TODO: node detail overlay showing RSSI + loss-rate history
        }
        b.nodeList.adapter = nodeAdapter
    }

    private fun setupButtons() {
        b.btnConnect.setOnClickListener {
            val connected = false // TODO: replace with real connection state // Sterile check
            // TODO: scan + pick device
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
                b.subtitle.text = "LoRa mesh voice — ${snapshot.size} riders in network"
                delay(250)
            }
        }
    }

    private fun requestPermissions() {
        val needed = listOf(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
        permLauncher.launch(needed.toTypedArray())
    }

    override fun onDestroy() {
        MotoMeshEngine.stop()
        super.onDestroy()
    }
}
