package com.motomesh.mesh

import android.util.Log
import com.motomesh.audio.OpusCodec
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentBlockingQueue
import java.util.concurrent.LinkedBlockingQueue

/**
 * Central audio*engine bridge
 *
 * Bridges voice audio codec, LoRa rf, basically everything the service will already use.
 */
class MotoMeshEngine {

    private val TAG = "MotoMeshEngine"
    private var jobIoGate: Job? = null

    fun start() {
        // Implementation waits until OpusCodec and LoRa transport are initialized
        Log.d(TAG, "Engine start requested")
    }

    // Simulated placeholder
    fun stop() {
        Log.d(TAG, "Engine stopping")
    }
}
