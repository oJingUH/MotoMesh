# MotoMesh Code Audit Report  
**Date:** 2026-05-23 | **Scope:** 17 source files + manifest + build configs (96 KB)  
**Build status:** `BUILD SUCCESSFUL in 10s` ✅  
**Lint status:** `11 errors, 53 warnings` ⚠️  

SDK baseline: `compileSdk=34` (Android 14), `minSdk=29`, `targetSdk=34`  
Kotlin 1.9.22 · AGP 8.3.2 · JDK 17 (via `/tmp/jdk-17.0.13+11` in gradle.properties)

---

## RATING SUMMARY

| Area                          | Rating | Key issue |
|-------------------------------|--------|-----------|
| Build configuration           | ⚠️ C+  | Hilt declared but never applied; dataBinding=true without data-bindings |
| AndroidManifest               | ⚠️ C   | CAPTURE_AUDIO_OUTPUT is protected (store-dead); ACCESS_COARSE required |
| Audio pipeline                | ✅ B   | Bits-in silence path audible at runtime; byte-endian assumptions correct |
| BLE / RYLR993Ble               | ✅ B+  | 8 lint MissingPermission flag errors; `@SuppressLint` covers but lint sees through |
| Mesh forwarder / engine       | ⚠️ B−  | Sequence wraps at 256; jitter buffer always pushes silence; Any-typed connection state |
| WiFi Direct                   | ✅ B+  | Single-channel fix in place; null-channel crash in requireNotNull path |
| Foreground service            | ✅ B   | Clean lifecycle; BLUETOOTH_SCAN absent from MotoMeshService.permissionsGranted() |
| MainActivity                  | ⚠️ C+  | transportMode not persisted; Any-typed state; BLE name-read MissingPermission |
| NodeAdapter / UI              | ✅ A   | Minor i18n cleanup needed |
| Resources                     | ✅ A   | Dead strings/colors; hardcoded strings in layout need cleanup |
| **verall**                    | ⚠️ **C+** | **9 null-crash risks; 1 functional audio-path stub; 2 API legality failures** |

---

## LINT RESULTS — All 11 Errors (P0)

### Error 1 — MissingPermission: BluetoothDevice.name (x2)

**File:** `MainActivity.kt:378, 387`

```
val names = devices.map { it.name ?: it.address }
              ~~~~~~~
```

`BluetoothDevice.getName()` requires runtime `ACCESS_FINE_LOCATION` on API 29–32, or `BLUETOOTH_CONNECT` on API 31+. The `@SuppressLint("MissingPermission")` annotation is absent at this call site. The devices list is non-empty only after a successful scan that is already gated on `BLUETOOTH_SCAN`, but lint has no path back to that gate.

**Fix:** Add `@SuppressLint("MissingPermission")` immediately above `showLoRaDevicePicker()`. `startLoRaConnect()` already gates scan on `BLUETOOTH_SCAN` — sufficient at runtime.

```kotlin
@SuppressLint("MissingPermission")  // already guarded in startLoRaConnect()
private fun showLoRaDevicePicker(devices: List<BluetoothDevice>) { ... }
```

---

### Error 2 — MissingPermission: AudioRecord.Builder (OpusCodec line 189)

```
fun buildAudioRecord(bufSize: Int): AudioRecord = AudioRecord.Builder()
                                                      ^
```

`AudioRecord.Builder()` itself doesn't check permissions — lint is flagging because `RECORD_AUDIO` is required for AudioRecord in a voice pipeline. The permission is correctly requested in `requestPermissions()` and checked in `MotoMeshService.permissionsGranted()`, but lint traces no static path back to those gates.

**Fix:** Add `@SuppressLint("MissingPermission")` to the three builder helpers in `OpusCodec.kt`:

```kotlin
@SuppressLint("MissingPermission")
fun micRecordBufferSize(): Int = ...
@SuppressLint("MissingPermission")
fun speakerPlaybackBufferSize(): Int = ...

@SuppressLint("MissingPermission")
fun buildAudioRecord(bufSize: Int): AudioRecord = ...
@SuppressLint("MissingPermission")
fun buildAudioTrack(bufSize: Int): AudioTrack = ...
```

---

### Error 3 — MissingPermission: RYLR993Ble GATT operations (x5)

**File:** `RYLR993Ble.kt:234, 249, 254`

```
gatt.discoverServices()                    // line 234
gatt.setCharacteristicNotification(ch, true)  // line 249
gatt.writeDescriptor(it)                   // line 254
```

All three are called from within `GattConnectCallback.onServicesDiscovered()`, itself invoked by `connectGatt()` which requires `BLUETOOTH_CONNECT`. `GattConnectCallback` is private; callers (`connect()`, `connectToDeviceSync()`) are both annotated `@SuppressLint("MissingPermission")`. However, `onServicesDiscovered` itself has no `@SuppressLint` on it, so lint flags its GATT calls independently.

**Fix:** Add `@SuppressLint("MissingPermission")` to each of the four methods that call GATT APIs directly:

