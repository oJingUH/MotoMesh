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

## Hardware Required

| Component | Model / Spec | Cost (per rider) |
|-----------|-------------|-----------------|
| LoRa module | RYLR993 (has BLE embedded) or TTGO LoRa32 (ESP32, BLE) | €12–20 |
| BLE pairing | Built-in to RYLR993; phone connects directly | — |
| Power | Bike USB outlet, cigarette-lighter adapter, or power bank | €5–15 |
| Phone | Android 10+ (API 29+) | you own it |
| Audio | Wired headset / helmet BT (optional, non-BT headset recommended) | you own it |

**Total per rider:** ~€20-35 depending on power. No recurring fees. No subscriptions.

---

## Architecture

```
┌─────────────────────────────────────────────┐
│               Your Android Phone             │
│                                             │
│  Mic ─→ AudioRecord ─→ Opus Encode ─→ Mesh  │─── Voice only ───┐
│                                             │                   │
│  Music Player ←─────────────────────────────│◄──── (your music, never sent)
│                                             │
│  Mesh ─→ Jitter Buffer → Opus Decode → Duck │◄── Inbound voice  │
│                Controller ──→ AudioTrack ────┘                   │
└───────────────────────────────────────────────────────────────────┘
                                      │
                                      │ BLE (GATT Serial)
                                      ▼
                              ┌────────────────┐
                              │  RYLR993       │
                              │  LoRa Radio    │
                              └────────┬───────┘
                                       │ RF
                               ←━━━━━━━━━━━━━━━→
                             Other riders' RYLR993 modules
```

**Key design decisions:**
- Voice only outbound. Music stays local, never leaves your phone.
- Automatic audio ducking: when inbound voice arrives, music volume ducks then restores.
- Opus codec @ 20 Kbps, 20ms frames → fits comfortably in LoRa 240 byte packets.
- Flood-gossip mesh: TTL=5, deduplication by packet hash. No ACKs (voice is real-time; retransmission of old frames is worse than silence).
- Jitter buffer absorbs LoRa's bursty packet delivery.

---

## Build Requirements

- Android Studio Hedgehog or later
- JDK 17
- Android SDK API 29+ (compileSdk 34)
- Android 10+ device for testing
- At least one RYLR993 LoRa module with BLE

---

## Quick Start

1. `git clone https://github.com/<you>/MotoMesh.git`
2. Open in Android Studio
3. Pair your RYLR993 module via BLE:
   - Scan for device named `RYLR993_XXXX`
   - Pair in Android Bluetooth settings (passkey: `123456`)
   - App will display node ID on connection
4. API configuration: Settings screen selects channel frequency, spreading factor, Bluetooth address
5. Build and install APK
6. Grant permissions on first run (Microphone, Bluetooth, Audio mods)
7. Repeat setup on each rider's phone
8. Ride.

---

## Running Tests

```bash
# Unit tests
./gradlew test

# Instrumented tests (requires connected device)
./gradlew connectedAndroidTest

# Build release APK
./gradlew assembleRelease
```

---

## Project Status

**Phase 1 in progress** — Opus audio loopback, BLE connection scaffolding.
Expected Phase 2: Single LoRa link (2 phones, 1 module) — ~4 weeks from start.

---

## Known Limitations

- LoRa range drops in heavy rain and dense urban canyons — expect 1–2 km minimum, 5 km clear day
- 150–250ms end-to-end voice latency is functional for motorcycle conversation but not enough for playing musical instruments over
- Module pairing via BLE: passkey `123456` must be pre-set on RYLR993; the app can't change it
- Android 12+ requires foreground service notification — expect a persistent "MotoMesh active" icon in the notification shade

---

## Contributing

Bug reports, hardware improvements, and pull requests welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).

---

## Credits

Built for wearable group voice under zero-cell constraints. Hardware target: RYLR993 LoRa module. Audio codec: Opus via Concentus library.
