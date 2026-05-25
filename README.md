# MotoMesh — Open-Source Group Voice Chat for Motorcycles

**Real-time voice mesh for 3–10 riders. Zero cell dependency. Music keeps playing, it just ducks when someone talks.**

---

## What This Is

A mesh voice chat Android application built on top of LoRa radio modules and the Opus codec. Bikes become independent radio nodes — no cell tower, no base station, no infrastructure required. If you can see the other rider, you can talk to them.

**License:** MIT — fork it, ride it, improve it.

---

## The Problem

Group motorcycle rides hit dead zones constantly — tunnels, mountain roads, rural stretches. Standard VoIP (Discord, WhatsApp, Telegram) depends on cellular data and drops you entirely when coverage does. Bluetooth audio between bikes doesn't scale past a couple of riders and fails at highway speed.

MotoMesh solves both: **every bike is an independent node in a radio mesh**, and voice routes around dead nodes automatically through multi-hop relay.

---

## Hardware Required (LoRa)

```
┌─────────────────────────────────────────────────────┐
│                 Rider Phone (Android)                │
│                                                      │
│  ┌──────────────┐      ┌──────────────────────┐      │
│  │  AudioRecord  │─────▶│  Opus Encoder (20ms) │      │
│  └──────────────┘      └──────────┬───────────┘      │
│                                    │                  │
│                                    ▼                  │
│  ┌──────────────┐      ┌──────────────────────┐      │
│  │  AudioTrack  │◀─────│  Opus Decoder + Jitter│      │
│  └──────────────┘      └──────────┬───────────┘      │
│                                    │                  │
│         ┌──────────────────────────┐                 │
│         │  DuckingController        │                 │
│         │  (music: 1.0 ↔ 0.20)     │                 │
│         └──────────────────────────┘                 │
└─────────────────────┬───────────────────────────────┘
                      │ BLE (GATT serial, ~2 Mbps)
                      ▼
┌──────────────────────────────────────────────────────┐
│                RYLR993 LoRa Module (~€15)             │
│                                                       │
│  ┌──────────────┐  ┌────────────┐  ┌───────────────┐ │
│  │  BLE + GATT  │──│  AT Cmd    │──│  LoRa Radio   │ │
│  │  (Nordic SoC)│  │  Config    │  │  (868/915 MHz)│ │
│  └──────────────┘  └────────────┘  └───────┬───────┘ │
└────────────────────────────────────────────┼─────────┘
                                             │ RF
                    ┌────────────────────────┼────────────┐
                    │                        │            │
                    ▼                        ▼            ▼
            ┌──────────────┐        ┌──────────────┐
            │  Rider 2     │◄──────▶│  Rider 3     │  ...up to 10
            │  RYLR993     │  mesh  │  RYLR993     │
            └──────────────┘        └──────────────┘
```

**Wiring (RYLR993 to power):**

| RYLR993 Pin | Wire to |
|-------------|---------|
| VCC (3.3V)  | Bike USB 5V → 3.3V regulator (AMS1117-3.3) |
| GND         | Ground / bike chassis |
| TX (GPIO)   | Not used (BLE handles all data) |
| RX (GPIO)   | Not used (BLE handles all data) |
| EN / RST    | 10 kΩ pull-up to 3.3V (module auto-starts) |

The module is pre-configured from factory — just power it via any USB port with a 3.3V regulator. Pair once in Android Bluetooth settings (passkey: `123456`), then the app handles the rest.

| Component | Model / Spec | Cost (per rider) |
|-----------|-------------|-----------------|
| LoRa module | RYLR993 (BLE embedded, AT commands) | ~€15 |
| 3.3V regulator | AMS1117-3.3 or similar | ~€2 |
| USB power | Bike USB outlet / 12→5V adapter | ~€10 |
| Case | Waterproof project box | ~€5 |
| Phone | Android 10+ (API 29+) | you own it |
| Audio | Wired headset / helmet speakers | you own it |

**Total per rider:** ~€25-32 depending on power. No recurring fees. No subscriptions.

---

## Cellular Transport (Alternative: No Hardware)

MotoMesh also supports **TCP/IP relay mode** — no LoRa module needed. Useful for:
- Quick testing without hardware
- Hybrid rides where some riders have LoRa and others use cellular
- Fallback when LoRa range is exceeded

In cellular mode, the app connects to a TCP relay server (user-provided), which forwards voice frames between connected riders. The same Opus codec runs at 20ms per frame.

---

## Architecture (Software)