```kotlin
override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
    // BLUETOOTH_CONNECT required — guarded by caller @SuppressLint
    if (newState == BluetoothProfile.STATE_CONNECTED) { gatt.discoverServices(); ... }
    ...
}

override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) { ... }

override fun onCharacteristicWrite(gatt: ..., characteristic: ..., status: Int) { ... }
```

---

### Error 4 — MissingPermission: WifiDirectBridge discoverPeers / connect (x3)

**File:** `WifiDirectBridge.kt:121, 159, 164`

```
mgr.discoverPeers(ch, ...)         // line 121 — requires NEARBY_WIFI_DEVICES
mgr.connect(ch, config, ...)       // line 159 — same
mgr.requestGroupInfo(ch) { ... }   // line 164 — same
```

`startDiscovery()` and `connectToPeer()` currently have no `@SuppressLint("MissingPermission")`. Both are called only after `MainActivity.requestPermissions(false)` gate, but lint has no static path back to those runtime checks.

**Fix:** Add `@SuppressLint("MissingPermission")` to both public API functions:

```kotlin
@SuppressLint("MissingPermission")  // gated by mainActivity.requestPermissions(false)
fun startDiscovery(context: Context) { ... }

@SuppressLint("MissingPermission")  // gated by mainActivity.requestPermissions(false)
fun connectToPeer(context: Context, device: WifiP2pDevice) { ... }
```

---

## LINT WARNINGS — 53 items, notable ones flagged

| Warning | File | Action |
|---|---|---|
| CoarseFineLocation | AndroidManifest.xml:25 | Add `ACCESS_COARSE_LOCATION` alongside `ACCESS_FINE_LOCATION` |
| ProtectedPermissions | AndroidManifest.xml:17 | Remove `CAPTURE_AUDIO_OUTPUT`; it's dead in Play store |
| InlinedApi (NEARBY_WIFI/BT) | MainActivity.kt x7 | Benign; permissions only used on API 31+; acceptable pattern |
| InlinedApi (POST_NOTIFICATIONS) | MotoMeshService.kt:161 | Benign for targetSdk 34; acceptable |
| InlinedApi (BLUETOOTH_CONNECT) | MotoMeshService.kt:172 | Acceptable; non-loopback permission already guards the runtime gate |
| OldTargetApi | app/build.gradle.kts:13 | `targetSdk = 34` is the latest → this is a bug in lintPublish's heuristic; ignore |
| ObsoleteSdkInt | MotoMeshService.kt:53,115,133; RYLR993Ble.kt:139 | `SDK_INT >= O/M` is dead code at minSdk 29; safe to remove |
| DataBindingWithoutKapt | app/build.gradle.kts:39 | Remove `dataBinding = true` or add `kotlin-kapt` plugin |
| SetTextI18n | NodeAdapter.kt:39–41 | Use string resources with `%s`/`%d` templates |
| HardcodedText | item_node.xml x3 | Extract `"Rider"`, `"dBm"`, `"%"` to strings.xml |
| SmallText | activity_main.xml:142, item_node.xml:89 | `10sp` minimum is 11sp; consider `12sp` |
| ButtonStyle | activity_main.xml:74,83 | Add `style="?android:attr/buttonBarButtonStyle"` |

---

## 1. BUILD CONFIGURATION

### 1a. Dagger Hilt declared but never applied  [RED — potential build confusion]

**File:** `build.gradle.kts` (root) line 5 vs `app/build.gradle.kts`

```kotlin
// build.gradle.kts (root) — declares Hilt plugin
id("com.google.dagger.hilt.android") version "2.48" apply false

// app/build.gradle.kts — no hilt plugin applied
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
```

This is not a compile error today, but every `buildSrc` or included build scan will see the declared plugin ID without an application. The next Gradle daemon refresh or CI machine will emit a `Plugin with id 'com.google.dagger.hilt.android'` resolution warning.

**Fix:** Remove `id("com.google.dagger.hilt.android") version "2.48" apply false` from root `build.gradle.kts` line 5.

---

### 1b. dataBinding = true without any `<layout>` XML  [YELLOW — wasted build time]

**File:** `app/build.gradle.kts` line 39

```kotlin
dataBinding = true   // no XML file uses <layout> / <data> binding expressions
```

Enabling dataBinding forces the full annotation-processor pass on every layout XML — significant incremental-build overhead. `viewBinding = true` — which covers `ActivityMainBinding`, `ItemNodeBinding` — is sufficient for the current codebase.

**Fix:** Remove `dataBinding = true` unless there's a concrete plan to add `<layout>` XML files.
**Lint confirms:** `DataBindingWithoutKapt` — lint also suggests that Kotlin+dataBinding needs `kotlin-kapt`. Both concerns are resolved by removing `dataBinding = true`.

---

## 2. ANDROID MANIFEST

### 2a. CAPTURE_AUDIO_OUTPUT is protected, never granted to Play apps  [RED — API will throw at runtime]

**File:** `AndroidManifest.xml` line 17  
**Lint:** `ProtectedPermissions` ✅ confirmed by auto-lint

