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
        self.start_time = time.monotonic()
        self.events: list[dict] = []
        self._lock = threading.Lock()

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
                    "buf": b"", "rx": 0, "tx": 0, "last_frame": 0,
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
                    return
                if len(buf) < total_frame:
                    break
                node_id = struct.unpack_from("<H", buf, FRAME_HEADER_SIZE + LEN_FIELD_SIZE)[0]
                if info["node_id"] is None:
                    self._add_event("identify", f"Node {node_id} identified ({info['addr'][0]})")
                info["node_id"] = node_id
                info["rx"] += 1
                info["last_frame"] = time.monotonic()
                self.rx_count += 1
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
                        self.tx_count += 1
                    except (BrokenPipeError, OSError):
                        self._drop(other_fd, "send failed")
                buf = buf[total_frame:]
                info["buf"] = buf

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
                riders.append({
                    "node_id": info["node_id"],
                    "ip": info["addr"][0],
                    "port": info["addr"][1],
                    "connected_sec": int(now - info["connected_at"]),
                    "rx": info["rx"],
                    "tx": info["tx"],
                    "last_frame_sec": int(now - info["last_frame"]) if info["last_frame"] else None,
                    "alive": (now - info["last_frame"]) < 30 if info["last_frame"] else False,
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
    server = HTTPServer((host, port), DashboardHandler)
    print(f"🌐 Dashboard: http://{host if host != '0.0.0.0' else 'localhost'}:{port}")
    server.serve_forever()


# ─── Dashboard HTML (dark theme, auto-refresh) ────────────────────────
DASHBOARD_HTML = r"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>MotoMesh Relay</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
         background: #0D1117; color: #C9D1D9; padding: 24px; }
  h1 { color: #B5FFDA; font-size: 24px; margin-bottom: 4px; }
  .subtitle { color: #6E7681; font-size: 13px; margin-bottom: 24px; }
  .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px; margin-bottom: 24px; }
  .card { background: #161B22; border-radius: 10px; padding: 16px; }
  .card .label { color: #6E7681; font-size: 11px; text-transform: uppercase; letter-spacing: 1px; }
  .card .value { color: #C9D1D9; font-size: 28px; font-weight: 700; margin-top: 4px; }
  .card .value.green { color: #22FF88; }
  .card .value.yellow { color: #FFC658; }
  .card .value.red { color: #FF6F6F; }
  table { width: 100%; border-collapse: collapse; margin-top: 16px; }
  th { color: #6E7681; font-size: 11px; text-transform: uppercase; letter-spacing: 1px;
       text-align: left; padding: 8px 12px; border-bottom: 1px solid #21262D; }
  td { padding: 10px 12px; border-bottom: 1px solid #21262D; font-size: 14px; }
  .badge { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 12px; font-weight: 600; }
  .badge.alive { background: #1A3A2E; color: #22FF88; }
  .badge.dead { background: #3A1A1A; color: #FF6F6F; }
  .event-list { margin-top: 16px; }
  .event { padding: 6px 0; border-bottom: 1px solid #21262D; font-size: 13px; display: flex; gap: 8px; }
  .event .time { color: #6E7681; min-width: 70px; }
  .event.connect .icon { color: #22FF88; }
  .event.disconnect .icon { color: #FF6F6F; }
  .event.startup .icon { color: #B5FFDA; }
  .footer { margin-top: 24px; color: #484F58; font-size: 11px; text-align: center; }
</style>
</head>
<body>
  <h1>MotoMesh Relay</h1>
  <div class="subtitle" id="subtitle">Loading...</div>

  <div class="grid" id="statsGrid">
    <div class="card"><div class="label">Riders Online</div><div class="value green" id="riderCount">0</div></div>
    <div class="card"><div class="label">Frames Received</div><div class="value" id="rxTotal">0</div></div>
    <div class="card"><div class="label">Frames Sent</div><div class="value" id="txTotal">0</div></div>
    <div class="card"><div class="label">Uptime</div><div class="value" id="uptime">0s</div></div>
  </div>

  <div class="card">
    <div class="label">Connected Riders</div>
    <div id="ridersTable"><p style="color:#6E7681;padding:12px 0">No riders connected.</p></div>
  </div>

  <div class="card" style="margin-top:12px">
    <div class="label">Recent Events</div>
    <div class="event-list" id="eventList"><p style="color:#6E7681;padding:8px 0">No events yet.</p></div>
  </div>

  <div class="footer">MotoMesh Relay Server — data refreshes every 2s</div>

<script>
async function refresh() {
  try {
    const r = await fetch('/api/stats');
    const d = await r.json();

    document.getElementById('subtitle').textContent =
      `Relay on ${d.relay_host}:${d.relay_port} · ${d.status}`;

    document.getElementById('riderCount').textContent = d.rider_count;
    document.getElementById('rxTotal').textContent = d.rx_total.toLocaleString();
    document.getElementById('txTotal').textContent = d.tx_total.toLocaleString();

    const u = d.uptime_sec;
    const hrs = Math.floor(u / 3600);
    const mins = Math.floor((u % 3600) / 60);
    const secs = u % 60;
    document.getElementById('uptime').textContent =
      hrs > 0 ? `${hrs}h ${mins}m` : `${mins}m ${secs}s`;

    // Riders table
    const rt = document.getElementById('ridersTable');
    if (d.riders.length === 0) {
      rt.innerHTML = '<p style="color:#6E7681;padding:12px 0">No riders connected.</p>';
    } else {
      let html = `<table><tr><th>Node ID</th><th>IP</th><th>Duration</th><th>RX</th><th>TX</th><th>Status</th></tr>`;
      for (const r of d.riders) {
        const alive = r.alive && r.node_id !== null;
        const id = r.node_id !== null ? r.node_id : '—';
        const dur = fmtDuration(r.connected_sec);
        const status = alive ? '<span class="badge alive">Alive</span>' : '<span class="badge dead">Idle</span>';
        html += `<tr><td><b>${id}</b></td><td>${r.ip}</td><td>${dur}</td><td>${r.rx}</td><td>${r.tx}</td><td>${status}</td></tr>`;
      }
      html += '</table>';
      rt.innerHTML = html;
    }

    // Events
    const el = document.getElementById('eventList');
    if (d.events.length === 0) {
      el.innerHTML = '<p style="color:#6E7681;padding:8px 0">No events yet.</p>';
    } else {
      let html = '';
      for (const e of d.events.slice().reverse()) {
        const ts = new Date(e.t * 1000).toLocaleTimeString();
        const icon = e.kind === 'connect' ? '➕' : e.kind === 'disconnect' ? '➖' : '🚀';
        html += `<div class="event ${e.kind}"><span class="time">${ts}</span><span class="icon">${icon}</span><span>${e.text}</span></div>`;
      }
      el.innerHTML = html;
    }
  } catch(e) {
    document.getElementById('subtitle').textContent = '⚠️ Relay unreachable — retrying...';
  }
}

function fmtDuration(s) {
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ${s % 60}s`;
  const h = Math.floor(m / 60);
  return `${h}h ${m % 60}m`;
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