# MotoMesh Cellular Testing Guide

Tested on Fedora 44 (relay) + Pixel 9 (Android 16) + OnePlus N10 5G (Android 10).

---

## Architecture

```
Phone A ──TCP──┐
               ├── Relay Server ──fans frames──► Phone B
Phone C ──TCP──┘       (cloud)
```

The relay server runs on a **cloud VM** so all phones connect over the internet. No one needs to be on the same WiFi. Each phone opens a TCP socket to the relay, sends Opus audio frames, and the relay broadcasts them to all other connected phones.

---

## Option A: Quick Local Test (same WiFi)

### 1. Start the Relay Server

On the host computer:
```bash
cd /path/to/MotoMesh
python3 relay_server.py
```
You'll see:
```
🚀 MotoMesh Relay: 0.0.0.0:60005
📊 Dashboard:      http://localhost:8080
```

### 2. Find Your LAN IP

```bash
ip addr show | grep "inet " | grep -v 127.0.0.1
# Look for: 192.168.x.x
```

### 3. Install the APK on Each Phone

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or share the APK file directly.

### 4. Configure Each Phone

1. Open **MotoMesh**
2. Tap gear icon (bottom-right) → **Network**
3. Set **Relay host** to the computer's LAN IP (e.g. `192.168.1.48`)
4. Set **Relay port** to `60005`
5. Tap **Save Settings**
6. Tap the **LOOPBACK** badge until it shows **CELLULAR**
7. Tap green **Connect** button
8. Grant **RECORD_AUDIO** when prompted

### 5. Talk

- Tap mic button to unmute (turns green)
- Other riders appear as cards in the list
- **Dashboard:** `http://localhost:8080` shows real-time stats

---

## Option B: Cloud Relay (ride anywhere)

Deploy the relay to [Fly.io](https://fly.io) — free tier, no credit card needed, runs 24/7.

### Step 1: Sign up for Fly.io

Open https://fly.io/app/signup and create an account using GitHub or Google.

### Step 2: Launch from your browser

1. Go to https://fly.io/apps
2. Click **Create app**
3. Name it `motomesh-relay`
4. Choose a region close to you

Or use the CLI (one-time setup):

```bash
# Install flyctl
curl -fsSL https://fly.io/install.sh | sh

# Log in
flyctl auth login

# Deploy (from the MotoMesh/relay-server directory)
cd MotoMesh/relay-server
flyctl launch
flyctl deploy
```

### Step 3: Get your relay URL

After deploy, run:
```bash
flyctl info | grep Hostname
# → motomesh-relay.fly.dev
```

### Step 4: Configure phones to use the cloud relay

Each phone sets **Relay host** to `motomesh-relay.fly.dev` (port `60005`).

---

## What Each Piece Does

| Component | File | Role |
|---|---|---|
| `relay_server.py` | Runs on cloud VM | Accepts TCP, fans audio frames between riders |
| `CellularBridge.kt` | In the Android app | Connects to relay, sends/receives frames |
| `MainActivity.kt` | In the Android app | UI + transport mode switching |
| Dashboard | Port 8080 on relay | Real-time stats (HTML, auto-refresh 2s) |

### Relay Server Features

- **Bandwidth tracking** — KB/s per rider, total bytes
- **Speaking detection** — green pulsing dot when actively transmitting (>8 fps)
- **Live frame-rate sparkline** — 30-sample bar chart
- **Per-rider stats** — FPS, bytes, alive/idle status
- **Event log** — connect/disconnect events with timestamps

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| "Cellular failed" | Check relay host/port. Ensure the relay server is actually running. |
| App crashes on launch | Unlock phone first. Android 16 restriction. |
| No riders appear | All phones must connect to the **same** relay server. |
| Can't hear anyone | Unmute mic. Check phone volume. Verify AudioTrack is running. |
| Permission denied | Settings → Apps → MotoMesh → Permissions → grant Microphone + Notifications |
| Port in use | `kill $(lsof -ti :60005)` then restart relay |

---

## Dashboard Reference

Open `http://[relay-host]:8080` in any browser:

```
┌─────────────────────────────────────────────┐
│  ◉ MotoMesh Relay                    ● Running │
│  Relay: 0.0.0.0:60005 · Uptime: 2m 05s     │
├─────────────────────────────────────────────┤
│  Riders │ RX       │ TX       │ RX BW  │   │
│  2      │ 1,234    │ 987      │ 45 KB/s│   │
├─────────────────────────────────────────────┤
│  Live Frame Rate  ▇▅▃▇▆▄▃▂▁▃▅▇▆▄▂  24 fps  │
├─────────────────────────────────────────────┤
│  Rider #3 ● ●● ●●●  -67dBm  Live          │
│  Rider #7 ○ ○○ ○○○  -72dBm  Idle          │
├─────────────────────────────────────────────┤
│  Event Log                                  │
│  19:30  ➕  Rider connected: 192.168.1.14   │
│  19:30  🔗  Node 3 identified               │
└─────────────────────────────────────────────┘
```

Built from commit `063a634` — MotoMesh v0.3.0