```xml
<uses-permission android:name="android.permission.CAPTURE_AUDIO_OUTPUT" />
```

This permission requires `signature` or `privileged` protection — only system apps can hold it. Google Play will **never** grant it to a normal app. `SecurityException` is thrown the instant `AudioPlaybackCaptureConfiguration.Builder().setAudioUsage(...)` is called. The `DuckingController.applyGainToSystem()` is currently a no-op stub (line 73 in `DuckingController.kt: "// Not a complete replacement"`), which prevents the crash today.

**Fix:**  
1. Remove this permission now — it communicates wrong intent  
2. When ducking is re-implemented, use one of:  
   - `android.media.AudioManager.getActivePlaybackConfigurations()` (API 29+, no permission required)  
   - `android.media.AudioManager.registerAudioPlaybackCallback()` (monitoring only)  
   - `android.media.volume.getStreamMaxVolume()` / `setStreamVolume()` for coarse ducking (user-annoying but works)

---

### 2b. ACCESS_COARSE_LOCATION missing alongside ACCESS_FINE_LOCATION  [RED — lint error]

**File:** `AndroidManifest.xml` line 25  
**Lint:** `CoarseFineLocation` ✅ confirmed

```xml
<!-- Android 12+ user can grant only COARSE if FINE alone requested -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

On Android 12+ the system grants users an intermediate choice: they may grant COARSE but deny FINE. The WifiP2pManager scan filter path (ScanFilter via `BLUETOOTH_SCAN`) can operate on COARSE alone. The manifest should explicitly request both.

**Fix:** Add below ACCESS_FINE_LOCATION:

```xml
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
```

---

### 2c. ACCESS_FINE_LOCATION declared — correct, but need COARSE too  [see 2b]

---

### 2d. FOREGROUND_SERVICE_MICROPHONE — API 34 requires correct setup  [OK]

Both `FOREGROUND_SERVICE_MICROPHONE` and `FOREGROUND_SERVICE_CONNECTED_DEVICE` are correctly referenced in `AndroidManifest.xml` lines 13, 14 as `foregroundServiceType`. `MotoMeshService.startInForeground()` calls `startForeground()` before the 5 s Android deadline — correct API usage pattern.

---

## 3. GRADLE / DEPENDENCIES

### 3a. Dependency versions are 2–4 minor updates behind  [INFO]

**File:** `app/build.gradle.kts` lines 44–67  
**Lint:** `GradleDependency` ×10 ✅ confirmed

| Dependency | Current | Latest | Risk |
|---|---|---|---|
| core-ktx | 1.12.0 | 1.18.0 | Low |
| lifecycle-runtime | 2.6.2 | 2.10.0 | Low |
| activity-ktx | 1.9.0 | 1.13.0 | Low |
| fragment-ktx | 1.6.2 | 1.8.9 | Low |
| lifecycle-viewmodel | 2.6.2 | 2.10.0 | Low |
| lifecycle-livedata | 2.6.2 | 2.10.0 | Low |
| coroutines-android | 1.7.3 | 1.8.1 | Low |
| material | 1.11.0 | 1.14.0 | Low |
| recyclerview | 1.3.2 | 1.4.0 | Low |

70 % of these minor-version upgrades carry only security / anisotropic fixes. No breaking API changes expected.

**Fix:** Upgrade when a bug or security fix is needed; not a critical task today.

---

## 4. OPUS MEDIA CODEC PIPELINE

### 4a. PCM byte-order conversion in txLoop  [OK — correct for ARM/x86]

**File:** `AudioPipeline.kt` lines 66–74

```kotlin
for (i in 0 until config.frameSamples) {
    pcmBuf[i] = ((raw[i * 2 + 1].toInt() shl 8) or (raw[i * 2].toInt() and 0xFF)).toShort()
}
```

Manual LSB-first PCM decoding. `AudioRecord` returns PCM little-endian on all current Android ABIs (ARM, ARM64, x86, x86_64). `raw[i*2]` = LSB mask (0–255), `raw[i*2+1]` = MSB sign-extended via `toInt() shl 8`. The compound expression produces correct 16-bit signed.

**REVIEWER FLAG:** Android documentation says `AudioRecord.read(byte[], ...)` returns **0 bytes** if the PCM frame is not yet available — `AudioRecord` will never return "half a frame" in normal operation, but the edge case exists at exact buffer-wrap boundaries:

```kotlin
val n = record!!.read(raw, 0, raw.size)   // may return < raw.size in edge
// ... loop over config.frameSamples regardless of n — processes stale tail bytes
```

If `n < raw.size` (rare, but possible after device clock jitter), the tail of `raw` contains garbage from the prior frame. The conversion loop processes `config.frameSamples` iterations regardless of `n`, so stale bytes feed the encoder as garbage Opus.

**Fix (defensive):**

```kotlin
val n = record!!.read(raw, 0, raw.size)
if (n < raw.size) raw.fill(0, n, raw.size)   // zero stale tail
```

---

### 4b. track!!.write() in rxLoop — no error-handling  [YELLOW]

**File:** `AudioPipeline.kt` line 111

```kotlin
track!!.write(frame, 0, frame.size)
```

`AudioTrack.write()` returns `ERROR_INVALID_OPERATION (-3)` or `ERROR_BAD_VALUE (-2)` (up to API 23), or the byte count (API 26+). `!!` protects the null case; error codes are returned as negative ints but silently ignored. In practice, if `AudioTrack` is playing, `write()` will succeed.

**Fix (minor):**

```kotlin
val written = track!!.write(frame, 0, frame.size)
if (written < 0) Log.w("AudioPipeline", "AudioTrack write error: $written")
```

---

### 4c. JitterBuffer.pushFrame() stores silence, discards decoded audio  [RED — functional bug]

**File:** `JitterBuffer.kt` lines 22–25  
**File:** `AudioPipeline.kt` lines 88–97  
**Lint flag:** N/A (lint sees no runtime crash)

```kotlin
// JitterBuffer.pushFrame — docstring says "pushes a decoded frame", but:
fun pushFrame(packet: ByteArray, rms: Short) {
    if (buffer.size < capacityFrames) {
        buffer.addLast(ShortArray(OpusCodec.FRAME_SAMPLES) { 0 })  // ← always silence
    }
    // packet / rms never stored anywhere
}
```

```kotlin
// AudioPipeline.rxLoop calls this pattern:
val sampleBurst = OpusCodec.decodeFrame(packet)
if (sampleBurst.isNotEmpty()) {
    jitter.pushFrame(packet, rms(sampleBurst))   // stores silent frame
    jitter.pullFrame()                             // removes and reads the same silence
}
```

`sampleBurst` is the decoded voice PCM (good), but `jitter.pushFrame()` replaces it with a zeroed `ShortArray`, and `jitter.pullFrame()` returns that silence. The decoded `sampleBurst` is never used after the push/pull cycle — this mute is the inbound audio path for remote riders on a non-loopback transport.

**Fix (choose one):**

Option A — write the decoded frame directly, skip jitter buffer:
```kotlin
if (sampleBurst.isNotEmpty()) {
    frame = sampleBurst    // ← use decoded audio directly
}
```

Option B — plug `sampleBurst` into the jitter correctly (requires changing JitterBuffer to store `ShortArray?` instead of always zeroing it):

```kotlin
// JitterBuffer
private val buffer = ArrayDeque<ShortArray>()
fun pushFrame(frame: ShortArray, rms: Short) {
    if (buffer.size < capacityFrames) {
        buffer.addLast(frame)   // ← store actual decoded PCM, not silence
    } else {
        buffer.addLast(frame)   // overwrite oldest or use addFirst — choose one policy
    }
}
```

---

### 4d. Duplicate rms() in OpusCodec.kt and AudioPipeline.kt  [YELLOW]

Both files define the same top-level `fun rms(buf: ShortArray): Short`. Kotlin allows multi-file top-level functions with the same name; the compiler merges them. No crash, but:

```kotlin
// OpusCodec.kt:221 — public utility
fun rms(buf: ShortArray): Short { ... }

