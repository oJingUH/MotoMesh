#!/usr/bin/env python3
"""
MotoMesh TCP Relay Server — with Web Dashboard

Usage:
  python3 relay_server.py [--relay-port PORT] [--web-port WP] [--host HOST]

Default: relay on 0.0.0.0:60005, dashboard at http://0.0.0.0:8080
"""

import argparse
import json
import select
import socket
import struct
import sys
import threading
import time
from datetime import datetime, timezone
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn


class ThreadedHTTPServer(ThreadingMixIn, HTTPServer):
    """Handle HTTP requests in separate threads so a slow client doesn't block the dashboard."""
    allow_reuse_address = True
    daemon_threads = True

# ─── Frame protocol constants ─────────────────────────────────────────
FRAME_HEADER = 0xBB
FRAME_HEADER_SIZE = 1
LEN_FIELD_SIZE = 2
NODE_ID_SIZE = 2
FRAME_OVERHEAD = FRAME_HEADER_SIZE + LEN_FIELD_SIZE + NODE_ID_SIZE
MAX_FRAME_BYTES = 4096
POLL_TIMEOUT = 1.0
STATS_INTERVAL = 2.0


def parse_args():
    p = argparse.ArgumentParser(description="MotoMesh TCP Relay + Dashboard")
    p.add_argument("--relay-port", type=int, default=60005, help="TCP relay port")
    p.add_argument("--web-port", type=int, default=8080, help="Web dashboard port")
    p.add_argument("--host", type=str, default="0.0.0.0", help="Bind address")
    p.add_argument("--verbose", "-v", action="store_true", help="Log every frame")
    return p.parse_args()


