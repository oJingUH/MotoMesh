#!/usr/bin/env python3
"""
MotoMesh TCP Relay Server

A minimal TCP relay for MotoMesh cellular transport mode.
Accepts connections from riders, forwards Opus voice frames between all connected riders.

Frame format (app side):
  [0xBB][len LE 2B][nodeId LE 2B][Opus payload]

Relay behaviour:
  1. Receives framed packet from any rider
  2. Broadcasts the raw frame to ALL other connected riders
  3. No storage, no re-ordering, no ACKs — pure stateless flood

Usage:
  python3 relay_server.py [--port PORT] [--host HOST]

Default: binds 0.0.0.0:60005
"""

import argparse
import select
import socket
import struct
import sys
import time

FRAME_HEADER = 0xBB
FRAME_HEADER_SIZE = 1
LEN_FIELD_SIZE = 2
NODE_ID_SIZE = 2
FRAME_OVERHEAD = FRAME_HEADER_SIZE + LEN_FIELD_SIZE + NODE_ID_SIZE
MAX_FRAME_BYTES = 4096

POLL_TIMEOUT = 1.0  # seconds
STATS_INTERVAL = 10.0  # seconds between stats log


def parse_args():
    parser = argparse.ArgumentParser(description="MotoMesh TCP Relay Server")
    parser.add_argument("--port", type=int, default=60005, help="Listen port")
    parser.add_argument("--host", type=str, default="0.0.0.0", help="Bind address")
    parser.add_argument("--verbose", "-v", action="store_true", help="Log every frame")
    return parser.parse_args()


class RelayServer:
    def __init__(self, host: str, port: int, verbose: bool = False):
        self.host = host
        self.port = port
        self.verbose = verbose
        self.clients: dict[socket.socket, dict] = {}  # sock -> {node_id, addr, connected_at}
        self.server_sock: socket.socket | None = None
        self.rx_count = 0
        self.tx_count = 0
        self.last_stats = time.monotonic()

    def start(self):
        self.server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server_sock.bind((self.host, self.port))
        self.server_sock.listen(16)
        self.server_sock.setblocking(False)
        print(f"🚀 MotoMesh Relay listening on {self.host}:{self.port}")
        print(f"   Frame overhead: {FRAME_OVERHEAD}B (0xBB + len LE + nodeId LE)")

    def run(self):
        poll = select.poll()
        poll.register(self.server_sock, select.POLLIN)

        while True:
            events = poll.poll(POLL_TIMEOUT * 1000)

            for fd, event in events:
                if fd == self.server_sock.fileno():
                    self._accept_new(poll)
                elif event & (select.POLLIN | select.POLLHUP):
                    self._handle_client(fd, poll)
                elif event & select.POLLERR:
                    self._drop_client(fd, poll, "POLLERR")

            self._print_stats()

    def _accept_new(self, poll):
        try:
            client_sock, addr = self.server_sock.accept()
            client_sock.setblocking(False)
            self.clients[client_sock] = {
                "node_id": None,
                "addr": addr,
                "connected_at": time.monotonic(),
                "buf": b"",
            }
            poll.register(client_sock, select.POLLIN)
            print(f"➕ Rider connected: {addr[0]}:{addr[1]}  (total: {len(self.clients)})")
        except OSError as e:
            print(f"❌ Accept error: {e}")

    def _handle_client(self, fd: int, poll):
        sock = next((s for s in self.clients if s.fileno() == fd), None)
        if sock is None:
            return

        try:
            data = sock.recv(4096)
        except (ConnectionResetError, ConnectionAbortedError, OSError):
            data = b""

        if not data:
            self._drop_client(fd, poll, "disconnected")
            return

        info = self.clients[sock]
        info["buf"] += data

        # Parse frames from buffer
        buf = info["buf"]
        while len(buf) >= FRAME_OVERHEAD:
            if buf[0] != FRAME_HEADER:
                # Out of sync — scan for next 0xBB
                idx = buf.find(bytes([FRAME_HEADER]), 1)
                if idx < 0:
                    info["buf"] = b""
                    return
                print(f"⚠️  Resync: discarded {idx} bytes from {info['addr']}")
                buf = buf[idx:]
                info["buf"] = buf
                continue

            # Read length field (uint16 LE)
            total_payload = struct.unpack_from("<H", buf, FRAME_HEADER_SIZE)[0]
            total_frame = FRAME_OVERHEAD + total_payload - NODE_ID_SIZE  # payload includes nodeId

            if total_frame > MAX_FRAME_BYTES:
                print(f"⚠️  Invalid frame size {total_frame} from {info['addr']} — dropping")
                info["buf"] = b""
                return

            if len(buf) < total_frame:
                break  # Wait for more data

            # Extract sender node ID
            node_id = struct.unpack_from("<H", buf, FRAME_HEADER_SIZE + LEN_FIELD_SIZE)[0]
            info["node_id"] = node_id
            self.rx_count += 1

            if self.verbose:
                print(f"📡 Frame from node {node_id} ({len(buf)}B, total clients: {len(self.clients)})")

            # Broadcast to ALL other riders
            frame = buf[:total_frame]
            for other_sock in list(self.clients.keys()):
                if other_sock is sock:
                    continue
                try:
                    other_sock.sendall(frame)
                    self.tx_count += 1
                except (BrokenPipeError, OSError):
                    self._drop_client(other_sock.fileno(), poll, "send failed", sock)

            # Consume from buffer
            buf = buf[total_frame:]
            info["buf"] = buf

    def _drop_client(self, fd: int, poll, reason: str, sock: socket.socket | None = None):
        if sock is None:
            sock = next((s for s in self.clients if s.fileno() == fd), None)
        if sock is None:
            return

        info = self.clients.pop(sock, {})
        try:
            poll.unregister(sock)
            sock.close()
        except OSError:
            pass

        node_id = info.get("node_id", "unknown")
        addr = info.get("addr", "unknown")
        print(f"➖ Rider {node_id} disconnected ({addr}): {reason}  (remaining: {len(self.clients)})")

    def _print_stats(self):
        now = time.monotonic()
        if now - self.last_stats < STATS_INTERVAL:
            return
        self.last_stats = now
        connected = len(self.clients)
        node_ids = [str(c["node_id"]) for c in self.clients.values() if c["node_id"] is not None]
        print(f"📊 [{time.strftime('%H:%M:%S')}] "
              f"{connected} rider{'s' if connected != 1 else ''} online"
              f"{' — IDs: ' + ', '.join(node_ids) if node_ids else ''}  "
              f"RX: {self.rx_count}  TX: {self.tx_count}")


def main():
    args = parse_args()
    server = RelayServer(args.host, args.port, args.verbose)
    try:
        server.start()
        server.run()
    except KeyboardInterrupt:
        print("\n👋 Shutting down...")
    finally:
        for sock in list(server.clients.keys()):
            try:
                sock.close()
            except OSError:
                pass
        if server.server_sock:
            server.server_sock.close()


if __name__ == "__main__":
    main()