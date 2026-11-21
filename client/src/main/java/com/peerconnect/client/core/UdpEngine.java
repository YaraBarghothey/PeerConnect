/* PeerConnect - authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 */
package com.peerconnect.client.core;

import com.peerconnect.client.model.MsgStatus;
import com.peerconnect.common.DedupCache;
import com.peerconnect.common.Protocol;
import com.peerconnect.common.UdpPacket;
import java.io.IOException;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * UDP peer-to-peer data plane. Owns the ONE DatagramSocket of a login session.
 *  - receiver thread: decodes + validates datagrams, answers CHAT_UDP with ACK_UDP, deduplicates by sender+messageId
 *  - reliability: original + 2 retries, 1 s timeout each, same messageId; ACK cancels the retries
 * Race handling: every pending message has its own monitor; ACK, retry-timer and cancel all take it and check 'done'.
 */
final class UdpEngine {
    interface Callbacks {
        void onIncoming(UdpPacket packet, InetSocketAddress from);
        void onStatus(String messageId, MsgStatus status, String detail);
        void onLog(String line);
    }

    static final int MAX_ATTEMPTS = 3;          // original + two retries
    static final long RETRY_MS = 1000;          // 1 second timeout per attempt

    private static final class Pending {
        final String id, peer; final InetAddress ip; final int port; final byte[] data;
        int attempts; boolean done; ScheduledFuture<?> timer;
        Pending(String id, String peer, InetAddress ip, int port, byte[] data) { this.id = id; this.peer = peer; this.ip = ip; this.port = port; this.data = data; }
    }

    private final String self;
    private final DatagramSocket socket;
    private final Callbacks cb;
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final DedupCache dedup = new DedupCache(4096, 10 * 60_000L);
    private final ScheduledExecutorService sched = Executors.newScheduledThreadPool(1, r -> { Thread t = new Thread(r, "pc-client-udp-retry"); t.setDaemon(true); return t; });
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger acksToDrop = new AtomicInteger();
    private final AtomicLong droppedWhilePaused = new AtomicLong(), datagramsSent = new AtomicLong(), chatsDisplayed = new AtomicLong();
    private volatile boolean receiveEnabled = true;
    private Thread receiver;

    private UdpEngine(String self, DatagramSocket socket, Callbacks cb) { this.self = self; this.socket = socket; this.cb = cb; }

    /** Binds the UDP socket. wantedPort 0 = auto (try base..base+199, then any free port). @throws IOException with a friendly message. */
    static UdpEngine open(String self, int wantedPort, int autoBase, Callbacks cb) throws IOException {
        DatagramSocket s = null;
        if (wantedPort > 0) {
            try { s = bind(wantedPort); }
            catch (BindException e) { throw new IOException("UDP port " + wantedPort + " is already in use. Choose another port (or 0 for automatic)."); }
        } else {
            for (int p = autoBase; p < autoBase + 200 && p <= 65535 && s == null; p++) {
                try { s = bind(p); } catch (BindException e) { /* taken by another client on this PC: try the next one */ }
            }
            if (s == null) s = bind(0);
        }
        return new UdpEngine(self, s, cb);
    }

    private static DatagramSocket bind(int port) throws IOException {
        DatagramSocket s = new DatagramSocket(null);
        try { s.setReuseAddress(false); s.bind(new InetSocketAddress(port)); return s; }
        catch (IOException e) { s.close(); throw e; }
    }

    int localPort() { return socket.getLocalPort(); }

