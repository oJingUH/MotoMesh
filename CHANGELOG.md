# CHANGELOG

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

### In Progress
- Phase 2 — Single LoRa link (2 phones + 1 RYLR993): AT command init, frequency/spreading-factor handshake
- Phase 2 — Audio loopback test on real device (no LoRa yet)

### Known Gaps / TODOs
- `OpusCodec.kt` imports `android.media.MediaRecorder` (unused — clean up import)
- `AudioPipeline.rxLoop` extract in the raw Opus byte[] path instead of forwardBuf
- In `AudioPipeline.kt`, type mismatch on `OpusCodec.rms(samples)`: the return type is Short not Int; call sites cast
- `R.id.pbVoice` references in NodeAdapter resolve when Material and RecyclerView packages are fetched
- AudioMixer.kt stub removed — native mixer (mixermodule .so) not yet built
- LoRa AT command init (channel, frequency, spreading factor) not yet wired to service start

- Duplicate `Channel<ByteArray>` import guard in lora/ package

---

## Phase 0 — Scaffold (commit b02aaf6)
MIT, project skeleton, README, build.gradle, manifest, service stub