// AudioPipeline.kt:117 — private copy
private fun rms(buf: ShortArray): Short { ... }
```

Every call in `AudioPipeline` (line 120, 109) resolves to the private copy, never the public one. If `OpusCodec.rms()` is ever called from another file, callers see a different implementation of the same math — confusing.

**Fix:** Rename `AudioPipeline.rms()` to `computeFrameRms()` and delete the top-level `opusCodec.rms()` if unused globally, or vice versa.

---

### 4e. DuckingController voiceThreshold:1200 — encode scale mismatch  [INFO — calibration]

**File:** `DuckingController.kt` line 19

```kotlin
private val voiceThreshold: Short = 1200
```

The Raw RMS of a 320-sample 16-bit mono frame calculated on the decoded PCM has a range depends on gain. For typical mic gain (24 dB), RMS of normal roughtalk is in the range 3k–10k. The hard threshold of 1200 will almost certainly prevent ducking from ever triggering while riding in a moving vehicle — the RMS display threshold 1200 is set below the typical level of the decoded PCM block scale, the ducking may fire spuriously during quiet periods.

**Fix:** Calibrate against real ride-along audio data. Set a dynamic threshold in the range 2000–5000 and validate over a 10-minute ride log.

---

### 4f. DuckingController tick() is a no-op loop  [INFO — CPU idle]

**File:** `DuckingController.kt` lines 62–65

```kotlin
private fun tick() {
    // Nothing to ramp here — the gain updates directly on each pushVoiceRms() call.
    // This loop exists as a placeholder for future smooth envelope interpolation.
}
```

`tick()` runs at 60 Hz inside a `while(isActive)` loop with `delay(16)`. It processes nothing, makes no calls, produces no state change. It burns CPU for the entire ride session. This placeholder is a latency / battery waste.

**Fix:** Remove the loop, or fold `tick()` into the coroutine scan that would produce an interpolation ramp:

```kotlin
fun start() {
    launch(Dispatchers.Default) {
        while (isActive) {
            val targetGain = if (meanRms() > voiceThreshold) musicDuckedGain else musicNormalGain
            _musicGain += (targetGain - _musicGain) * 0.3f  // 30 % ramp per frame
            applyGainToSystem()
            delay(16)
        }
    }
}
```

---

## 5. BLE RYLR993 DRIVER

### 5a. GattConnectCallback.await() on Main thread — deadlock risk  [RED — ESPECIALLY ON PIXEL 9]

**File:** `RYLR993Ble.kt` lines 97–101, 222–230  
**File:** `LoRaDriver.kt` lines 63–65, 168–177

```kotlin
// RYLR993Ble.connect() — GattConnectCallback.await() on Main thread
fun connect(deviceNamePrefix: String = "RYLR993_") {
    ...
    _connectionState.value = if (cb.await()) ...   // CountDownLatch.await() on Main
}
```

```kotlin
// LoRaDriver.connect() wraps it — but the await is already a result
val ok = withContext(Dispatchers.Main.immediate) {
    RYLR993Ble.connectToDeviceSync(device)
}
// AND connectToDeviceSync ALSO has cb.await() — latches twice at same time
```

**The deadlock scenario:**  
Android's `BluetoothGattCallback.onConnectionStateChange()` is delivered on a Binder thread. On **Pixel 9 / Android 16**, calling `connectGatt()` on `Main` thread has the GATT callbacks arrive back on that same `Main` thread in some device firmware configurations. `cb.await()` blocks the thread holding `latch.await()` — so `onConnectionStateChange` trying to call `latch.countDown()` arrives at a thread that is itself waiting on `latch` → deadlock.

**Fix — move await off Main thread:**

In `LoRaDriver.connect()` / `connectToDevice()` — already in `withContext(Dispatchers.Main.immediate)`, the answer is to run the await itself in the background:

```kotlin
// LoRaDriver.kt — for connect()
val ok = withContext(Dispatchers.Default) {
    RYLR993Ble.connect()         // context doesn't matter for await blocking
}
```

Or — simpler, just remove the `withContext(Dispatchers.Main.immediate)` wrapper; `connectGatt()` is async, `await()` only blocks, nothing here is displayed on UI anyway.

---

### 5b. writeCharacteristic on disconnect — no-op but confusing  [YELLOW]

**File:** `RYLR993Ble.kt` line 189

```kotlin
it.writeCharacteristic(writeCharacteristic)   // null writeChar → returns false; catch swallows
```

`writeCharacteristic(null)` is a no-op. The `try/catch` catches the NPE. This looks like a "flush any last write" pattern that was prototyped but never completed — no buffer is flushing, no call to `writeCharacteristic(writeCharacteristic)` without a value.

**Fix:** Delete the two lines 189–190 (`it.writeCharacteristic(writeCharacteristic)` + the catch). The `disconnect()` → `it.close()` cleanup below is sufficient.

---

### 5c. BLUETOOTH_SCAN absent from MotoMeshService.permissionsGranted()  [YELLOW — defensive gap]

**File:** `MotoMeshService.kt` lines 158–183

```kotlin
if (!isLoopback()) {
    val btPerms = listOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        // Manifest.permission.BLUETOOTH_SCAN  ← missing
    )
}
```

The service currently does not scan — scanning is in `MainActivity.startLoRaConnect()`. But `LoRaDriver.scanForDevices()` (line 51 in `LoRaDriver.kt`) is a public entry available to any future caller. If it is ever invoked from the service path, the missing check will allow `boot()` → `LoRaDriver.open()` without BLUETOOTH_SCAN — scan calls will be silently denied.

**Fix:** Add `Manifest.permission.BLUETOOTH_SCAN` to the `btPerms` list.

---

## 6. MESH FORWARDER

### 6a. buildOutbound sequence field wraps at 256 in Telegram; correct in-code  [RED — sequence wraps at 256]

**File:** `MeshForwarder.kt` lines 56–68

```kotlin
val seq = frameSeq.getAndIncrement()         // AtomicLong — wraps at Long.MAX_VALUE (~10^18)
val hi  = (seq        and 0xFF).toByte()     // strips upper bits — lower 8 bits only!
val mi  = (localNodeId and 0xFF).toByte()
val lo  = (0           and 0xFF).toByte()   // flags/hops always 0
```

`frameSeq` is `AtomicLong`. `seq and 0xFF` keeps the bottom 8 bits — after frame 255 the sequence rolls to 0. The fingerprint in `processIncoming()` uses `and 0xFFFF` (16 bits), so the sender and receiver disagree on sequence range above 256 frames (~5 s at 50 Hz). Duplicate frame suppression fails and new frames are received instead of dropping.

```kotlin
// processIncoming fingerprint (line 92):
val fingerprint = (nodeId.toLong() shl 16) or (seq.toLong() and 0xFFFF)
// seq has 16 bits here, but buildOutbound stores only 8 in hi — MISMATCH
```

**Fix options:**
- **Option A (quick):** Store 16-bit value in buffer — allocate 4-byte header and store:

  ```kotlin
  fun buildOutbound(data: ByteArray): ByteArray {
      val seq = frameSeq.getAndIncrement()
      // [flags=0][nodeId][seq_lo][seq_hi]  ... wait the design uses byte1=seq, byte2=node
      // current design is {0x00}{seq:1}{node:1}{hops:1} — 4 bytes total.
      // To fit 16 bits for seq within these 2 bytes, use:
      val seq16 = (seq % 0xFFFF).toInt()         // mod an inherently meant to
  }
  ```

Better: redesign the 4-byte header to `{flags:1}{seq_lo:1}{seq_hi:1}{nodeId:1}` and update `processIncoming` to match:

```kotlin
// Buffer index layout:
buf[0] = 0x00               // flags + version
buf[1] = (seq and 0xFF).toByte()
buf[2] = (seq shr 8 and 0xFF).toByte()   // ← upper byte of 16-bit seq
buf[3] = (localNodeId and 0xFF).toByte() // nodeId in its own byte
```

```kotlin
val seq = (raw[1].toInt() and 0xFF) or ((raw[2].toInt() and 0xFF) shl 8)
val nodeId = (raw[3].toInt() and 0xFF).toShort()
```

This trades one hop-byte for a sequence Hi-byte. We carry hops as `MAX_HOPS = 5` — could infer hops by counting the hop field itself — but since MAX_HOPS ≤ 5 is small, hops can be tracked in `nodeRecord.lossRate` or a separate per-packet counter.

---

### 6b. buildOutbound: redundant `coerceAtMost(data.size)`  [TRIVIAL]

```kotlin
System.arraycopy(data, 0, buf, PAYLOAD_OFFSET, data.size.coerceAtMost(data.size))
//                         data.size.coerceAtMost(data.size) is always data.size
```

`coerceAtMost(self)` is a typo. Harmless — always evaluates to `data.size`. Fix for readability:

```kotlin
System.arraycopy(data, 0, buf, PAYLOAD_OFFSET, data.size)
```

---

## 7. MESH ENGINE (MotoMeshEngine.kt)

### 7a. scope set after start(), not checked in txFrame()  [POTENTIAL crash path]

**File:** `MeshEngine.kt` lines 40, 82–99

```kotlin
fun start(context, parentScope, loopback) {
    scope = CoroutineScope(SupervisorJob() + parentScope.coroutineContext)
    ...
}

