package com.motomesh.audio

/**
 * AudioMixer — music-channel gain control placeholder.
 *
 * Used by MotoMeshService to duck background music during voice transmission.
 * Actual gain application (AudioManager.setStreamVolume) lives in DuckingController.
 * This class is a stub labelled for future expansion when playback mixing is needed.
 */
object AudioMixer {
    var gain: Float = 1.0f
}