# ─── Relay server ────────────────────────────────────────────────────
class RelayServer:
    def __init__(self, host: str, relay_port: int, verbose: bool = False):
        self.host = host
        self.relay_port = relay_port
        self.verbose = verbose
        self.clients: dict[int, dict] = {}  # fd -> client info
        self.client_map: dict[socket.socket, int] = {}  # sock -> fd
        self.sock_map: dict[int, socket.socket] = {}  # fd -> sock
        self.server_sock: socket.socket | None = None
        self.poll = select.poll()
        self.rx_count = 0
        self.tx_count = 0
        self.rx_bytes_total = 0
        self.tx_bytes_total = 0
        self.start_time = time.monotonic()
        self.events: list[dict] = []
        self._lock = threading.Lock()
        self._pending_events: list[tuple[str, str]] = []

    def start(self):
        self.server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server_sock.bind((self.host, self.relay_port))
        self.server_sock.listen(16)
        self.server_sock.setblocking(False)
        self.poll.register(self.server_sock, select.POLLIN)
        self._add_event("startup", f"Relay listening on {self.host}:{self.relay_port}")
        print(f"🚀 MotoMesh Relay: {self.host}:{self.relay_port}")
        print(f"📊 Dashboard:      http://{self.host if self.host != '0.0.0.0' else 'localhost'}:{args.web_port}")

    def run(self):
        while True:
            events = self.poll.poll(POLL_TIMEOUT * 1000)
            for fd, event in events:
                if fd == self.server_sock.fileno():
                    self._accept()
                elif event & (select.POLLIN | select.POLLHUP):
                    self._handle(fd)
                elif event & select.POLLERR:
                    self._drop(fd, "POLLERR")

    def _accept(self):
        try:
            client, addr = self.server_sock.accept()
            client.setblocking(False)
            fd = client.fileno()
            with self._lock:
                self.clients[fd] = {
                    "node_id": None, "addr": addr, "connected_at": time.monotonic(),
                    "buf": b"", "rx": 0, "tx": 0,
                    "rx_bytes": 0, "tx_bytes": 0,
                    "last_frame": 0, "frame_times": [],
                }
                self.client_map[client] = fd
                self.sock_map[fd] = client
            self.poll.register(client, select.POLLIN)
            self._add_event("connect", f"Rider connected: {addr[0]}:{addr[1]}")
        except OSError as e:
            print(f"Accept error: {e}")

    def _handle(self, fd: int):
        sock = self.sock_map.get(fd)
        if sock is None:
            return
        try:
            data = sock.recv(4096)
        except (ConnectionResetError, ConnectionAbortedError, OSError):
            data = b""
        if not data:
            self._drop(fd, "disconnected")
            return

        with self._lock:
            info = self.clients.get(fd)
            if info is None:
                return
            info["buf"] += data
            buf = info["buf"]
            drop_fds = []
            while len(buf) >= FRAME_OVERHEAD:
                if buf[0] != FRAME_HEADER:
                    idx = buf.find(bytes([FRAME_HEADER]), 1)
                    buf = buf[idx:] if idx >= 0 else b""
                    info["buf"] = buf
                    continue
                total_payload = struct.unpack_from("<H", buf, FRAME_HEADER_SIZE)[0]
                total_frame = FRAME_OVERHEAD + total_payload - NODE_ID_SIZE
                if total_frame > MAX_FRAME_BYTES:
                    info["buf"] = b""
                    break
                if len(buf) < total_frame:
                    break
                node_id = struct.unpack_from("<H", buf, FRAME_HEADER_SIZE + LEN_FIELD_SIZE)[0]
                if info["node_id"] is None:
                    # Schedule event outside lock
                    self._pending_events.append(("identify", f"Node {node_id} identified ({info['addr'][0]})"))
                info["node_id"] = node_id
                info["rx"] += 1
                info["rx_bytes"] += total_frame
                info["last_frame"] = time.monotonic()
                info["frame_times"].append(time.monotonic())
                if len(info["frame_times"]) > 60:
                    info["frame_times"] = info["frame_times"][-60:]
                self.rx_count += 1
                self.rx_bytes_total += total_frame
                if self.verbose:
                    print(f"📡 Node {node_id}  frame")
                frame = buf[:total_frame]
                for other_fd, other_info in self.clients.items():
                    if other_fd == fd:
                        continue
                    other_sock = self.sock_map.get(other_fd)
                    if other_sock is None:
                        continue
                    try:
                        other_sock.sendall(frame)
                        other_info["tx"] += 1
                        other_info["tx_bytes"] += total_frame
                        self.tx_count += 1
                        self.tx_bytes_total += total_frame
                    except (BrokenPipeError, OSError):
                        drop_fds.append(other_fd)
                buf = buf[total_frame:]
                info["buf"] = buf
        # Drop failed clients outside lock to avoid deadlock
        for other_fd in drop_fds:
            self._drop(other_fd, "send failed")
        # Flush pending events outside lock
        for kind, text in self._pending_events:
            self._add_event(kind, text)
        self._pending_events.clear()

    def _drop(self, fd: int, reason: str):
        sock = self.sock_map.pop(fd, None)
        with self._lock:
            info = self.clients.pop(fd, {})
        if sock is not None:
            try:
                self.poll.unregister(sock)
                sock.close()
            except OSError:
                pass
            self.client_map.pop(sock, None)
        nid = info.get("node_id", "?")
        addr = info.get("addr", ("?",))[0]
        self._add_event("disconnect", f"Node {nid} disconnected ({addr}): {reason}")

    def _add_event(self, kind: str, text: str):
        with self._lock:
            self.events.append({"t": time.time(), "kind": kind, "text": text})
            if len(self.events) > 200:
                self.events = self.events[-200:]

    def get_stats(self) -> dict:
        now = time.monotonic()
        uptime = now - self.start_time
        with self._lock:
            riders = []
            for fd, info in list(self.clients.items()):
                # Compute instantaneous frame rate (frames in last 5 seconds)
                recent = [t for t in info["frame_times"] if now - t < 5]
                instant_fps = len(recent) / 5.0 if len(recent) > 1 else 0.0
                # Speaking detection: >8 frames/sec = actively transmitting voice
                is_speaking = instant_fps > 8
                riders.append({
                    "node_id": info["node_id"],
                    "ip": info["addr"][0],
                    "port": info["addr"][1],
                    "connected_sec": int(now - info["connected_at"]),
                    "rx": info["rx"],
                    "tx": info["tx"],
                    "rx_bytes": info["rx_bytes"],
                    "tx_bytes": info["tx_bytes"],
                    "last_frame_sec": int(now - info["last_frame"]) if info["last_frame"] else None,
                    "alive": (now - info["last_frame"]) < 30 if info["last_frame"] else False,
                    "instant_fps": round(instant_fps, 1),
                    "is_speaking": is_speaking,
                    "frame_times": info["frame_times"][-30:],
                })
            events = list(self.events[-50:])
        return {
            "status": "running",
            "uptime_sec": int(uptime),
            "relay_host": self.host,
            "relay_port": self.relay_port,
            "riders": riders,
            "rider_count": len(riders),
            "rx_total": self.rx_count,
            "tx_total": self.tx_count,
            "rx_bytes_total": self.rx_bytes_total,
            "tx_bytes_total": self.tx_bytes_total,
            "events": events,
        }


