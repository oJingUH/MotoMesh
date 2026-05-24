/*
 * mio-relay — Standalone TCP relay for MotoMesh cellular voice frames.
 *
 * Build:  javac mio_relay_server.java
 * Run:    java MioRelay [port]        (default port = 60005)
 *
 * Frame format: [0xBB (1 B)][len: u16 LE (2 B)][Opus payload]
 * One thread per rider; OUTBOUND frames are synchronized per-OutputStream.
 */
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.*;

public class MioRelay {

    private static final byte   HEADER_MAGIC = (byte) 0xBB;
    private static final int    HDR          = 3;          // 1B magic + 2B length
    private static final int    MAX_PAYLOAD  = 4096;
    private static final int    MAX_RIDERS   = 32;
    private static final int    DEFAULT_PORT = 60005;

    static final Logger log = Logger.getLogger("mio-relay");

    private static final List<Rider> riders = new CopyOnWriteArrayList<>();
    private static final AtomicLong framesFanned = new AtomicLong();

    /* ── main ──────────────────────────────────────────────────────────── */

    public static void start(int port) {
        log.info("=== mio-relay ===  port=" + port
            + "  header=0xBB  fmt=[0xBB][2B LE][opus payload]");
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(port));
            log.info("Listening on :" + port + "  max riders: " + MAX_RIDERS
                + ".  Ctrl-C to stop.");

            while (true) {
                Socket sock = server.accept();
                String ep = sock.getInetAddress().getHostAddress()
                    + ":" + sock.getPort();
                log.fine("JOIN  " + ep + "  (" + status() + ")");

                if (riders.size() >= MAX_RIDERS) {
                    log.warning("REJECT " + ep
                        + "  -- roster full (" + MAX_RIDERS + ")");
                    sock.close();
                    continue;
                }
                Rider r = new Rider(ep, sock);
                riders.add(r);
                new Thread(r, "re-" + ep).start();
            }
        } catch (IOException e) {
            log.log(Level.SEVERE, "Server died", e);
            System.exit(1);
        }
    }

    static String status() { return riders.size() + " rider(s) connected"; }

    /* ── fan-out ───────────────────────────────────────────────────────── */

    static void fan(byte[] payload, Rider sender) {
        if (payload.length == 0 || payload.length > MAX_PAYLOAD) return;
        framesFanned.incrementAndGet();
        for (Rider r : riders) {
            if (r == sender) continue;
            try {
                r.send(payload);
            } catch (IOException e) {
                log.fine("fan: write error " + r.endpoint + "  " + e.getMessage());
            }
        }
    }

    static void depart(Rider r) {
        riders.remove(r);
        log.fine("OUT   " + r.endpoint + "  (" + status() + ")");
    }

    public static void main(String[] args) {
        int port = (args.length > 0) ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        start(port);
    }

    /* ── Rider thread: blocking reader─────────────────────────────────── */

    /**
     * Each Rider owns one blocking read loop.
     * It maintains the last 2 bytes read internally so that if the network
     * splits a 3-byte header across two reads, the boundary is invisible.
     */
    static final class Rider implements Runnable {
        final String endpoint;
        private final InputStream  in;
        private final OutputStream out;

        Rider(String endpoint, Socket sock) throws IOException {
            this.endpoint = endpoint;
            this.in  = sock.getInputStream();
            this.out = sock.getOutputStream();
        }

        /**
         * Write one Opus payload framed for this relay.
         * Synchronized on this Rider so one coroutine/thread never interleaves half-frames.
         */
        void send(byte[] payload) throws IOException {
            byte[] buf = new byte[HDR + payload.length];
            buf[0] = HEADER_MAGIC;
            buf[1] = (byte) ( payload.length        & 0xFF);
            buf[2] = (byte) ((payload.length >>> 8)  & 0xFF);
            System.arraycopy(payload, 0, buf, HDR, payload.length);
            synchronized (this) { out.write(buf); out.flush(); }
        }

        @Override
        public void run() {
            byte[] hdr = new byte[HDR];
            try {
                while (readFullHeader(in, hdr)) {
                    int len = (hdr[1] & 0xFF) | ((hdr[2] & 0xFF) << 8);
                    if (len <= 0 || len > MAX_PAYLOAD) {
                        log.warning(endpoint + " bad len " + len + " -- skip");
                        continue;
                    }
                    byte[] payload = new byte[len];
                    readFully(in, payload, 0, len);
                    fan(payload, this);
                }
            } catch (EOFException e) {
                log.info(endpoint + " EOF");
            } catch (IOException e) {
                log.log(Level.WARNING, endpoint + " error: " + e.getMessage(), e);
            } finally {
                depart(this);
                try { in.close();  } catch (IOException ignored) { }
                try { out.close(); } catch (IOException ignored) { }
            }
        }

        /**
         * Read exactly HDR bytes into [dst]. If the first byte of the freshly-read
         * window is not 0xBB, slide the window one byte at a time (re-reading one
         * byte from the stream each time) until the magic is found or EOF.
         */
        boolean readFullHeader(InputStream in, byte[] dst) throws IOException {
            // Phase 1: read first HDR bytes from stream
            int n = 0;
            while (n < HDR) {
                int got = in.read(dst, n, HDR - n);
                if (got < 0) return false;
                n += got;
            }

            // Phase 2: resync: if header[0] != 0xBB, slide byte-by-byte
            while ((dst[0] & 0xFF) != (HEADER_MAGIC & 0xFF)) {
                dst[0] = dst[1]; dst[1] = dst[2];
                int b = in.read();
                if (b < 0) return false;
                dst[2] = (byte) b;
            }
            return true;
        }

        @Override public String toString() { return endpoint; }
    }

    /* ── I/O helpers ───────────────────────────────────────────────────── */

    static void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int left = len, pos = off;
        while (left > 0) {
            int n = in.read(buf, pos, left);
            if (n < 0) throw new EOFException();
            pos += n; left -= n;
        }
    }
}