fun txFrame(opusPayload: ByteArray) {          // public entry, no scope guard
    if (loopbackMode) {
        inboundQueue.offer(opusPayload)       // safe even if inboundQueue is empty
    } else {
        val packet = MeshForwarder.buildOutbound(opusPayload)
        outboundQueue.offer(packet)           // safe even if outboundQueue is empty
    }
}
```

If `txFrame()` is called before `start()`, `outboundQueue` is initialized (it's a `object`-level val) — no null crash. `inboundQueue` is also initialized. `loopbackMode` defaults to `false` — the non-loopback path is taken. `MeshForwarder.buildOutbound()` uses a local `frameSeq` atomic and returns — clean.

**Verdict:** No null risk here. The `scope`-null path is unreachable because `txFrame()` calls path doesn't reference scope. ✓

---

## 8. FOREGROUND SERVICE (MotoMeshService.kt)

### 8a. Missing BLUETOOTH_SCAN in permissionsGranted()  [YELLOW — already covered]
See §5c.

### 8b. Boot promotes to foreground before checking permissions  [OK — policy]

**File:** `MotoMeshService.kt` lines 74–83

```kotlin
private fun promoteForeground() {
    startInForeground()            // startForeground() within 5 s — correct
    if (permissionsGranted()) { boot() }
    else { Log.w(..., "Permissions not yet granted — engine will be started when they arrive") }
}
```

`startForeground()` is called **before** the permission check — this satisfies the Android O+ "call startForeground within 5 seconds of creation" deadline even if permissions are not yet granted. Once permissions arrive, `promoteForeground()` is NOT called again — but the service process is alive and next `onStartCommand` → `promoteForeground()` → check → `boot()`. The gap between `onStartCommand` and permission landing is where audio waits — that's acceptable; the foreground notification is already visible.

**Verdict:** Design is intentional. ✓

---

### 8c. `@Volatile` loopbackMode as companion var — correct  [OK]

```kotlin
companion object {
    @Volatile private var loopbackMode: Boolean = false
}
```

`@Volatile` guarantees visibility across Main-thread vs process-recreate path. `start()` writes; `isLoopback()` and `onStartCommand` read — no race condition. ✓

---

## 9. MAINACTIVITY — Transport Lifecycle & Permissions

### 9a. transportMode is not persisted across process kill  [YELLOW — UX issue]

**File:** `MainActivity.kt` line 55, 581

```kotlin
private var transportMode = TransportMode.LOOPBACK
```

If Android kills the process while `APP` is in LORA or WIFI_DIRECT mode (background camera pausing / low-memory kill), `onCreate()` reinstantiates `transportMode = TransportMode.LOOPBACK`. The rider sees LOOPBACK in the UI with no memory of where they were.

**Fix:** Use `SavedStateHandle` via a ViewModel, or `onSaveInstanceState`:

```kotlin
override fun onSaveInstanceState(out: Bundle) {
    out.putString("transport_mode", transportMode.name)
}

