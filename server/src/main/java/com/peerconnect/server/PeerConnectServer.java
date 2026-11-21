/* PeerConnect - authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 */
package com.peerconnect.server;

import com.peerconnect.common.Json;
import com.peerconnect.common.LineReader;
import com.peerconnect.common.Msg;
import com.peerconnect.common.Presence;
import com.peerconnect.common.Protocol;
import com.peerconnect.common.UserInfo;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PeerConnect TCP control-plane + relay server.
 * - accepts TCP clients (one reader thread + one writer thread per client, from a cached pool)
 * - authenticates (PBKDF2), keeps a thread-safe directory, pushes USERS_UPDATE events
 * - relays CHAT_TCP messages to the addressed user ONLY
 * - NEVER sees UDP chat payloads: it has no UDP socket at all; it only hands out IP + advertised UDP port.
 * start() binds synchronously and throws on failure; stop() releases everything so start() can be called again.
 */
public final class PeerConnectServer {
    private final ServerConfig cfg;
    private final AccountStore accounts;
    private final ServerListener listener;
    private final UserDirectory dir = new UserDirectory();
    private final Set<ClientSession> connections = ConcurrentHashMap.newKeySet();
    private final AtomicLong sessionIds = new AtomicLong();
    private final AtomicLong relayedCount = new AtomicLong();

    private volatile boolean running;
    private ServerSocket serverSocket;
    private ExecutorService pool;
    private ScheduledExecutorService scheduler;

    public PeerConnectServer(ServerConfig cfg, AccountStore accounts, ServerListener listener) {
        this.cfg = cfg;
        this.accounts = accounts;
        this.listener = listener;
    }

    // ------------------------------------------------------------------ lifecycle