# ─── Web dashboard HTTP handler ───────────────────────────────────────
shared_server: RelayServer | None = None


class DashboardHandler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass  # silence HTTP logs

    def do_GET(self):
        if self.path == "/api/stats":
            stats = shared_server.get_stats() if shared_server else {"status": "stopped"}
            self._json(200, stats)
        elif self.path == "/":
            self._html(200, DASHBOARD_HTML)
        else:
            self._html(404, "<h1>404</h1>")

    def _json(self, status, data):
        body = json.dumps(data).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _html(self, status, body):
        body = body.encode()
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET")
        self.end_headers()


def start_web_server(host: str, port: int):
    server = ThreadedHTTPServer((host, port), DashboardHandler)
    print(f"🌐 Dashboard: http://{host if host != '0.0.0.0' else 'localhost'}:{port}")
    server.serve_forever()


# ─── Dashboard HTML (dark theme, auto-refresh, sparklines, bandwidth) ──
DASHBOARD_HTML = r"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>MotoMesh Relay</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
         background: #0D1117; color: #C9D1D9; padding: 24px; min-height: 100vh; }

  .header { display: flex; align-items: center; gap: 16px; margin-bottom: 24px; }
  .header .logo { width: 40px; height: 40px; border-radius: 10px; background: #1A3A2E;
                  display: flex; align-items: center; justify-content: center;
                  font-size: 20px; color: #22FF88; font-weight: bold; }
  .header h1 { color: #B5FFDA; font-size: 22px; font-weight: 700; }
  .header .status-badge { margin-left: auto; padding: 4px 14px; border-radius: 20px;
                          font-size: 12px; font-weight: 600; background: #1A3A2E; color: #22FF88; }

  .server-info { display: flex; gap: 16px; flex-wrap: wrap; margin-bottom: 20px; }
  .info-chip { background: #161B22; border-radius: 8px; padding: 8px 16px; display: flex; align-items: center; gap: 8px; font-size: 13px; }
  .info-chip .label { color: #6E7681; }
  .info-chip .value { color: #C9D1D9; font-weight: 500; }

  .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 12px; margin-bottom: 20px; }
  .card { background: #161B22; border-radius: 12px; padding: 18px; }
  .card .label { color: #6E7681; font-size: 11px; text-transform: uppercase; letter-spacing: 1px; margin-bottom: 6px; }
  .card .value { color: #C9D1D9; font-size: 28px; font-weight: 700; }
  .card .value.green { color: #22FF88; }
  .card .value.yellow { color: #FFC658; }
  .card .value.blue { color: #58A6FF; }
  .card .value.red { color: #FF6F6F; }
  .card .sub { font-size: 12px; color: #6E7681; margin-top: 4px; }

  /* Live sparkline bar chart */
  .sparkline { display: flex; gap: 2px; align-items: flex-end; height: 28px; margin-top: 6px; }
  .sparkline .bar { width: 4px; border-radius: 2px; background: #22FF88; min-height: 2px; transition: height 0.5s ease; }
  .sparkline .bar.inactive { background: #21262D; }

  .section-title { font-size: 13px; font-weight: 600; color: #C9D1D9; margin-bottom: 12px;
                   display: flex; align-items: center; gap: 8px; }
  .section-title .count { background: #21262D; padding: 1px 8px; border-radius: 10px; font-size: 11px; }

  table { width: 100%; border-collapse: collapse; }
  th { color: #6E7681; font-size: 11px; text-transform: uppercase; letter-spacing: 1px;
       text-align: left; padding: 10px 12px; border-bottom: 1px solid #21262D; }
  td { padding: 12px; border-bottom: 1px solid #21262D; font-size: 14px; }
  tr:last-child td { border-bottom: none; }
  .badge { display: inline-block; padding: 2px 10px; border-radius: 12px; font-size: 12px; font-weight: 600; }
  .badge.alive { background: #1A3A2E; color: #22FF88; }
  .badge.dead { background: #3A1A1A; color: #FF6F6F; }
  .badge.speaking { background: #0D3A1A; color: #22FF88; animation: pulse 0.8s ease-in-out infinite; }

  @keyframes pulse {
    0%, 100% { opacity: 1; }
    50% { opacity: 0.4; }
  }

  .speaking-dot { display: inline-block; width: 10px; height: 10px; border-radius: 50%;
                  margin-right: 6px; vertical-align: middle; }
  .speaking-dot.on { background: #22FF88; box-shadow: 0 0 6px #22FF8866; animation: pulse 0.8s ease-in-out infinite; }
  .speaking-dot.off { background: #21262D; }

  .events { margin-top: 8px; max-height: 240px; overflow-y: auto; }
  .events::-webkit-scrollbar { width: 4px; }
  .events::-webkit-scrollbar-thumb { background: #21262D; border-radius: 2px; }
  .event { display: flex; gap: 10px; padding: 7px 4px; border-bottom: 1px solid #21262D; font-size: 13px; align-items: center; }
  .event .time { color: #6E7681; font-family: monospace; font-size: 12px; min-width: 70px; }
  .event .icon { font-size: 14px; }
  .event.connect .icon { color: #22FF88; }
  .event.disconnect .icon { color: #FF6F6F; }
  .event.identify .icon { color: #B5FFDA; }
  .event .text { color: #C9D1D9; }

  .footer { margin-top: 32px; padding-top: 16px; border-top: 1px solid #21262D;
            color: #484F58; font-size: 11px; text-align: center; display: flex; justify-content: center; gap: 16px; }

  @media (max-width: 600px) {
    body { padding: 12px; }
    .grid { grid-template-columns: 1fr 1fr; }
    td:nth-child(3), th:nth-child(3) { display: none; }
  }
</style>
</head>
<body>
  <div class="header">
    <div class="logo">◉</div>
    <h1>MotoMesh Relay</h1>
    <span class="status-badge" id="statusBadge">● Running</span>
  </div>

  <div class="server-info" id="serverInfo"></div>

  <div class="grid">
    <div class="card"><div class="label">Riders</div><div class="value green" id="riderCount">0</div><div class="sub" id="riderList">waiting for connections</div></div>
    <div class="card"><div class="label">RX</div><div class="value" id="rxTotal">0</div><div class="sub" id="rxRate">0 /s</div></div>
    <div class="card"><div class="label">TX</div><div class="value" id="txTotal">0</div><div class="sub" id="txRate">0 /s</div></div>
    <div class="card"><div class="label">RX Bandwidth</div><div class="value blue" id="rxBW">—</div><div class="sub" id="rxBWTotal">0 B total</div></div>
    <div class="card"><div class="label">TX Bandwidth</div><div class="value blue" id="txBW">—</div><div class="sub" id="txBWTotal">0 B total</div></div>
    <div class="card"><div class="label">Uptime</div><div class="value" id="uptime">0s</div><div class="sub" id="uptimeSub">started just now</div></div>
  </div>

  <div class="card" style="margin-bottom:12px">
    <div class="section-title">Live Frame Rate <span class="count" id="fpsBadge">0 fps</span></div>
    <div class="sparkline" id="fpsSparkline"></div>
  </div>

  <div class="card">
    <div class="section-title">Connected Riders <span class="count" id="riderCountBadge">0</span></div>
    <div id="ridersTable"><p style="color:#6E7681;padding:12px 0">No riders connected. Open the app on your phone and tap Connect.</p></div>
  </div>

  <div class="card" style="margin-top:12px">
    <div class="section-title">Event Log</div>
    <div class="events" id="eventList"><p style="color:#6E7681;padding:8px 0">Waiting for activity…</p></div>
  </div>

  <div class="footer">
    <span>MotoMesh Relay v0.3.0</span>
    <span>Refreshes every 2s</span>
    <span>Dashboard: port 8080 · Relay: port 60005</span>
  </div>

<script>
let prevRx = 0, prevTx = 0, prevRxBytes = 0, prevTxBytes = 0;
let frameHistory = [];

function fmtBytes(b) {
  if (b < 1024) return b + ' B';
  if (b < 1048576) return (b / 1024).toFixed(1) + ' KB';
  return (b / 1048576).toFixed(1) + ' MB';
}

function fmtBytesRate(b) {
  if (b < 1024) return b + ' B/s';
  if (b < 1048576) return (b / 1024).toFixed(1) + ' KB/s';
  return (b / 1048576).toFixed(1) + ' MB/s';
}

function fmtDuration(s) {
  if (s < 60) return s + 's';
  const m = Math.floor(s / 60);
  if (m < 60) return m + 'm ' + (s % 60) + 's';
  const h = Math.floor(m / 60);
  return h + 'h ' + (m % 60) + 'm';
}

async function refresh() {
  try {
    const r = await fetch('/api/stats');
    const d = await r.json();
    const now = Date.now();

    // Status badge
    document.getElementById('statusBadge').textContent = '● ' + d.status;

    // Server info chips
    const info = document.getElementById('serverInfo');
    info.innerHTML = `
      <div class="info-chip"><span class="label">Relay</span><span class="value">${d.relay_host}:${d.relay_port}</span></div>
      <div class="info-chip"><span class="label">Dashboard</span><span class="value">${window.location.hostname}:8080</span></div>
      <div class="info-chip"><span class="label">Uptime</span><span class="value">${fmtDuration(d.uptime_sec)}</span></div>
      <div class="info-chip"><span class="label">Total Data</span><span class="value">${fmtBytes(d.rx_bytes_total + d.tx_bytes_total)}</span></div>
    `;

    // Rates (frames + bytes per poll interval)
    const rxRate = d.rx_total - prevRx;
    const txRate = d.tx_total - prevTx;
    const rxBytesRate = d.rx_bytes_total - prevRxBytes;
    const txBytesRate = d.tx_bytes_total - prevTxBytes;
    prevRx = d.rx_total;
    prevTx = d.tx_total;
    prevRxBytes = d.rx_bytes_total;
    prevTxBytes = d.tx_bytes_total;

    document.getElementById('rxRate').textContent = rxRate + ' /s';
    document.getElementById('txRate').textContent = txRate + ' /s';

    // Bandwidth cards
    document.getElementById('rxBW').textContent = fmtBytesRate(rxBytesRate);
    document.getElementById('rxBWTotal').textContent = fmtBytes(d.rx_bytes_total) + ' total';
    document.getElementById('txBW').textContent = fmtBytesRate(txBytesRate);
    document.getElementById('txBWTotal').textContent = fmtBytes(d.tx_bytes_total) + ' total';

    // Cards
    document.getElementById('riderCount').textContent = d.rider_count;
    document.getElementById('riderCountBadge').textContent = d.rider_count;
    document.getElementById('rxTotal').textContent = d.rx_total.toLocaleString();
    document.getElementById('txTotal').textContent = d.tx_total.toLocaleString();

    const u = d.uptime_sec;
    document.getElementById('uptime').textContent = fmtDuration(u);
    const startDate = new Date(now - u * 1000);
    document.getElementById('uptimeSub').textContent = 'since ' + startDate.toLocaleTimeString();

    // Sparkline (frame rate history)
    const totalFps = rxRate + txRate;
    frameHistory.push(totalFps);
    if (frameHistory.length > 30) frameHistory = frameHistory.slice(-30);
    const maxFps = Math.max(...frameHistory, 1);
    const sl = document.getElementById('fpsSparkline');
    sl.innerHTML = frameHistory.map(f => {
      const pct = Math.max(2, (f / maxFps) * 24);
      const active = f > 0;
      return `<div class="bar${active ? '' : ' inactive'}" style="height:${pct}px"></div>`;
    }).join('');
    document.getElementById('fpsBadge').textContent = totalFps + ' fps';

    // Rider list subtitle
    const riderNames = d.riders.filter(r => r.node_id !== null).map(r => '#' + r.node_id).join(', ');
    document.getElementById('riderList').textContent = riderNames || 'waiting for connections';

    // Riders table
    const rt = document.getElementById('ridersTable');
    if (d.riders.length === 0) {
      rt.innerHTML = '<p style="color:#6E7681;padding:16px 0">No riders connected. Open the app on your phone and tap Connect.</p>';
    } else {
      let html = `<table><tr><th>Node</th><th>Speaking</th><th>IP</th><th>Duration</th><th>RX</th><th>TX</th><th>FPS</th><th>BW</th><th>Status</th></tr>`;
      for (const r of d.riders) {
        const alive = r.alive && r.node_id !== null;
        const id = r.node_id !== null ? '#' + r.node_id : '—';
        const dur = fmtDuration(r.connected_sec);
        const status = alive ? '<span class="badge alive">Live</span>' : '<span class="badge dead">Idle</span>';
        const speaking = r.is_speaking ? '<span class="speaking-dot on" title="Speaking now"></span>' : '<span class="speaking-dot off" title="Idle"></span>';
        const fpsLabel = r.node_id !== null ? r.instant_fps + '/s' : '—';
        const bw = fmtBytesRate(r.rx_bytes / Math.max(r.connected_sec, 1));
        html += `<tr><td><b>${id}</b></td><td>${speaking}</td><td style="color:#6E7681;font-size:13px">${r.ip}</td><td>${dur}</td><td>${r.rx}</td><td>${r.tx}</td><td>${fpsLabel}</td><td style="font-size:12px;color:#58A6FF">${bw}</td><td>${status}</td></tr>`;
      }
      html += '</table>';
      rt.innerHTML = html;
    }

    // Events
    const el = document.getElementById('eventList');
    if (d.events.length === 0) {
      el.innerHTML = '<p style="color:#6E7681;padding:8px 0">Waiting for activity…</p>';
    } else {
      let html = '';
      for (const e of d.events.slice().reverse().slice(0, 40)) {
        const ts = new Date(e.t * 1000).toLocaleTimeString();
        const icon = e.kind === 'connect' ? '➕' : e.kind === 'disconnect' ? '➖' : e.kind === 'identify' ? '🔗' : '🚀';
        html += `<div class="event ${e.kind}"><span class="time">${ts}</span><span class="icon">${icon}</span><span class="text">${e.text}</span></div>`;
      }
      el.innerHTML = html;
    }
  } catch(e) {
    document.getElementById('statusBadge').textContent = '○ Offline';
    document.getElementById('riderCount').textContent = '—';
  }
}

refresh();
setInterval(refresh, 2000);
</script>
</body>
</html>"""


# ─── Main ─────────────────────────────────────────────────────────────
def main():
    global args, shared_server
    args = parse_args()

    server = RelayServer(args.host, args.relay_port, args.verbose)
    shared_server = server

    # Start web dashboard in background thread
    web_thread = threading.Thread(
        target=start_web_server, args=(args.host, args.web_port), daemon=True
    )
    web_thread.start()
    time.sleep(0.3)

    try:
        server.start()
        server.run()
    except KeyboardInterrupt:
        print("\n👋 Shutting down...")
    finally:
        for fd, sock in list(server.sock_map.items()):
            try:
                sock.close()
            except OSError:
                pass
        if server.server_sock:
            server.server_sock.close()


if __name__ == "__main__":
    main()
