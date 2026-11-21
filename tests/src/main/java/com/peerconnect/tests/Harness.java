/* PeerConnect - authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 */
package com.peerconnect.tests;

import com.peerconnect.client.core.ClientConfig;
import com.peerconnect.client.core.MessengerClient;
import com.peerconnect.client.core.MessengerListener;
import com.peerconnect.client.model.ChatMessage;
import com.peerconnect.client.model.MsgStatus;
import com.peerconnect.client.model.TcpState;
import com.peerconnect.common.LineReader;
import com.peerconnect.common.Msg;
import com.peerconnect.common.Protocol;
import com.peerconnect.common.UserInfo;
import com.peerconnect.server.AccountStore;
import com.peerconnect.server.PeerConnectServer;
import com.peerconnect.server.ServerConfig;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Test infrastructure: a REAL server on a loopback socket, REAL MessengerClient instances, and raw-socket clients. */
final class Harness {
    private Harness() {}

    static void check(boolean cond, String msg) { if (!cond) throw new AssertionError(msg); }
    static void eq(Object exp, Object act, String msg) { if (!java.util.Objects.equals(exp, act)) throw new AssertionError(msg + " - expected <" + exp + "> but was <" + act + ">"); }
    static boolean waitFor(BooleanSupplier c, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) { if (c.getAsBoolean()) return true; try { Thread.sleep(25); } catch (InterruptedException e) { return false; } }
        return c.getAsBoolean();
    }
    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    // ------------------------------------------------------------------ environment
    static final class Env implements AutoCloseable {
        final PeerConnectServer server;
        final int port;
        final List<String> serverLog = new CopyOnWriteArrayList<>();
        final List<TC> clients = new CopyOnWriteArrayList<>();
        static Path accountsFile;

        Env(long timeoutMs, int fixedPort) throws IOException {
            ServerConfig c = new ServerConfig();
            c.bindIp = "127.0.0.1"; c.port = fixedPort; c.timeoutMs = timeoutMs;
            server = new PeerConnectServer(c, AccountStore.load(accountsFile), line -> serverLog.add(line));
            server.start();
            port = server.getLocalPort();
        }
        Env() throws IOException { this(15_000, 0); }

        TC client(String user) { TC t = new TC(user, this); clients.add(t); return t; }
        TC loggedIn(String user) {
            TC t = client(user);
            String r = t.login(user + "123");
            check(r.startsWith("OK"), "login of " + user + " failed: " + r);
            return t;
        }
        @Override public void close() {
            for (TC t : clients) t.c.shutdown(1500);
            server.stop();
        }
    }

    // ------------------------------------------------------------------ a headless client = real MessengerClient + recorder
    record StatusEvt(MsgStatus status, String detail, long nanos) {}

    static final class TC implements MessengerListener {
        final String user; final Env env; final MessengerClient c;
        final List<ChatMessage> incoming = new CopyOnWriteArrayList<>();
        final java.util.concurrent.ConcurrentHashMap<String, List<StatusEvt>> statuses = new java.util.concurrent.ConcurrentHashMap<>();
        final BlockingQueue<String> loginResults = new LinkedBlockingQueue<>();
        final List<String> logs = new CopyOnWriteArrayList<>();
        final List<String> announcements = new CopyOnWriteArrayList<>();
        final List<String> tcpStates = new CopyOnWriteArrayList<>();
        volatile List<UserInfo> users = List.of();
        volatile TcpState tcp = TcpState.DISCONNECTED;

        TC(String user, Env env) {
            this.user = user; this.env = env;
            ClientConfig cfg = new ClientConfig();
            cfg.serverHost = "127.0.0.1"; cfg.serverPort = env.port; cfg.pingIntervalMs = 1000; cfg.serverTimeoutMs = 5000;
            this.c = new MessengerClient(cfg, this);
        }
        String login(String password) { return login(password, "0"); }
        String login(String password, String udp) {
            loginResults.clear();
            c.login("127.0.0.1", String.valueOf(env.port), user, password, udp);
            try { String r = loginResults.poll(10, TimeUnit.SECONDS); return r == null ? "TIMEOUT" : r; } catch (InterruptedException e) { return "INTERRUPTED"; }
        }
        Set<String> names() { Set<String> s = new TreeSet<>(); for (UserInfo u : users) s.add(u.username()); return s; }
        List<StatusEvt> evts(String id) { return statuses.getOrDefault(id, List.of()); }
        MsgStatus last(String id) { List<StatusEvt> l = evts(id); return l.isEmpty() ? null : l.get(l.size() - 1).status(); }
        String lastDetail(String id) { List<StatusEvt> l = evts(id); return l.isEmpty() ? "" : l.get(l.size() - 1).detail(); }
        boolean waitStatus(String id, MsgStatus s, long ms) { return waitFor(() -> last(id) == s, ms); }
        boolean waitIncoming(int n, long ms) { return waitFor(() -> incoming.size() >= n, ms); }
        long count(String id, MsgStatus s) { return evts(id).stream().filter(e -> e.status() == s).count(); }

        @Override public void onTcpState(TcpState s, String d) { tcp = s; tcpStates.add(s + ":" + d); }
        @Override public void onUsers(List<UserInfo> u) { users = u; }
        @Override public void onIncoming(ChatMessage m) { incoming.add(m); }
        @Override public void onStatus(String id, MsgStatus s, String d) { statuses.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>()).add(new StatusEvt(s, d, System.nanoTime())); }
        @Override public void onLog(String l) { logs.add(l); }
        @Override public void onAnnouncement(String t) { announcements.add(t); }
        @Override public void onLoginResult(boolean ok, String m, int p) { loginResults.add((ok ? "OK:" : "ERR:") + m); }
    }

    // ------------------------------------------------------------------ raw TCP client (to send hostile / malformed input)
    static final class Raw implements AutoCloseable {
        final Socket s; final LineReader in; final OutputStream out;
        final List<Msg> events = new ArrayList<>();
        Raw(int port) throws IOException {
            s = new Socket("127.0.0.1", port);
            s.setSoTimeout(5000);
            in = new LineReader(s.getInputStream(), 1 << 20);
            out = s.getOutputStream();
        }
        void sendLine(String line) throws IOException { out.write((line + "\n").getBytes(StandardCharsets.UTF_8)); out.flush(); }
        void sendBytes(byte[] b) throws IOException { out.write(b); out.flush(); }
        void send(Msg m) throws IOException { sendLine(m.toJson()); }
        /** Reads frames until a RESPONSE arrives (EVENT frames are collected in 'events'). Returns null on EOF. */
        Msg response() throws IOException {
            while (true) {
                String l = in.readLine();
                if (l == null) return null;
                Msg m;
                try { m = Msg.parse(l); } catch (com.peerconnect.common.Json.JsonException e) { throw new IOException("server sent invalid JSON"); }
                if (Protocol.T_RESPONSE.equals(m.getString("type"))) return m;
                events.add(m);
            }
        }
        Msg login(String user, String pass, int udpPort) throws IOException {
            send(Msg.of().put("cmd", "LOGIN").put("id", "x").put("username", user).put("password", pass).put("udpPort", (long) udpPort));
            return response();
        }
        @Override public void close() { try { s.close(); } catch (IOException ignored) { } }
    }

    static int threadsNamed(String prefix) {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) if (t.isAlive() && t.getName().startsWith(prefix)) n++;
        return n;
    }
}