```
┌─────────────────────────────────────────────────────────────┐
│                    MotoMesh App (Android)                    │
│                                                             │
│  ┌──────────┐   ┌──────────────┐   ┌────────────────────┐  │
│  │ Settings │   │  MainActivity │   │  MotoMeshService   │  │
│  │ Activity │──▶│  (rider list) │──▶│  (foreground)      │  │
│  └──────────┘   └──────┬───────┘   └──────────┬─────────┘  │
│                         │                       │            │
│                         ▼                       ▼            │
│  ┌─────────────────────────────────────────────────────┐    │
│  │                 MotoMeshEngine                       │    │
│  │  ┌────────────┐  ┌──────────────┐  ┌─────────────┐  │    │
│  │  │ AudioPipe  │──│ MeshForwarder│──│  NodeTable  │  │    │
│  │  │ OpusCodec  │  │ (TTL=5 flood)│  │ (StateFlow) │  │    │
│  │  │ DuckCtrl   │  │ + dedup      │  │             │  │    │
│  │  └────────────┘  └──────┬───────┘  └─────────────┘  │    │
│  └─────────────────────────┼────────────────────────────┘    │
│                            │                                 │
│              ┌─────────────┴─────────────┐                   │
│              ▼                           ▼                   │
│  ┌──────────────────┐      ┌──────────────────────┐         │
│  │  LoRaDriver       │      │  CellularBridge      │         │
│  │  (BLE GATT)       │      │  (TCP relay)         │         │
│  │  RYLR993Ble       │      │  + connectivity      │         │
│  │  + AT config      │      │  + node ID framing   │         │
│  └────────┬─────────┘      └──────────┬───────────┘         │
└───────────┼───────────────────────────┼──────────────────────┘
            │                           │
            ▼                           ▼
    ┌──────────────┐          ┌──────────────────┐
    │ LoRa RF (868)│          │  Internet (TCP)   │
    │ 3-10 riders  │          │  Relay Server     │
    └──────────────┘          └──────────────────┘
```

**Key design decisions:**
- Voice only outbound. Music stays local, never leaves your phone.
- Automatic audio ducking: when inbound voice arrives, music volume ducks then restores.
- Opus codec @ 20 Kbps, 20ms frames → fits comfortably in LoRa 240 byte packets.
- Flood-gossip mesh: TTL=5, deduplication by 16-bit sequence + node ID. No ACKs.
- Jitter buffer absorbs LoRa's bursty packet delivery.
- Dual transport: LoRa radio (hardware) or TCP relay (no hardware), switchable at runtime.

---

## Build Requirements

- Android Studio Hedgehog or later
- JDK 17 or JDK 21 (Android Studio JBR works)
- Android SDK API 29+ (compileSdk 35)
- Android 10+ device for testing
- At least one RYLR993 LoRa module with BLE (for LoRa mode; cellular mode needs no hardware)

---

## Quick Start

1. `git clone https://github.com/<you>/MotoMesh.git`
2. Open in Android Studio
3. Pair your RYLR993 module via BLE:
   - Scan for device named `RYLR993_XXXX`
   - Pair in Android Bluetooth settings (passkey: `123456`)
   - App auto-configures module via AT commands on connect
4. Build and install APK (`./gradlew assembleDebug`)
5. Grant permissions on first run (Microphone, Bluetooth, Notifications)
6. Tap **Connect** → choose LoRa BLE (or Cellular for TCP relay)
7. Repeat setup on each rider's phone
8. Ride.

---

## Project Status

**Phase 1 complete** — all core systems scaffolded and integrated.

| Feature | Status |
|---------|--------|
| Opus codec (20ms encode/decode) | ✅ |
| Audio pipeline with jitter buffer | ✅ |
| Ducking controller (voice→music duck) | ✅ |
| BLE GATT driver (RYLR993) | ✅ |
| AT command config (on connect) | ✅ |
| Mesh forwarder (TTL=5 flood-gossip + dedup) | ✅ |
| Foreground service + notification | ✅ |
| Rider list UI with RSSI/loss display | ✅ |
| NodeTable with reactive StateFlow | ✅ |
| Cellular TCP relay transport | ✅ |
| Node detail dialog (tap rider) | ✅ |
| Audio settings (VOX threshold, duck depth) | ✅ |
| Loopback mode (self-test) | ✅ |
| Cellular node-ID protocol (multi-rider) | ✅ |
| **Next: Multi-device field test** | 🔜 |

### Phase 2 (in progress)
- Multi-device LoRa link test (2+ phones, 1+ module each)
- Range characterization at ride speed
- Cellular relay server implementation

### Phase 3 (planned)
- Multi-hop mesh field test (3+ riders)
- Node table population at ride scale
- Packet loss characterization

### Phase 4 (planned)
- Signed Play Store AAB build
- Privacy policy for RECORD_AUDIO + INTERNET
- Play Store listing

---

## Running Tests

```bash
# Unit tests
./gradlew test

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease
```

---

## Known Limitations

- LoRa range drops in heavy rain and dense urban canyons — expect 1–2 km minimum, 5 km clear day
- 150–250ms end-to-end voice latency is functional for motorcycle conversation but not for music
- Module pairing via BLE: passkey `123456` must be pre-set on RYLR993; the app can't change it
- Android 12+ requires foreground service notification — expect a persistent "MotoMesh active" icon
- Cellular relay requires an external TCP server to forward frames between riders

---

## Contributing

Bug reports, hardware improvements, and pull requests welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).

---

## Credits

Built for wearable group voice under zero-cell constraints. Hardware target: RYLR993 LoRa module. Audio codec: Opus via Concentus library.