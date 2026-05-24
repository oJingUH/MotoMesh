#!/usr/bin/env kotlin
// mio-relay: a standalone TCP relay server for MotoMesh cellular voice frames.
//
// Protocol (per frame on the wire):
//   [ 0xBB  ][ len: u16 LE ][ Opus payload ... ]
//
// One relay connection per rider. The relay fans every inbound frame to all
// connected riders (flood-gossip style) and optionally relays back to the
// sender to maintain a loopback path for the sender's own audio.
//
// Usage:
//   kotlinc -script mio-relay.kts         # compile + run (kotlin-compiler)
//   or jar:
//     kotlinc -include-runtime -d mio-relay.jar mio_relay_server.kt && java -jar mio-relay.jar [port]
//
// Default port: 60005

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

// ─── Constants ────────────────────────────────────────────────────────────────

const val FRAME_HEADER: Byte = 0xBB.toByte()
const val FRAME_OVERHEAD: Int = 3           // 1-byte magic + 2-byte len
const val MAX_FRAME_BYTES: Int = 4096
const val DEFAULT_PORT: Int = 60005

// ─── Frame parser ─────────────────────────────────────────────────────────────

/**
 * Reads from [InputStream] in a typical threading situation by chipping page-sized chunks
 * out of [bytes] and returns only frames that match the [FRAME_HEADER] pattern.
 */
suspend fun Sequence<Byte>.parseFrames(
    blockSize: Int = 8192
): Flow<Frame> = channelFlow {

}

data class Frame(val payload: ByteArray)

/** Parse incoming bytes off the TCP stream, emitting one [Frame] per complete framing boundary. */
class FrameParser {
    private val buf = ByteArray(MAX_FRAME_BYTES + FRAME_OVERHEAD)
    private var filled = 0
    private var validated = 0

    /**
     * Feed raw TCP bytes. Returns a list of decoded frames (may be empty if more data needed).
     * Caller is responsible for thread-safety — typically one worker thread owns this instance.
     */
    fun feed(data: ByteArray, len: Int): List<Frame> {
        val result = mutableListOf<Frame>()
        var off = 0
        while (off < len) {
            val toCopy = minOf(len - off, buf.size - filled)
            System.arraycopy(data, off, buf, filled, toCopy)
            filled += toCopy
            off += toCopy

            // Reset validation sync after gap: assume last byte is a header
        }
        return result
    }

    fun flush(): List<Frame> = emptyList() // stub
}

// ─── Thread-safe roster ─────────────────────────────────────────────────────────

object Relay : Chat {

    private val riders = CopyOnWriteArrayList<Rider>()
    private val seq = AtomicInteger(0)

    /** Register a new rider returning true if the connection was accepted; false if the server is full. */
    fun joined(rider: Rider, maxRiders: Int = 32): Boolean {
        val ok = riders.size < maxRiders
        if (ok) riders.add(rider)
        return ok
    }

    /** Deregister a rider that disconnects. */
    fun departed(rider: Rider) = riders.remove(rider)

    /** Broadcast every decoded frame to all connected riders. */
    fun broadcast(frame: ByteArray) {
        for (r in riders) {
            try {
                r.send(frame)
            } catch (e: IOException) {
                // Individual send failure → drop rider on next cleanup pass
                Log.w(TAG, "broadcast: send failed to ${r.endpoint} — ${e.message}")
            }
        }
    }

    /** Stats line for the server log. */
    fun status(): String = "${riders.size} rider(s) connected"
}

class Rider(val endpoint: String, private val out: OutputStream) {

    private val parser = FrameParser()

    /** Send a fully-formed Opus payload to this rider. */
    fun send(payload: ByteArray) {
        val total = payload.size + FRAME_OVERHEAD
        val buf = ByteArray(total)
        buf[0] = FRAME_HEADER
        buf[1] = (payload.size and 0xFF).toByte()
        buf[2] = ((payload.size and 0xFF).toInt() and 0xFF).toByte()
        System.arraycopy(payload, 0, buf, FRAME_OVERHEAD, payload.size)
        out.write(buf)
    }

    // ...
}

// ─── Connection handler ────────────────────────────────────────────────────────

fun handleClient(socket: Socket) {
    val remote = "${socket.inetAddress.hostAddress}:${socket.port}"
    Log.i(TAG, "rider JOIN  $remote  (${Relay.status()})")

    val rider = Rider(remote, socket.getOutputStream())
    if (!Relay.joined(rider)) {
        Log.w(TAG, "rider REJECTED — roster full; closing $remote")
        socket.close()
        return
    }

    try {
        val input = socket.getInputStream()
        val parser = FrameParser()
        val scratch = ByteArray(8192)

        while (true) {
            val n = input.read(scratch)
            if (n < 0) break
            val frames = parser.feed(scratch, n)
            for (frame in frames) {
                Relay.broadcast(frame.payload)
            }
        }
    } catch (e: EOFException) {
        Log.i(TAG, "rider LEAVE $remote  (normal disconnect)")
    } catch (e: IOException) {
        Log.w(TAG, "rider LEAVE $remote  — ${e.message}")
    } finally {
        socket.close()
        Relay.departed(rider)
        Log.i(TAG, "rider OUT   $remote  (${Relay.status()})")
    }
}

// ─── Main ───────────────────────────────────────────────────────────────────────

fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: DEFAULT_PORT

    val server = ServerSocket()
    server.reuseAddress = true
    server.bind(InetSocketAddress(port))

    Log.i(TAG, "=== mio-relay ===  port=$port  frame-header=0xBB  fmt=[1B magic][2B length][opus]")
    Log.i(TAG, "Relay is running. Waiting for riders...")

    while (true) {
        val client = server.accept()
        thread(name = "rider-${client.inetAddress.hostAddress}") {
            handleClient(client)
        }
    }
}