    void start() {
        receiver = new Thread(this::receiveLoop, "pc-client-udp-recv");
        receiver.setDaemon(true);
        receiver.start();
        sched.scheduleWithFixedDelay(() -> dedup.purge(System.currentTimeMillis()), 60, 60, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ test hooks

    /** TEST MODE: while false, incoming datagrams are dropped BEFORE decoding: no ACK, no display (simulated packet loss). */
    void setReceiveEnabled(boolean on) { receiveEnabled = on; cb.onLog(Protocol.now() + "  UDP receive " + (on ? "ENABLED" : "DISABLED (test: incoming datagrams are dropped, no ACK sent)")); }
    boolean isReceiveEnabled() { return receiveEnabled; }
    /** TEST HOOK: do not send the next n ACKs (simulates ACK loss). */
    void dropNextAcks(int n) { acksToDrop.set(n); }
    long droppedWhilePaused() { return droppedWhilePaused.get(); }
    long datagramsSent() { return datagramsSent.get(); }
    long chatsDisplayed() { return chatsDisplayed.get(); }

    String describe() {
        if (closed.get()) return "Closed";
        if (!receiveEnabled) return "PAUSED (test mode: dropping incoming UDP) - port " + localPort();
        return "Listening on UDP port " + localPort();
    }

    // ------------------------------------------------------------------ sending with ACK / retry

    /** Starts the reliable send: attempt 1 now, retries via timer. Status changes are reported through the callbacks. */
    void sendReliable(String messageId, String peer, InetAddress ip, int port, byte[] data) {
        Pending p = new Pending(messageId, peer, ip, port, data);
        pending.put(messageId, p);
        synchronized (p) { attemptLocked(p); }
    }

    private void attemptLocked(Pending p) {          // caller holds p's monitor
        p.attempts++;
        try {
            socket.send(new DatagramPacket(p.data, p.data.length, p.ip, p.port));
            datagramsSent.incrementAndGet();
        } catch (IOException e) {
            finishLocked(p, MsgStatus.FAILED, "UDP send error: " + e.getMessage());
            return;
        }
        cb.onStatus(p.id, MsgStatus.SENT, p.attempts == 1 ? "Sent via UDP, waiting for ACK (attempt 1/" + MAX_ATTEMPTS + ")"
                : "No ACK yet - retransmitted (attempt " + p.attempts + "/" + MAX_ATTEMPTS + "), waiting for ACK");
        try {
            p.timer = sched.schedule(() -> onTimeout(p), RETRY_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            finishLocked(p, MsgStatus.FAILED, "Cancelled: session closed");
        }
    }

    private void onTimeout(Pending p) {
        synchronized (p) {
            if (p.done) return;                      // ACK (or cancel) won the race
            if (p.attempts < MAX_ATTEMPTS) attemptLocked(p);
            else finishLocked(p, MsgStatus.FAILED, "Failed: no ACK after " + MAX_ATTEMPTS + " attempts (timeout) - peer unreachable or not listening");
        }
    }

    private void finishLocked(Pending p, MsgStatus st, String detail) {
        p.done = true;
        pending.remove(p.id, p);
        if (p.timer != null) p.timer.cancel(false);
        cb.onStatus(p.id, st, detail);
    }

    // ------------------------------------------------------------------ receiving

    private void receiveLoop() {
        byte[] buf = new byte[Protocol.MAX_DATAGRAM_BYTES];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        while (!closed.get()) {
            try {
                dp.setLength(buf.length);
                socket.receive(dp);
            } catch (IOException e) {
                if (closed.get() || socket.isClosed()) return;
                cb.onLog(Protocol.now() + "  UDP receive error: " + e.getClass().getSimpleName());
                continue;
            }
            try {
                handle(dp, buf);
            } catch (Throwable t) {                  // a bad datagram must never stop the listener
                cb.onLog(Protocol.now() + "  UDP datagram ignored (" + t.getClass().getSimpleName() + ")");
            }
        }
    }

    private void handle(DatagramPacket dp, byte[] buf) {
        InetSocketAddress from = (InetSocketAddress) dp.getSocketAddress();
        if (!receiveEnabled) {
            droppedWhilePaused.incrementAndGet();
            cb.onLog(Protocol.now() + "  TEST MODE: dropped incoming datagram from " + from.getAddress().getHostAddress() + ":" + from.getPort());
            return;
        }
        if (dp.getLength() >= buf.length) { cb.onLog(Protocol.now() + "  Oversized datagram from " + from + " dropped"); return; }
        UdpPacket pkt;
        try {
            pkt = UdpPacket.decode(buf, 0, dp.getLength());
        } catch (IllegalArgumentException e) {
            cb.onLog(Protocol.now() + "  Malformed datagram from " + from.getAddress().getHostAddress() + ":" + from.getPort() + " dropped (" + e.getMessage() + ")");
            return;
        }
        if (!self.equals(pkt.recipient())) { cb.onLog(Protocol.now() + "  Datagram addressed to '" + pkt.recipient() + "' dropped (I am '" + self + "')"); return; }
        if (UdpPacket.ACK.equals(pkt.type())) handleAck(pkt);
        else handleChat(pkt, from);
    }

    private void handleChat(UdpPacket pkt, InetSocketAddress from) {
        boolean first = dedup.firstSeen(pkt.sender() + "|" + pkt.messageId(), System.currentTimeMillis());
        sendAck(pkt, from);                          // ALWAYS acknowledge, also duplicates (the earlier ACK may have been lost)
        if (first) {
            chatsDisplayed.incrementAndGet();
            cb.onIncoming(pkt, from);
        } else {
            cb.onLog(Protocol.now() + "  Duplicate datagram " + pkt.messageId() + " from " + pkt.sender() + ": ACK re-sent, NOT displayed again");
        }
    }

    private void sendAck(UdpPacket chat, InetSocketAddress to) {
        if (acksToDrop.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) { cb.onLog(Protocol.now() + "  TEST HOOK: ACK for " + chat.messageId() + " intentionally not sent"); return; }
        byte[] data = new UdpPacket(UdpPacket.ACK, chat.messageId(), self, chat.sender(), System.currentTimeMillis(), "").encode();
        try { socket.send(new DatagramPacket(data, data.length, to)); }
        catch (IOException e) { cb.onLog(Protocol.now() + "  Could not send ACK: " + e.getMessage()); }
    }

    private void handleAck(UdpPacket ack) {
        Pending p = pending.get(ack.messageId());
        if (p == null) { cb.onLog(Protocol.now() + "  Late/unknown ACK " + ack.messageId() + " ignored"); return; }
        if (!p.peer.equals(ack.sender())) { cb.onLog(Protocol.now() + "  ACK for " + ack.messageId() + " from unexpected peer '" + ack.sender() + "' ignored"); return; }
        synchronized (p) {
            if (p.done) return;
            finishLocked(p, MsgStatus.ACKNOWLEDGED, "ACK received from " + p.peer + (p.attempts > 1 ? " (after " + p.attempts + " attempts)" : ""));
        }
    }

    // ------------------------------------------------------------------ shutdown

    /** Closes the socket, stops the timers and marks every still-pending message as cancelled (no retries after logout). */
    void close(String reason) {
        if (!closed.compareAndSet(false, true)) return;
        socket.close();                              // unblocks receive()
        sched.shutdownNow();
        for (Pending p : pending.values()) {
            synchronized (p) { if (!p.done) finishLocked(p, MsgStatus.FAILED, "Cancelled: " + reason); }
        }
        pending.clear();
        if (receiver != null && receiver != Thread.currentThread()) {
            try { receiver.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
