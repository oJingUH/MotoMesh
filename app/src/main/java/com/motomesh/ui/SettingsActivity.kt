package com.motomesh.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.motomesh.R
import com.motomesh.databinding.ActivitySettingsBinding
import com.motomesh.mesh.NodeTable
import android.view.View

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * SettingsActivity — one-stop QoL panel.
 *
 * Sections:
 *   Profile   — username / callsign entered by the rider (blocklist-gated)
 *   Network   — relay host + port (same keys as the old dialog_relay_config)
 *   Audio     — VOX threshold + duck depth sliders, persisted to SharedPreferences
 *
 * All changes are toasted individually and persisted atomically.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private val settingsPrefs: SharedPreferences by lazy {
        getSharedPreferences("moto_settings", MODE_PRIVATE)
    }

    // ─── Lifecycle ─────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        loadRelaySettings()
        loadUsername()
        loadAudioSettings()
        aboutLine()

        b.etUsername.addTextChangedListener(usernameWatcher)
        b.btnSave.setOnClickListener { saveAll() }
    }

    // ─── Profile ───────────────────────────────────────────────────────

    /** Blocklist for abusive callsigns. Kept deliberately small and explicit — add new terms with a matching comment in the PR. */
    private val blockedWords = setOf(
        // Racial / ethnically targeted
        "nigger", "nigga", "niggas", "kike", "chink", "gook", "spic",
        "wetback", "paki", "gypsy", "gypsie",
        // Hate-group identifiers
        "nazi", "heil", "kkk", "white supremacist", "white power",
        // Gender / sexuality slurs
        "faggot", "fag", "tranny", "shemale", "dyke", "cunt",
        // Disability slurs
        "retard", "retarded", "spaz", "mong",
        // General extreme profanity kept out of callsigns
        "pedo", "kys",
        // Leetspeak variants
        "n1gg3r", "ch1nk", "sp1c", "f4g", "k1k3", "p4k1",
    )

    private val usernameWatcher = object : TextWatcher {
        override fun afterTextChanged(s: Editable?) { validateUsername(s?.toString().orEmpty()) }
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    }

    private fun validateUsername(raw: String) {
        val name = raw.trim()
        val error = when {
            name.length < 3   -> getString(R.string.toast_username_too_short)
            name.length > 20  -> getString(R.string.toast_username_too_long)
            blockedWords.any { name.contains(it, ignoreCase = true) } -> getString(R.string.toast_username_blocked)
            else -> null
        }
        b.tilUsername.error = error
    }

    private fun loadUsername() {
        val saved = settingsPrefs.getString("username", "")
        b.etUsername.setText(saved ?: "")
        validateUsername(saved ?: "")
    }

    // ─── Network / relay ───────────────────────────────────────────────

    private fun loadRelaySettings() {
        val defaultHost = if (Build.FINGERPRINT.contains("generic")) "10.0.2.2" else "0.0.0.0"
        val savedHost = settingsPrefs.getString("relay_host", defaultHost) ?: defaultHost
        val savedPort = settingsPrefs.getInt("relay_port", 60005)
        b.etRelayHost.setText(savedHost)
        b.etRelayPort.setText(savedPort.toString())
    }

    private fun clearRelayError() {
        b.tvRelayStatus.visibility = View.GONE
        b.tilRelayHost.error = null
        b.tilRelayPort.error = null
    }

    // ─── Audio settings ────────────────────────────────────────────────

    private val voxThresholdRange = 800..8000
    private val duckDepthRange = 0..90  // 0 = no duck, 90 = duck to 10% volume

    private fun loadAudioSettings() {
        val vox = settingsPrefs.getInt("vox_threshold", 1200)
        b.sbVoxThreshold.progress = (vox - voxThresholdRange.first).coerceIn(0, voxThresholdRange.last - voxThresholdRange.first)
        updateVoxLabel(vox)

        val duckPct = settingsPrefs.getInt("duck_depth_pct", 80)  // 80% -> duck to 20%
        b.sbDuckDepth.progress = duckPct.coerceIn(0, 90)
        updateDuckLabel(duckPct)

        b.sbVoxThreshold.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                val raw = voxThresholdRange.first + p
                updateVoxLabel(raw)
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })

        b.sbDuckDepth.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                updateDuckLabel(p)
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })
    }

    private fun updateVoxLabel(value: Int) {
        b.tvVoxThresholdValue.text = getString(R.string.settings_vox_threshold_summary, value)
    }

    private fun updateDuckLabel(duckPct: Int) {
        val remaining = 100 - duckPct
        b.tvDuckDepthValue.text = getString(R.string.settings_duck_depth_summary, remaining)
    }

    // ─── Save ──────────────────────────────────────────────────────────

    private fun saveAll() {
        var allOk = true

        // Save username (only if validated)
        val rawName = b.etUsername.text?.toString().orEmpty()
        val trimmed = rawName.trim()
        val error = when {
            trimmed.length < 3   -> getString(R.string.toast_username_too_short)
            trimmed.length > 20  -> getString(R.string.toast_username_too_long)
            blockedWords.any { trimmed.contains(it, ignoreCase = true) } -> getString(R.string.toast_username_blocked)
            else -> null
        }
        if (error != null) {
            b.tilUsername.error = error
            allOk = false
        } else {
            settingsPrefs.edit().putString("username", trimmed).apply()
            NodeTable.setUsername(trimmed)
            Toast.makeText(this, R.string.settings_username_saved, Toast.LENGTH_SHORT).show()
            b.tilUsername.error = null
        }

        // Save relay port + host
        val host = b.etRelayHost.text?.toString()?.trim().orEmpty()
        val port = b.etRelayPort.text?.toString()?.toIntOrNull()

        if (host.isEmpty() || port == null || port !in 1..65535) {
            b.tilRelayHost.error = if (host.isEmpty()) "Required" else null
            b.tilRelayPort.error = if (port == null) "Invalid port" else null
            b.tvRelayStatus.text = "Invalid relay address or port"
            b.tvRelayStatus.visibility = View.VISIBLE
            allOk = false
        } else {
            settingsPrefs.edit()
                .putString("relay_host", host)
                .putInt("relay_port", port)
                .apply()
            clearRelayError()
        }

        // Save audio settings
        val voxValue = voxThresholdRange.first + b.sbVoxThreshold.progress
        val duckValue = b.sbDuckDepth.progress
        settingsPrefs.edit()
            .putInt("vox_threshold", voxValue)
            .putInt("duck_depth_pct", duckValue)
            .apply()

        if (allOk) {
            // Signal MotoMeshService to reload audio settings
            val reloadIntent = Intent(this, com.motomesh.service.MotoMeshService::class.java).apply {
                action = com.motomesh.service.MotoMeshService.Companion.ACTION_RELOAD_AUDIO
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(reloadIntent)
            } else {
                startService(reloadIntent)
            }
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    // ─── About ─────────────────────────────────────────────────────────

    private fun aboutLine() {
        val pi = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        val ver = "MotoMesh %s (build %d)".format(
            pi.versionName,
            pi.longVersionCode and 0xFFFFFFFF
        )
        b.tvAbout.text = ver
    }
}
