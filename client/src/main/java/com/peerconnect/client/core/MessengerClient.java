/* PeerConnect - authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 */
package com.peerconnect.client.core;

import com.peerconnect.client.model.ChatMessage;
import com.peerconnect.client.model.MsgStatus;
import com.peerconnect.client.model.TcpState;
import com.peerconnect.client.model.Transport;
import com.peerconnect.common.Msg;
import com.peerconnect.common.Presence;
import com.peerconnect.common.Protocol;
import com.peerconnect.common.UdpPacket;
import com.peerconnect.common.UserInfo;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Session logic of one PeerConnect client (NO Swing code): login/logout, directory, TCP chat, UDP chat.
 * Every public method returns quickly; blocking work runs on background threads. Results come back via {@link MessengerListener}.
 * Each login creates a brand-new Session (new UDP socket, new TCP link, new message-id prefix); a Session that was torn down
 * never emits events again, so nothing stale survives a Logout/Login.
 */
public final class MessengerClient {
    private final ClientConfig cfg;
    private final MessengerListener ls;
    private final ExecutorService control = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "pc-client-control"); t.setDaemon(true); return t; });
    private final Object lock = new Object();
    private volatile Session session;
    private boolean loggingIn;
    private volatile boolean udpReceiveEnabled = true;
    private static final SecureRandom RNG = new SecureRandom();

    public MessengerClient(ClientConfig cfg, MessengerListener listener) { this.cfg = cfg; this.ls = listener; }

    private final class Session {
        final String id; final String username;
        final AtomicLong counter = new AtomicLong();
        final AtomicBoolean closed = new AtomicBoolean();
        volatile TcpLink tcp; volatile UdpEngine udp; volatile List<UserInfo> users = List.of();
        final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "pc-client-heartbeat"); t.setDaemon(true); return t; });
        final AtomicInteger workIds = new AtomicInteger();
        final ExecutorService work = Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "pc-client-work-" + workIds.incrementAndGet()); t.setDaemon(true); return t; });
        Session(String username) {
            this.username = username;
            byte[] b = new byte[4]; RNG.nextBytes(b);
            StringBuilder sb = new StringBuilder("s");
            for (byte x : b) sb.append(String.format("%02x", x));
            this.id = sb.toString();                       // unique per client session => ids are "s1a2b3c4d-<n>"
        }
    }

    // ------------------------------------------------------------------ login / logout

    /** Validates the inputs synchronously (IllegalArgumentException with a friendly message), then connects in the background. */
    public void login(String host, String portText, String username, String password, String udpPortText) {
        final String h = Protocol.validateHost(host);
        final int port = Protocol.parsePort(portText, "Server port", false);
        final int udp = Protocol.parsePort(udpPortText == null || udpPortText.isBlank() ? "0" : udpPortText, "UDP port", true);
        if (!Protocol.isValidUsername(username)) throw new IllegalArgumentException("Username must be 2-20 letters, digits, '.', '_' or '-'");
        if (password == null || password.isEmpty()) throw new IllegalArgumentException("Password is empty");
        control.execute(() -> doLogin(h, port, username, password, udp));
    }

    private void doLogin(String host, int port, String user, String pass, int udpWanted) {
        synchronized (lock) {
            if (session != null || loggingIn) { ls.onLoginResult(false, "Already logged in (or login in progress)", -1); return; }
            loggingIn = true;
        }
        Session s = new Session(user);
        ls.onTcpState(TcpState.CONNECTING, "Connecting to " + host + ":" + port + " ...");
        try {
            // 1) bind the UDP socket FIRST so the real port can be registered with the server in LOGIN
            s.udp = UdpEngine.open(user, udpWanted, cfg.udpPortBase, udpCallbacks(s));
            s.udp.setReceiveEnabled(udpReceiveEnabled);
            ls.onUdpState("Bound to UDP port " + s.udp.localPort() + " (not registered yet)", s.udp.localPort());
            // 2) TCP connect + LOGIN (registers username + advertised UDP port)
            s.tcp = new TcpLink(host, port, cfg.connectTimeoutMs, cfg.serverTimeoutMs, tcpHandler(s));
            s.tcp.connect();
            Msg resp = s.tcp.request(Msg.of().put("cmd", Protocol.CMD_LOGIN).put("username", user).put("password", pass)
                    .put("udpPort", (long) s.udp.localPort())).get(cfg.requestTimeoutMs + 3000L, TimeUnit.MILLISECONDS);
            if (!resp.getBool("ok")) throw new LoginRejected(resp.getString("error"), resp.getString("message"));
            s.users = UserInfo.parseList(resp.getList("users"));
            synchronized (lock) { session = s; loggingIn = false; }
            // 3) only now start receiving UDP and the heartbeat (once per session)
            s.udp.start();
            s.heartbeat.scheduleWithFixedDelay(() -> s.tcp.request(Msg.of().put("cmd", Protocol.CMD_PING)), cfg.pingIntervalMs, cfg.pingIntervalMs, TimeUnit.MILLISECONDS);
            ls.onTcpState(TcpState.CONNECTED, "Connected as " + user);
            ls.onUdpState(s.udp.describe(), s.udp.localPort());
            ls.onUsers(s.users);
            ls.onLog(Protocol.now() + "  Logged in as " + user + " (UDP port " + s.udp.localPort() + ")");
            ls.onLoginResult(true, "Logged in as " + user, s.udp.localPort());
        } catch (Throwable t) {
            synchronized (lock) { loggingIn = false; }
            teardown(s, "login failed");
            String msg = friendly(t);
            ls.onTcpState(TcpState.DISCONNECTED, msg);
            ls.onUdpState("Closed", -1);
            ls.onLog(Protocol.now() + "  Login failed: " + msg);
            ls.onLoginResult(false, msg, -1);
        }
    }

    private static final class LoginRejected extends Exception {
        final String code;
        LoginRejected(String code, String message) { super(message == null ? "Login rejected" : message); this.code = code; }
    }

    private static String friendly(Throwable t) {
        if (t instanceof ExecutionException && t.getCause() != null) t = t.getCause();
        if (t instanceof LoginRejected) {
            String c = ((LoginRejected) t).code;
            if (Protocol.E_BAD_CREDENTIALS.equals(c)) return "Wrong username or password";
            if (Protocol.E_DUPLICATE_LOGIN.equals(c)) return "This user is already logged in on another client";
            return t.getMessage();
        }
        if (t instanceof ConnectException) return "Cannot connect to the server (is it running? check IP and port)";
        if (t instanceof UnknownHostException) return "Unknown server host: " + t.getMessage();
        if (t instanceof SocketTimeoutException || t instanceof TimeoutException) return "Server did not answer (timeout)";
        if (t instanceof IOException) return t.getMessage() == null ? "Network error" : t.getMessage();
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }

    public void logout() {
        control.execute(() -> {
            Session s = session;
            if (s == null) return;
            try { s.tcp.request(Msg.of().put("cmd", Protocol.CMD_LOGOUT)).get(1500, TimeUnit.MILLISECONDS); } catch (Exception ignored) { }
            teardown(s, "logged out");
            ls.onTcpState(TcpState.DISCONNECTED, "Logged out");
            ls.onUdpState("Closed", -1);
            ls.onUsers(List.of());
            ls.onLog(Protocol.now() + "  Logged out. UDP socket closed, pending retries cancelled.");
        });
    }

    /** Idempotent: releases the UDP socket, TCP link, timers and worker threads of one session. */
    private void teardown(Session s, String reason) {
        if (!s.closed.compareAndSet(false, true)) return;
        s.heartbeat.shutdownNow();
        UdpEngine u = s.udp; if (u != null) u.close(reason);
        TcpLink t = s.tcp; if (t != null) t.close();
        s.work.shutdownNow();
        synchronized (lock) { if (session == s) session = null; }
    }

    /** Stops everything (used when the window is closed). Blocks at most timeoutMs: call from a background thread. */
    public void shutdown(long timeoutMs) {
        Session s = session;
        if (s != null) {
            try { s.tcp.request(Msg.of().put("cmd", Protocol.CMD_LOGOUT)).get(Math.min(timeoutMs, 1000), TimeUnit.MILLISECONDS); } catch (Exception ignored) { }
            teardown(s, "application closing");
        }
        control.shutdownNow();
        try { control.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ------------------------------------------------------------------ TCP events

    private TcpLink.Handler tcpHandler(Session s) {
        return new TcpLink.Handler() {
            @Override public void onEvent(Msg ev) {
                if (s.closed.get()) return;
                String name = ev.getString("event");
                if (name == null) return;
                switch (name) {
                    case Protocol.EV_USERS_UPDATE:
                        s.users = UserInfo.parseList(ev.getList("users"));
                        if (session == s) ls.onUsers(s.users);
                        break;
                    case Protocol.EV_CHAT_TCP: {
                        String from = ev.getString("from"), text = ev.getString("text"), mid = ev.getString("messageId");
                        if (!Protocol.isValidUsername(from) || !Protocol.isValidMessageId(mid)) return;
                        String err = Protocol.validateText(text);                       // validate on RECEIVE too
                        if (err != null) { ls.onLog(Protocol.now() + "  Rejected TCP message from " + from + ": " + err); return; }
                        ls.onIncoming(new ChatMessage(mid, from, from, s.username, text, System.currentTimeMillis(), Transport.TCP, false,
                                MsgStatus.RECEIVED, "Received via server"));
                        break;
                    }
                    case Protocol.EV_ANNOUNCEMENT: {
                        String text = ev.getString("text");
                        if (text != null) ls.onAnnouncement(text);
                        break;
                    }
                    case Protocol.EV_SERVER_SHUTDOWN:
                        ls.onLog(Protocol.now() + "  Server announced shutdown");
                        break;
                    default:
                }
            }
            @Override public void onClosed(String reason) { connectionLost(s, reason); }
        };
    }

    private void connectionLost(Session s, String reason) {
        if (s.closed.get()) return;
        boolean wasActive = (session == s);
        teardown(s, "connection lost");
        if (!wasActive) return;                         // login was still in progress: doLogin reports it
        ls.onTcpState(TcpState.DISCONNECTED, reason);
        ls.onUdpState("Closed", -1);
        ls.onUsers(List.of());
        ls.onLog(Protocol.now() + "  Disconnected from server: " + reason);
    }

    // ------------------------------------------------------------------ UDP events

    private UdpEngine.Callbacks udpCallbacks(Session s) {
        return new UdpEngine.Callbacks() {
            @Override public void onIncoming(UdpPacket p, InetSocketAddress from) {
                if (s.closed.get()) return;
                ls.onIncoming(new ChatMessage(p.messageId(), p.sender(), p.sender(), s.username, p.payload(), System.currentTimeMillis(),
                        Transport.UDP, false, MsgStatus.RECEIVED, "Direct from " + from.getAddress().getHostAddress() + ":" + from.getPort()));
            }
            @Override public void onStatus(String id, MsgStatus st, String detail) { ls.onStatus(id, st, detail); }
            @Override public void onLog(String line) { ls.onLog(line); }
        };
    }

    // ------------------------------------------------------------------ directory / presence

    public void refreshUsers() {
        Session s = session;
        if (s == null) return;
        s.work.execute(() -> {
            try {
                Msg r = s.tcp.request(Msg.of().put("cmd", Protocol.CMD_USERS)).get(cfg.requestTimeoutMs, TimeUnit.MILLISECONDS);
                if (r.getBool("ok")) { s.users = UserInfo.parseList(r.getList("users")); ls.onUsers(s.users); }
            } catch (Exception e) { ls.onLog(Protocol.now() + "  Refresh failed: " + friendly(e)); }
        });
    }

    public void setPresence(Presence p) {
        Session s = session;
        if (s == null) return;
        s.work.execute(() -> {
            try {
                Msg r = s.tcp.request(Msg.of().put("cmd", Protocol.CMD_PRESENCE).put("status", p.label())).get(cfg.requestTimeoutMs, TimeUnit.MILLISECONDS);
                ls.onLog(Protocol.now() + "  Presence " + (r.getBool("ok") ? "set to " + p.label() : "change rejected: " + r.getString("message")));
            } catch (Exception e) { ls.onLog(Protocol.now() + "  Presence change failed: " + friendly(e)); }
        });
    }

    public List<UserInfo> users() { Session s = session; return s == null ? List.of() : s.users; }
    public boolean isConnected() { return session != null; }
    public String username() { Session s = session; return s == null ? null : s.username; }
    public int localUdpPort() { Session s = session; return (s == null || s.udp == null) ? -1 : s.udp.localPort(); }

    // ------------------------------------------------------------------ sending

    /**
     * Creates the outgoing message (status PENDING) and sends it in the background.
     * @throws IllegalStateException when not connected;  IllegalArgumentException for empty / too long / invalid recipient.
     */
    public ChatMessage send(String peer, String text, Transport transport) {
        Session s = session;
        if (s == null) throw new IllegalStateException("Not connected to the server");
        if (!Protocol.isValidUsername(peer)) throw new IllegalArgumentException("No valid recipient selected");
        if (peer.equals(s.username)) throw new IllegalArgumentException("You cannot send a message to yourself");
        String err = Protocol.validateText(text);
        if (err != null) throw new IllegalArgumentException(err);
        ChatMessage m = new ChatMessage(s.id + "-" + s.counter.incrementAndGet(), peer, s.username, peer, text, System.currentTimeMillis(),
                transport, true, MsgStatus.PENDING, "Queued");
        try {
            s.work.execute(() -> { if (transport == Transport.UDP) sendUdp(s, m); else sendTcp(s, m); });
        } catch (RejectedExecutionException e) {
            throw new IllegalStateException("Not connected to the server");
        }
        return m;
    }

    private void sendTcp(Session s, ChatMessage m) {
        try {
            Msg r = s.tcp.request(Msg.of().put("cmd", Protocol.CMD_CHAT_TCP).put("to", m.peer).put("text", m.text).put("messageId", m.id))
                    .get(cfg.requestTimeoutMs, TimeUnit.MILLISECONDS);
            if (s.closed.get()) return;
            if (r.getBool("ok")) ls.onStatus(m.id, MsgStatus.SENT, "Relayed by the server to " + m.peer + " (this is NOT a read receipt)");
            else ls.onStatus(m.id, MsgStatus.FAILED, errorText(r, m.peer));
        } catch (TimeoutException e) {
            ls.onStatus(m.id, MsgStatus.TIMEOUT, "Timeout: the server did not answer within " + (cfg.requestTimeoutMs / 1000) + " s");
        } catch (Exception e) {
            if (!s.closed.get()) ls.onStatus(m.id, MsgStatus.FAILED, "Failed: " + friendly(e));
        }
    }

    private void sendUdp(Session s, ChatMessage m) {
        UserInfo peer;
        try {
            // peer discovery: ask the server for the CURRENT endpoint (never reuse an old one)
            Msg r = s.tcp.request(Msg.of().put("cmd", Protocol.CMD_PEER).put("username", m.peer)).get(cfg.requestTimeoutMs, TimeUnit.MILLISECONDS);
            if (s.closed.get()) return;
            if (!r.getBool("ok")) { ls.onStatus(m.id, MsgStatus.FAILED, errorText(r, m.peer)); return; }
            List<UserInfo> l = UserInfo.parseList(java.util.List.of(r.asMap().get("peer")));
            if (l.isEmpty()) { ls.onStatus(m.id, MsgStatus.FAILED, "Failed: server returned an invalid peer record"); return; }
            peer = l.get(0);
        } catch (TimeoutException e) {
            ls.onStatus(m.id, MsgStatus.TIMEOUT, "Timeout: peer lookup got no answer from the server");
            return;
        } catch (Exception e) {
            if (!s.closed.get()) ls.onStatus(m.id, MsgStatus.FAILED, "Failed: " + friendly(e));
            return;
        }
        try {
            byte[] data = new UdpPacket(UdpPacket.CHAT, m.id, s.username, m.peer, m.timestamp, m.text).encode();
            if (data.length >= Protocol.MAX_DATAGRAM_BYTES) { ls.onStatus(m.id, MsgStatus.FAILED, "Failed: message too large for one datagram"); return; }
            InetAddress ip = InetAddress.getByName(peer.ip());
            s.udp.sendReliable(m.id, m.peer, ip, peer.udpPort(), data);
        } catch (Exception e) {
            ls.onStatus(m.id, MsgStatus.FAILED, "Failed: " + friendly(e));
        }
    }

    private static String errorText(Msg r, String peer) {
        String code = r.getString("error");
        if (Protocol.E_OFFLINE.equals(code)) return "Failed: user '" + peer + "' is offline";
        String m = r.getString("message");
        return "Failed: " + (m != null ? m : code);
    }

    // ------------------------------------------------------------------ test mode (UDP packet-loss simulation)

    /** TEST MODE: false = drop all incoming UDP datagrams (TCP stays connected). */
    public void setUdpReceiveEnabled(boolean on) {
        udpReceiveEnabled = on;
        Session s = session;
        if (s != null && s.udp != null) {
            s.udp.setReceiveEnabled(on);
            ls.onUdpState(s.udp.describe(), s.udp.localPort());
        }
    }
    public boolean isUdpReceiveEnabled() { return udpReceiveEnabled; }

    /** TEST HOOK (integration tests): skip the next n ACKs. */
    public void dropNextAcks(int n) { Session s = session; if (s != null) s.udp.dropNextAcks(n); }
    public long udpDroppedWhilePaused() { Session s = session; return s == null ? 0 : s.udp.droppedWhilePaused(); }
    public long udpDatagramsSent() { Session s = session; return s == null ? 0 : s.udp.datagramsSent(); }
    public long udpChatsDisplayed() { Session s = session; return s == null ? 0 : s.udp.chatsDisplayed(); }
}
