# MotoMesh Cellular Testing Guide

Tested on Fedora 44 (relay) + Pixel 9 (Android 16) + OnePlus N10 5G (Android 10).

---

## 1. Start the Relay Server

On the **host computer** (the one running the relay):

```bash
cd /path/to/MotoMesh
python3 relay_server.py
```

You should see:
```
🚀 MotoMesh Relay: 0.0.0.0:60005
📊 Dashboard:      http://localhost:8080
```

The relay stays running until you press Ctrl+C.

**Dashboard:** Open `http://localhost:8080` in a browser to see live stats — riders, frame rates, bandwidth, and event log.

---

## 2. Find the Host IP

On the host computer, run:

```bash
ip addr show | grep "inet " | grep -v 127.0.0.1
```

Look for your LAN IP (likely `192.168.x.x`). This is the address your friends' phones will connect to.

---

## 3. Install the App on Each Phone

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or share the APK file (`app/build/outputs/apk/debug/app-debug.apk`) via any file-sharing method.

---

## 4. Configure Each Phone

On each phone:

1. Open **MotoMesh**
2. Tap the **gear icon** (bottom-right) → **Network**
3. Set **Relay host** to the host computer's IP (e.g. `192.168.1.48`)
4. Set **Relay port** to `60005`
5. Tap **Save Settings**

---

## 5. Connect

On each phone:

1. Tap the **LOOPBACK badge** at the bottom until it shows **CELLULAR**
2. Tap the green **Connect** button
3. Grant the **RECORD_AUDIO** permission when prompted
4. The status dot should turn **green** and show "Cellular"

---

## 6. Talk

- Tap the **mic button** to unmute (turns green)
- The green VOX dot in the status bar pulses when audio is being sent
- Other riders on the same relay appear in the rider list as cards
- Tap a rider card to see RSSI, loss rate, and last heard time

---

## 7. Monitor

On the host computer, the dashboard at `http://localhost:8080` shows:

- **Riders** — who's connected and how many frames they've sent
- **Speaking** — green pulsing dot when actively transmitting (>8 fps)
- **Bandwidth** — real-time KB/s and total bytes per rider
- **Live FPS** — frame rate sparkline chart updating every 2 seconds
- **Event log** — connect/disconnect/identify events with timestamps

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| "Cellular failed" | Check relay host IP and port. Phone must be on the same WiFi network. |
| App crashes on launch | Unlock the phone first, then launch. Android 16 restrictions. |
| No riders appear | Make sure all phones are on the same relay server (same IP:port). |
| Can't hear other riders | Check mute button (should be green/unmuted). Check phone volume. |
| Permission denied | Settings → Apps → MotoMesh → Permissions → grant Microphone + Notifications |
| Relay won't start | Port 60005 or 8080 might be in use: `kill $(lsof -ti :60005)` |

---

## Quick Reference

```
Relay server:  python3 relay_server.py
Dashboard:     http://localhost:8080
Relay port:    60005 (TCP)
Default host:  192.168.1.48 (replace with your IP)
```

Built from commit `b31111f` — MotoMesh v0.3.0
