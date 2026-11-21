/* PeerConnect - authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 */
package com.peerconnect.server;

import com.peerconnect.common.LineReader;
import com.peerconnect.common.Presence;
import com.peerconnect.common.Protocol;
import com.peerconnect.common.UserInfo;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Server-side state of ONE TCP connection. Reading is done by the reader thread; ALL writing goes through
 * a bounded outbox drained by exactly ONE writer thread, so frames from different threads can never interleave,
 * and a slow client can never block the thread that is broadcasting to the others.
 */
final class ClientSession {
    private static final String CLOSE_MARKER = new String("<close>");

    final long id;
    final Socket socket;
    final LineReader reader;
    final String ip;
    private final OutputStream out;
    private final LinkedBlockingQueue<String> outbox;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    volatile String username;      // set BEFORE registration in the directory
    volatile int udpPort;
    volatile Presence presence = Presence.ONLINE;
    volatile boolean loggedIn;     // true only after the directory accepted this session
    volatile long lastSeen = System.currentTimeMillis();

    ClientSession(long id, Socket socket, int outboxCapacity) throws IOException {
        this.id = id;
        this.socket = socket;
        this.reader = new LineReader(socket.getInputStream(), Protocol.MAX_FRAME_BYTES);
        this.out = socket.getOutputStream();
        this.ip = socket.getInetAddress().getHostAddress();
        this.outbox = new LinkedBlockingQueue<>(outboxCapacity);
    }

    UserInfo toUserInfo() { return new UserInfo(username, ip, udpPort, presence, lastSeen); }

    /** Queues one frame (a JSON line without '\n'). @return false if the session is closed or too slow (queue full). */
    boolean enqueue(String jsonLine) {
        if (closed.get()) return false;
        return outbox.offer(jsonLine);
    }

    /** Flush everything already queued, then close the socket (graceful). */
    void closeAfterFlush() {
        if (closed.get()) return;
        if (!outbox.offer(CLOSE_MARKER)) close();
    }

    /** Immediate close; unblocks the reader and writer threads. Idempotent. */
    void close() {
        if (closed.compareAndSet(false, true)) {
            try { socket.close(); } catch (IOException ignored) { }
            outbox.offer(CLOSE_MARKER); // wake the writer if it is waiting
        }
    }

    boolean isClosed() { return closed.get(); }

    /** The single writer loop (runs on its own thread). */
    void writerLoop() {
        try {
            while (true) {
                String line = outbox.take();
                if (line == CLOSE_MARKER) break;          // identity comparison on purpose
                if (closed.get()) break;
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.write('\n');
                out.flush();
            }
        } catch (IOException | InterruptedException e) {
            // connection broken or server stopping: fall through to close
        } finally {
            close();
        }
    }
}
