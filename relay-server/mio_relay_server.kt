package org.motomesh.relay

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

/**
 * mio-relay — standalone TCP relay server for MotoMesh cellular voice frames.
 *
 * ONE THREAD PER RIDER connection.  Each reader thread parses frames off its own
 * socket and fans them to every other rider via a thread-safe writer.
 *
 * Frame wire-format (binary, little-endian):
 *   ┌──────────────┬──────────────┬──────────────────┐
 *   │ 0xBB  (1 B)  │  len  (2 B)  │  Opus payload    │
 *   └──────────────┴──────────────┴──────────────────┘
 *
 * Build & run:
 *   kotlinc -include-runtime -d mio-relay.jar mio_relay_server.kt
 *   java  -jar mio-relay.jar [port]          # default port = 60005
 *
 * Emulator note: for Android emulator testing start with:
 *   java -jar mio-relay.jar 60005
 * and use relay host "10.0.2.2" in the MotoMesh app settings.
 */
object MioRelay {

    private const val FRAME_HEADER: Byte = 0xBB.toByte()
    private const val HEADER_SIZE: Int = 3          // 1 B magic + 2 B length
    private const val MAX_PAYLOAD: Int = 4096       // safety cap
    private const val MAX_RIDERS: Int = 32
    private const val DEFAULT_PORT: Int = 60005

    private val log = Logger.getLogger("mio-relay")
    private val riders: MutableList<Rider> = CopyOnWriteArrayList()
    private val frameCounter = AtomicLong(0)

    // ─── Public API ───────────────────────────────────────────────────────────

    fun start(port: Int = DEFAULT_PORT) {
        log.info("=== mio-relay starting on port $port ===")
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(port))
        log.info("Listening on port $port — frame header=0xBB  fmt=[1B][2B LE len][opus payload]")
        log.info("Ctrl-C to stop.")

        while (true) {
            val socket = server.accept()
            val endpoint = "${socket.inetAddress.hostAddress}:${socket.port}"
            log.fine("JOIN  $endpoint  (${status()})")

            if (riders.size >= MAX_RIDERS) {
                log.warning("REJECT $endpoint — roster full ($MAX_RIDERS). Closing.")
                socket.close()
                continue
            }

            val rider = Rider(endpoint, socket)
            riders.add(rider)
            Thread(rider, "rider-$endpoint").start()
        }
    }

    fun status(): String = "${riders.size} rider(s) connected"

    // ─── Frame fan-out ───────────────────────────────────────────────────────

    /** Push a decoded Opus payload to every connected rider. */
    fun broadcast(payload: ByteArray, sender: Rider?) {
        if (payload.isEmpty()) return
        frameCounter.incrementAndGet()
        for (r in riders) {
            if (r === sender) continue   // no echo back to the sender in the relay
            try {
                r.send(payload)
            } catch (e: IOException) {
                log.log(Level.WARNING, "broadcast write failed to ${r.endpoint}: ${e.message}")
                // Rider will be cleaned up when its reader thread terminates
            }
        }
    }

    fun depart(rider: Rider) {
        riders.remove(rider)
        log.fine("OUT   ${rider.endpoint}  (${status()})")
    }

    // ─── Per-rider reader thread ─────────────────────────────────────────────

    private class Rider(
        val endpoint: String,
        socket: Socket
    ) : Runnable {

        private val `in`: InputStream = socket.getInputStream()
        private val out: OutputStream = socket.getOutputStream()
        private val addr = "${socket.inetAddress.hostAddress}:${socket.port}  "

        /**
         * Send one Opus frame to this rider — framing is [0xBB][len LE][payload].
         * Thread-safe individual writes; callers must not share OutputStream across threads
         * without the same lock.
         */
        fun send(payload: ByteArray) {
            if (payload.isEmpty()) return
            synchronized(this) {
                out.write(byteArrayOf(FRAME_HEADER))
                out.write(payload.size and 0xFF)
                out.write((payload.size ushr 8) and 0xFF)
                out.write(payload)
                out.flush()
            }
        }

        override fun run() {
            val header = ByteArray(HEADER_SIZE)
            try {
                while (true) {
                    // 1. Read 3-byte fixed header (blocking)
                    readFully(`in`, header, 0, HEADER_SIZE)
                    if (header[0].toInt() != FRAME_HEADER.toInt()) {
                        // Resync: slide one byte and retry
                        log.fine("$addr out-of-sync byte 0x${(header[0].toInt() and 0xFF).toString(16)} — resyncing")
                        val shifted = ByteArray(HEADER_SIZE - 1) { header[it + 1] }
                        val next = `in`.read()
                        if (next < 0) break
                        shifted[HEADER_SIZE - 2] = next.toByte()
                        readFully(`in`, header, 0, 1)
                        // (simplified: just log and try again next loop)
                        continue
                    }
                    val payloadLen = (header[1].toInt() and 0xFF) or ((header[2].toInt() and 0xFF) shl 8)
                    if (payloadLen <= 0 || payloadLen > MAX_PAYLOAD) {
                        log.warning("$addr bad payload length $payloadLen — resync")
                        continue
                    }

                    // 2. Read payload
                    val payload = ByteArray(payloadLen)
                    readFully(`in`, payload, 0, payloadLen)

                    // 3. Fan out to all other riders
                    broadcast(payload, this)
                }
            } catch (e: EOFException) {
                log.info("$addr disconnected (EOF)")
            } catch (e: IOException) {
                log.log(Level.WARNING, "$addr IO error: ${e.message}", e)
            } finally {
                depart(this@Rider)
                try { `in`.close() } catch (_: IOException) { }
                try { out.close() } catch (_: IOException) { }
            }
        }

        override fun toString(): String = endpoint
    }

    // ─── I/O utility ──────────────────────────────────────────────────────────

    private fun readFully(`in`: InputStream, buf: ByteArray, off: Int, len: Int) {
        var left = len
        var pos = off
        while (left > 0) {
            val n = `in`.read(buf, pos, left)
            if (n < 0) throw EOFException()
            pos += n
            left -= n
        }
    }

    // ─── Entry point ─────────────────────────────────────────────────────────

    @JvmStatic
    fun main(args: Array<String>) {
        val port = args.firstOrNull()?.toIntOrNull() ?: DEFAULT_PORT
        start(port)
    }
}
