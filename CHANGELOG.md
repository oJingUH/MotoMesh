# CHANGELOG

## 2026-05-24 — Integration + Polish Release (v0.1-alpha)

### Added
- **TCP relay server** (`relay_server.py`) — Python TCP frame relay for cellular transport mode
- **Cellular node-ID protocol** — 2-byte node ID in every cellular frame (`[0xBB][len LE][nodeId LE][Opus]`)
- **NodeTable reactive StateFlow** — replaces 250ms polling with `nodeFlow.collectLatest` + 10s purge loop
- **Rider detail dialog** — tap any rider row to see RSSI, loss%, last heard, alive/stale status
- **Audio settings** — VOX threshold slider (800-8000) + duck depth slider (0-90%), persisted to SharedPreferences
- **Settings hot-reload** — `ACTION_RELOAD_AUDIO` signal re-reads prefs without service restart
- **TX pulse animation** — VOX dot pulses green on unmute (`res/anim/vox_pulse.xml`)
- **Live notification** — persistent notification shows transport mode + rider count
- **Hardware wiring diagram** — full ASCII pinout and BOM in README

### Changed
- **Architecture overhaul** — `enqueueInbound()` now dispatches by transport mode (LoRa → MeshForwarder dedup+flood → NodeTable → stripped Opus; Cellular → NodeTable → Opus; Loopback → direct)
- **LoRa rx pump** — now calls `publishRssi()` on every inbound frame (RSSI was never updated before)
- **MeshForwarder wired** — `processIncoming()` finally called from the engine (was defined but never invoked)
- **Rider-ergonomic layout** — consolidated status bar, 48-58dp glove-friendly touch targets, guideline_bar at 86%
- **Mute button** — icon-only with recognizable Material microphone, toggles mic_off on mute
- **Settings gear** — proper Material cog icon, now at bottom controls row
- **NodeAdapter** — alive/dead icon tint, pbVoice progress+color by lossRate threshold
- **README** — complete rewrite with dual-transport architecture, wiring diagram, project status table
- **All vector icons** — rewritten with clean, single-line Material paths

### Fixed
- `about_version` string — non-positional `%s %s` → `%1$s %2$s` (aapt2 was rejecting)
- Missing `Widget.MotoMesh.IconButton` and `Widget.MotoMesh.ConnectButton` styles in themes.xml
- DataBinding expressions in `item_node.xml` — stripped (were corrupting XML escape sequences)
- `observeNodeTable()` — was defined but never called (node list stayed empty)
- `NodeTable.touch()`/`markIdle()` — were defined but never called by any integration point
- `LoRaDriver.publishRssi()` — was defined but never called (RSSI stayed null forever)
- Settings activity hardcoded "Network"/"Audio" labels → string resources
- `settings_audio_stub` — replaced with functional SeekBar controls

### Infrastructure
- Full `assembleDebug` APK builds clean — installed and tested on OnePlus N10 5G (Android 10)
- App launches in loopback mode, foreground service notification active
- All permissions grant flow verified via ADB

### Remaining (hardware-dependent)
- LoRa field test with physical RYLR993 modules (Task 2c)
- Multi-hop mesh characterization at ride speed (Task 3c)
- Cellular relay server deployment

---

## Phase 1 — Audio Pipeline Complete (commit 8a3ffc4)

### Added
- Opus encode/decode wrappers (OpusCodec.kt, 20 ms / 16 kHz / 20 kbps via Concentus)
- AudioPipeline.kt — two 50 Hz coroutines: mic → Opus → LoRa Tx, LoRa Rx → jitter → Opus → headphones
- DuckingController.kt — voice-RMS-triggered music gain envelope (10% duck at 1200 RMS threshold)
- MeshEngine.kt — inbound/outbound queues, engine lifecycle
- LoRaDriver.kt — BLE GATT bridge with send serialization, rxFrames StateFlow
- RYLR993Ble.kt — full BLE GATT driver with CCCD subscription, binary packet channel
- MeshForwarder.kt — TTL=5 flood-gossip, 5 s dedup window, outbound frame builder
- MotoMeshService.kt — foreground service + wake lock + notification channel
- MainActivity + NodeAdapter — live rider list, node count subtitle, runtime permission grant
- Layouts: activity_main.xml, item_node.xml; Resources: strings, colors, themes

---

## Phase 0 — Scaffold (commit b02aaf6)
MIT, project skeleton, README, build.gradle, manifest, service stub