    public synchronized void start() throws IOException {
        if (running) throw new IllegalStateException("Server already running");
        ServerSocket ss = new ServerSocket();
        try {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(cfg.bindIp, cfg.port), 100); // may throw BindException / UnknownHost
        } catch (IOException | RuntimeException e) {
            try { ss.close(); } catch (IOException ignored) { }
            throw e;
        }
        serverSocket = ss;
        pool = Executors.newCachedThreadPool(new Names("pc-server"));
        scheduler = Executors.newSingleThreadScheduledExecutor(new Names("pc-server-sched"));
        running = true;
        long period = Math.max(200, Math.min(1000, cfg.timeoutMs / 4));
        scheduler.scheduleWithFixedDelay(this::sweepStale, period, period, TimeUnit.MILLISECONDS);
        final ServerSocket accepting = ss;
        pool.execute(() -> acceptLoop(accepting));
        log("Server listening on " + ss.getInetAddress().getHostAddress() + ":" + ss.getLocalPort()
                + " (UDP P2P messages are NOT relayed by this server)");
        listener.onStateChanged(true);
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        try { serverSocket.close(); } catch (IOException ignored) { }
        String bye = Msg.of().put("type", Protocol.T_EVENT).put("event", Protocol.EV_SERVER_SHUTDOWN)
                .put("text", "Server is shutting down").toJson();
        for (ClientSession s : connections) { s.enqueue(bye); s.closeAfterFlush(); }
        scheduler.shutdownNow();
        pool.shutdown();
        try {
            if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
                for (ClientSession s : connections) s.close();
                pool.shutdownNow();
                pool.awaitTermination(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
        dir.clear();
        connections.clear();
        log("Server stopped. All sockets, threads and sessions were released.");
        listener.onDirectoryChanged();
        listener.onStateChanged(false);
    }

    public boolean isRunning() { return running; }
    public int getLocalPort() { return serverSocket == null ? -1 : serverSocket.getLocalPort(); }
    public int getConnectedCount() { return dir.size(); }
    public List<UserInfo> snapshot() { return dir.snapshot(); }
    /** Number of CHAT_TCP messages forwarded so far (UDP chats never increase this). */
    public long getRelayedCount() { return relayedCount.get(); }

    /** Server admin announcement to every logged-in client. */
    public int broadcastAnnouncement(String text) {
        String err = Protocol.validateText(text);
        if (err != null) throw new IllegalArgumentException(err);
        String frame = Msg.of().put("type", Protocol.T_EVENT).put("event", Protocol.EV_ANNOUNCEMENT)
                .put("text", text).put("timestamp", System.currentTimeMillis()).toJson();
        int n = 0;
        for (ClientSession s : dir.sessions()) if (deliver(s, frame)) n++;
        log("[ANNOUNCEMENT] sent to " + n + " client(s)");
        return n;
    }

    // ------------------------------------------------------------------ accept + sessions

    private void acceptLoop(ServerSocket ss) {
        while (running) {
            try {
                Socket sock = ss.accept();
                sock.setTcpNoDelay(true);
                sock.setKeepAlive(true);
                if (connections.size() >= cfg.maxConnections) { sock.close(); log("[WARN] connection refused: too many connections"); continue; }
                ClientSession cs = new ClientSession(sessionIds.incrementAndGet(), sock, cfg.outboxCapacity);
                connections.add(cs);
                pool.execute(cs::writerLoop);
                pool.execute(() -> serve(cs));
            } catch (SocketException e) {
                if (!running || ss.isClosed()) return;       // normal stop
                log("[WARN] accept error: " + e.getMessage());
            } catch (java.util.concurrent.RejectedExecutionException e) {
                return;                                      // pool already shut down
            } catch (Throwable t) {                          // keep accepting after ANY client-related failure
                log("[WARN] accept loop recovered from " + t.getClass().getSimpleName());
            }
        }
    }

    private void serve(ClientSession s) {
        String reason = "connection closed";
        try {
            while (!s.isClosed()) {
                String line;
                try {
                    line = s.reader.readLine();
                } catch (LineReader.FrameException fe) {
                    s.lastSeen = System.currentTimeMillis();
                    sendError(s, null, null, fe.code, fe.getMessage());
                    continue;
                }
                if (line == null) { reason = "client closed the connection (EOF)"; break; }
                s.lastSeen = System.currentTimeMillis();
                if (line.isEmpty()) continue;
                if (!handle(s, line)) { reason = "logout"; break; }
            }
        } catch (IOException e) {
            reason = "connection error (" + e.getClass().getSimpleName() + ")";
        } catch (Throwable t) {
            reason = "internal error (" + t.getClass().getSimpleName() + ")";
            log("[ERROR] session handler: " + t.getClass().getSimpleName());
        } finally {
            cleanup(s, reason);
        }
    }

    /** Removes the session from the directory (only if it is still THIS session) and informs everyone. */
    private void cleanup(ClientSession s, String reason) {
        boolean wasRegistered = s.loggedIn && dir.remove(s);
        s.closeAfterFlush();
        connections.remove(s);
        if (wasRegistered && running) {
            log("[LOGOUT] " + s.username + " removed (" + reason + ")");
            broadcastUsers();
            listener.onDirectoryChanged();
        }
    }

    private void sweepStale() {
        long now = System.currentTimeMillis();
        for (ClientSession s : connections) {
            if (!s.isClosed() && now - s.lastSeen > cfg.timeoutMs) {
                log("[TIMEOUT] stale session " + (s.loggedIn ? s.username : "(not logged in) " + s.ip) + " closed");
                s.close();
            }
        }
    }

    // ------------------------------------------------------------------ request handling

    /** @return false when the session must end (LOGOUT). */
    private boolean handle(ClientSession s, String line) {
        Msg req;
        try {
            req = Msg.parse(line);
        } catch (Json.JsonException e) {
            sendError(s, null, null, Protocol.E_BAD_REQUEST, "Malformed JSON frame");
            return true;
        }
        String id = req.getString("id");
        if (id != null && id.length() > Protocol.MAX_ID_LEN) id = null;
        String cmd = req.getString("cmd");
        if (cmd == null) { sendError(s, id, null, Protocol.E_BAD_REQUEST, "Missing 'cmd'"); return true; }

        switch (cmd) {
            case Protocol.CMD_PING:
                send(s, response(id, cmd).put("pong", true).put("serverTime", System.currentTimeMillis()));
                return true;
            case Protocol.CMD_LOGIN:
                login(s, id, req);
                return true;
            default:
        }
        if (!s.loggedIn) {
            if (isKnown(cmd)) sendError(s, id, cmd, Protocol.E_NOT_LOGGED_IN, "Please LOGIN first");
            else sendError(s, id, cmd, Protocol.E_UNKNOWN_COMMAND, "Unknown command");
            return true;
        }
        switch (cmd) {
            case Protocol.CMD_LOGOUT:
                send(s, response(id, cmd));
                log("[LOGOUT] " + s.username + " logged out");
                return false;
            case Protocol.CMD_USERS:
                send(s, response(id, cmd).put("users", UserInfo.toMsgs(dir.snapshot())));
                return true;
            case Protocol.CMD_PRESENCE: {
                Presence p = Presence.parse(req.getString("status"));
                if (p == null) { sendError(s, id, cmd, Protocol.E_BAD_REQUEST, "status must be Online, Busy or Away"); return true; }
                s.presence = p;
                send(s, response(id, cmd).put("status", p.label()));
                log("[PRESENCE] " + s.username + " -> " + p.label());
                broadcastUsers();
                listener.onDirectoryChanged();
                return true;
            }
            case Protocol.CMD_PEER: {
                String who = req.getString("username");
                ClientSession t = who == null ? null : dir.get(who);
                if (t == null) { sendError(s, id, cmd, Protocol.E_OFFLINE, "User '" + who + "' is not online"); return true; }
                send(s, response(id, cmd).put("peer", t.toUserInfo().toMsg()));
                return true;
            }
            case Protocol.CMD_CHAT_TCP:
                relay(s, id, req);
                return true;
            default:
                sendError(s, id, cmd, Protocol.E_UNKNOWN_COMMAND, "Unknown command");
                return true;
        }
    }

    private static boolean isKnown(String cmd) {
        return cmd.equals(Protocol.CMD_LOGOUT) || cmd.equals(Protocol.CMD_USERS) || cmd.equals(Protocol.CMD_PRESENCE)
                || cmd.equals(Protocol.CMD_PEER) || cmd.equals(Protocol.CMD_CHAT_TCP);
    }

    private void login(ClientSession s, String id, Msg req) {
        if (s.loggedIn) { // a second LOGIN on the same connection must not touch any session
            sendError(s, id, Protocol.CMD_LOGIN, Protocol.E_ALREADY_LOGGED_IN, "This connection is already logged in as " + s.username);
            return;
        }
        String user = req.getString("username"), pass = req.getString("password");
        long port = req.getLong("udpPort", -1);
        if (!Protocol.isValidUsername(user) || pass == null || pass.isEmpty() || pass.length() > 256) {
            sendError(s, id, Protocol.CMD_LOGIN, Protocol.E_BAD_REQUEST, "username/password missing or invalid");
            return;
        }
        if (port < 1 || port > 65535) { sendError(s, id, Protocol.CMD_LOGIN, Protocol.E_BAD_REQUEST, "udpPort must be 1-65535"); return; }
        if (!accounts.verify(user, pass)) {
            log("[LOGIN] failed for '" + user + "' from " + s.ip + " (wrong credentials)");
            sendError(s, id, Protocol.CMD_LOGIN, Protocol.E_BAD_CREDENTIALS, "Wrong username or password");
            return;
        }
        s.username = user;
        s.udpPort = (int) port;
        s.presence = Presence.ONLINE;
        if (!dir.tryRegister(s)) {          // ATOMIC putIfAbsent: only one of several concurrent logins can win
            log("[LOGIN] duplicate login rejected for '" + user + "' from " + s.ip);
            sendError(s, id, Protocol.CMD_LOGIN, Protocol.E_DUPLICATE_LOGIN, "User '" + user + "' is already logged in");
            return;
        }
        s.loggedIn = true;
        log("[LOGIN] " + user + " authenticated from " + s.ip + ", UDP " + s.udpPort);
        send(s, response(id, Protocol.CMD_LOGIN).put("username", user).put("ip", s.ip).put("udpPort", (long) s.udpPort)
                .put("users", UserInfo.toMsgs(dir.snapshot())));
        broadcastUsers();
        listener.onDirectoryChanged();
    }

    private void relay(ClientSession s, String id, Msg req) {
        String to = req.getString("to"), text = req.getString("text"), mid = req.getString("messageId");
        if (!Protocol.isValidUsername(to)) { sendError(s, id, Protocol.CMD_CHAT_TCP, Protocol.E_BAD_REQUEST, "Invalid recipient"); return; }
        String err = Protocol.validateText(text);
        if (err != null) { sendError(s, id, Protocol.CMD_CHAT_TCP, Protocol.E_INVALID_TEXT, err); return; }
        if (!Protocol.isValidMessageId(mid)) mid = "srv-" + s.id + "-" + System.nanoTime();
        if (to.equals(s.username)) { sendError(s, id, Protocol.CMD_CHAT_TCP, Protocol.E_BAD_REQUEST, "Cannot send a message to yourself"); return; }
        ClientSession target = dir.get(to);
        if (target == null) {
            log("[TCP RELAY] " + s.username + " -> " + to + ": recipient offline");
            sendError(s, id, Protocol.CMD_CHAT_TCP, Protocol.E_OFFLINE, "User '" + to + "' is offline");
            return;
        }
        String frame = Msg.of().put("type", Protocol.T_EVENT).put("event", Protocol.EV_CHAT_TCP).put("messageId", mid)
                .put("from", s.username).put("to", to).put("text", text).put("timestamp", System.currentTimeMillis()).toJson();
        if (!deliver(target, frame)) {
            sendError(s, id, Protocol.CMD_CHAT_TCP, Protocol.E_DELIVERY_FAILED, "Could not hand the message to '" + to + "'");
            return;
        }
        relayedCount.incrementAndGet();
        log("[TCP RELAY] " + s.username + " -> " + to + ": message relayed (" + Protocol.charCount(text) + " chars)");
        // RELAYED = the server wrote the message to the recipient's connection. It does NOT mean the user has read it.
        send(s, response(id, Protocol.CMD_CHAT_TCP).put("status", "RELAYED").put("messageId", mid));
    }

    // ------------------------------------------------------------------ sending helpers

    private Msg response(String id, String cmd) {
        return Msg.of().put("type", Protocol.T_RESPONSE).put("id", id).put("cmd", cmd).put("ok", true);
    }

    private void send(ClientSession s, Msg m) { deliver(s, m.toJson()); }

    private void sendError(ClientSession s, String id, String cmd, String code, String message) {
        send(s, Msg.of().put("type", Protocol.T_RESPONSE).put("id", id).put("cmd", cmd).put("ok", false)
                .put("error", code).put("message", message));
    }

    /** Queue a frame; a client whose queue is full (too slow) is disconnected instead of blocking others. */
    private boolean deliver(ClientSession s, String frame) {
        if (s.enqueue(frame)) return true;
        if (!s.isClosed()) { log("[WARN] client " + (s.username != null ? s.username : s.ip) + " is too slow; closing"); s.close(); }
        return false;
    }

    private void broadcastUsers() {
        String frame = Msg.of().put("type", Protocol.T_EVENT).put("event", Protocol.EV_USERS_UPDATE)
                .put("users", UserInfo.toMsgs(dir.snapshot())).toJson();
        for (ClientSession s : dir.sessions()) deliver(s, frame);
    }

    private void log(String msg) { listener.onLog(Protocol.now() + "  " + msg); }

    private static final class Names implements java.util.concurrent.ThreadFactory {
        private final String prefix;
        private final AtomicInteger n = new AtomicInteger();
        Names(String prefix) { this.prefix = prefix; }
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