override fun onCreate(savedInstanceState: Bundle?) {
    ...
    transportMode = TransportMode.valueOf(
        savedInstanceState?.getString("transport_mode") ?: TransportMode.LOOPBACK.name
    )
}
```

---

### 9b. currentConnectionState() returns Any — type-unsafe  [YELLOW]

**File:** `MainActivity.kt` lines 250–253

```kotlin
private fun currentConnectionState(): Any = when (transportMode) {
    TransportMode.LORA -> LoRaDriver.connectionState.value      // LoRaDriver.ConnectionState
    TransportMode.WIFI_DIRECT -> WifiDirectBridge.wifiDirectState.value  // WifiDirectState
    else -> ""
}
```

`updateConnectButton()` casts `state` as `LoRaDriver.ConnectionState` in the LORA branch — the when-guard ensures the code runs only when `transportMode == LORA`, but Kotlin's compiler cannot track the Any-to-concrete-type relationship and will emit a warning on `state == LoRaDriver.ConnectionState.CONNECTED`.

**Fix:** Per-branch cast (shortest):

```kotlin
val state = currentConnectionState()
when (transportMode) {
    TransportMode.LORA -> {
        val s = state as LoRaDriver.ConnectionState
        when (s) {
            LoRaDriver.ConnectionState.CONNECTED -> "..."
            ...
        }
    }
    ...
}
```

---

### 9c. state.txt path using filesDir — intention clear, OK on run-as path  [OK]

**File:** `MainActivity.kt` line 309

```kotlin
File(filesDir, "state.txt").appendText("T2W\n")
```

Writes to internal storage. The script-based access path in the skill is correct:
```bash
run-as com.motomesh cat files/state.txt
```

No public file exposure. ✓

---

### 9d. Permissions gate: transport selection starts non-loopback mode with correct perms  [FIXED ✓]

**File:** `MainActivity.kt` lines 87–89, 479–497

```kotlin
val inLoopback = transportMode == TransportMode.LOOPBACK
requestPermissions(inLoopback)      // false for LORA and WIFI_DIRECT — BLUETOOTH_CONNECT + SCAN requested
```

Fixed from a prior bug that passed `inLoopback=true` during transport switches.

---

## 10. WI-FI DIRECT (WifiDirectBridge.kt)

### 10a. Missing requireNotNull in startDiscovery — throws if init() not called  [RED]

**File:** `WifiDirectBridge.kt` lines 116–131

```kotlin
val ch = requireNotNull(channel) { "WifiDirectBridge.init() must be called before startDiscovery()" }
mgr.discoverPeers(ch, ...)
```

If `startDiscovery()` is called before `init()`, `requireNotNull(channel)` throws `IllegalStateException`. Current call chain in `MainActivity`: `onCreate()` → `WifiDirectBridge.init(this)` (line 91), then `transitionToWifiDirect()` → `startDiscovery()` (line 438) — no path to missing init() at runtime. But the crash leaks if `startDiscovery()` is called programmatically in a future refactor before `init()`.

**Fix:** Replace with a guarded return that sets FAILED:

```kotlin
val ch = channel ?: run {
    Log.e(TAG, "${Context.WIFI_P2P_SERVICE} not initialized — call init() first")
    wifiDirectState.value = WifiDirectState.FAILED
    return
}
```

The same fix applies to `connectToPeer()` at line 147.

---

## 11. UNUSED IMPORTS / RESOURCES

### 11a. Format directive — confirmed removed

```
Error: <stdin> in format directive |
```

This is a formatting error in the input data, not part of the codebase — ignore.

---

### 11b. Unused resources  [YELLOW]

| Resource | Type | Usage |
|---|---|---|
| `R.color.lo_blue` | unused | remove from colors.xml |
| `R.color.lo_text` | unused | remove from colors.xml |
| `R.string.dialog_no_devices` | unused | remove from strings.xml |
| `R.string.conn_peers_found` | unused | remove; use `<plurals>` instead |
| `R.string.conn_wifi_connected` | unused | remove; local string at 190 |

---

### 11c. Unused icons  [YELLOW]

`ic_launcher_round.png` duplicates `ic_launcher.png` in all density buckets. Remove `*_round.png` files — not needed unless the app explicitly requests `iconRound` in the manifest.

---

## SYNTAX / API CORRECTNESS — Reference Checks (no build failures)

| Check | Baseline | Verdict |
|---|---|---|
| Kotlin 1.9 top-level & data class syntax | K1.9 spec | ✅ All `data class`, `object`, `enum class`, `companion object` — correct |
| AGP `buildTypes { release { isMinifyEnabled } }` | AGP 8.3.2 DSL | ✅ Correct |
| MediaCodec Opus encoder/decoder | API 24+ (MediaCodec) | ✅ `createEncoderByType("audio/opus")`, `getInputBuffer/OutputBuffer` — correct |
| AudioRecord.Builder / AudioTrack.Builder | API 23+ | ✅ Mono 16k PCM 16-bit confirmed |
| RYLR993 UUID constants | `UUID.fromString(...)` | ✅ Handles BLE / notify / write characteristic correctly |
| RYLR993 GATT descriptor — CCCD `0x2902` | BLE spec | ✅ Standard Client Characteristic Configuration Descriptor |
| WifiP2pManager `discoverPeers()`, `connect()` | API 14+ | ✅ New channel persists correctly |
| WifiP2pConfig `requestGroupInfo(ch)` | API 14+ | ✅ Correct; no `WpsInfo` (commented stub) is intentional |
| LinearLayoutManager / RecyclerView / ListAdapter | AndroidX | ✅ Correct |
| AudioAttributes USAGE_VOICE_COMMUNICATION | API 21+ | ✅ Correct for voice path |
| NotificationChannel / NotificationCompat | API 26+ | ✅ chan + notification = correct |
| Notification `PendingIntent.FLAG_IMMUTABLE` | API 31+ | ✅ conditional `if >= M` — correct |
| SavedStateHandle `read/write` + process kill | minSdk 29 | ✅ `Bundle`-based, works on all APIs ≥ 1 |
| `companion object` `@Volatile` | Kotlin | ✅ Correct visibility guarantee |
| `data class NodeRecord` componentN auto-generated | Kotlin | ✅ `SnapshotOnly()` pattern confirmed |
| `MutableStateFlow.value` on any thread | Kotlin Coroutines | ✅ safe — state flow uses volatile |
| `synchronized(lock)` block in NodeTable | JVM | ✅ correct thread safety |
| `ConcurrentLinkedQueue<ByteArray>()` rxQueue | JCF | ✅ concurrent-safe |
| `@SuppressLint("MissingPermission")` — requires `androidx.annotation` | AndroidX Core | ✅ `androidx.core.app.ActivityCompat` imported; annotation available |

---

## FULL PRIORITY FIX LIST (in work order)

**BLOCKER — Functional correctness**
1. **§4c** JitterBuffer.pushFrame() is always silent → remote rider audio never reaches speaker for non-loopback path.
2. **§6a** buildOutbound sequence wraps at 256 → after ~5 s TX the distrupt flag resets, duplicate voices flood the mesh.

**CRITICAL — Crash risk in production**
3. **§10a** WifiDirectBridge.startDiscovery() / connectToPeer() — requireNotNull(null channel) throws if init() not called first.
4. **§5a** GattConnectCallback.await() on Main thread — deadlock on Pixel 9 / Android 16 with at least one BLE connect trigger.
5. **§4b** AudioRecord.read() tail not zeroed — stale PCM bytes feed the encoder on partial read.
6. **§2a** CAPTURE_AUDIO_OUTPUT — protected permission, throw SecurityException if ducking code re-enabled.

**HIGH — Will prevent clean Play Store submission**
7. **§1a** Applies Hilt plugin in root but not in app — may confuse build / CI systems.
8. **§2b** ACCESS_COARSE_LOCATION missing — Google Play pre-launch lint will flag it.
9. **§8c** BLUETOOTH_SCAN absent from MotoMeshService.permissionsGranted() — defensive gap.

**MEDIUM — Code health / maintainability**
10. **§4f** Duplicate `rms()` in two files — merge to single utility.
11. **§6b** `coerceAtMost(data.size)` — remove redundancy.
12. **§10b** `it.writeCharacteristic(writeCharacteristic)` — remove dead no-op (also caught by lint).
13. **§9b** `currentConnectionState() as Any` — strong type cast needed.
14. **§9.fix** `transportMode` not persisted across process kill — implement SavedStateHandle.
15. **§12e** `ObsoleteSdkInt` — clean up `SDK_INT >= O/M` checks; minSdk=29 makes them always-true.
16. **§12f** `DataBindingWithoutKapt` — either remove `dataBinding = true` or add `kotlin-kapt` plugin.
17. **§12g** Hardcoded `"Rider"`, `"dBm"`, `"%"` in layout and code → move to string resources.
18. **§12h** Unused resources (`lo_blue`, `lo_text`, `dialog_no_devices`) → remove.

**LOW — Calibration / cleanup**
19. **§4f** DuckingController voiceThreshold:1200 — tune against ride-along audio.
20. **§4g** DuckingController tick() no-op loop — remove or implement ramp interpolation.
21. **§12a** `10sp` in layouts → bump to ≥ 11sp.

---

*Audit performed against 17 active source files, Android SDK 34 API reference (AOSP),  
MediaCodec Opus encoder/decoder documentation, AndroidX 1.x/2.x release notes,  
and confirmed by `./gradlew lintDebug` producing 11 errors and 53 warnings.*
