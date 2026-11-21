/* PeerConnect - authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 */
package com.peerconnect.tests;

import static com.peerconnect.tests.Harness.*;

import com.peerconnect.client.model.ChatMessage;
import com.peerconnect.client.model.ConversationStore;
import com.peerconnect.client.model.MsgStatus;
import com.peerconnect.client.model.Transport;
import com.peerconnect.common.DedupCache;
import com.peerconnect.common.Msg;
import com.peerconnect.common.PasswordHasher;
import com.peerconnect.common.Presence;
import com.peerconnect.common.Protocol;
import com.peerconnect.common.UdpPacket;
import com.peerconnect.common.UserInfo;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

/** Real socket integration tests. Run: java -cp ... com.peerconnect.tests.IntegrationTests [resultsFile] */
public final class IntegrationTests {
    interface TestBody { void run() throws Exception; }
    record Result(String id, String name, String status, long ms, String detail) {}
    static final List<Result> results = new ArrayList<>();

    static void run(String id, String name, TestBody b) {
        long t0 = System.currentTimeMillis();
        String status = "PASSED", detail = "";
        try { b.run(); }
        catch (Throwable t) { status = "FAILED"; detail = t.getClass().getSimpleName() + ": " + t.getMessage(); }
        long ms = System.currentTimeMillis() - t0;
        results.add(new Result(id, name, status, ms, detail));
        System.out.println(String.format("%-6s %-62s %-7s %6d ms %s", id, name, status, ms, detail));
    }

    public static void main(String[] args) throws Exception {
        Path acc = Paths.get(System.getProperty("peerconnect.accounts", "config/accounts.txt"));
        if (!Files.exists(acc)) acc = Paths.get("../config/accounts.txt");
        Env.accountsFile = acc;
        System.out.println("PeerConnect integration tests - accounts: " + acc.toAbsolutePath() + " - Java " + System.getProperty("java.version") + "\n");

        run("T01", "Valid login (directory, UDP port registered)", IntegrationTests::t01);
        run("T02", "Wrong credentials / unknown user / bad input", IntegrationTests::t02);
        run("T03", "Duplicate login rejected, first session intact", IntegrationTests::t03);
        run("T04", "Concurrent duplicate login (8 threads, exactly 1 wins)", IntegrationTests::t04);
        run("T05", "Three clients + automatic directory updates", IntegrationTests::t05);
        run("T06", "Six simultaneous clients", IntegrationTests::t06);
        run("T07", "UDP direct chat + ACK, server does not relay", IntegrationTests::t07);
        run("T08", "TCP via server: relayed to addressed user only", IntegrationTests::t08);
        run("T09", "Presence change pushed to other clients", IntegrationTests::t09);
        run("T10", "Offline recipient: clear error, no blocking (TCP+UDP)", IntegrationTests::t10);
        run("T11", "Lost UDP: 3 transmissions then Failed", IntegrationTests::t11);
        run("T12", "Lost ACK: retransmission, displayed once", IntegrationTests::t12);
        run("T13", "Duplicate datagrams: displayed once, ACK each time", IntegrationTests::t13);
        run("T14", "Unexpected client termination + stale session timeout", IntegrationTests::t14);
        run("T15", "Server Stop/Start: cleanup, no stuck port/threads", IntegrationTests::t15);
        run("T16", "Arabic / Unicode / multiline / quotes (both transports)", IntegrationTests::t16);
        run("T17", "Empty and oversized messages (send + receive side)", IntegrationTests::t17);
        run("T18", "Malformed TCP frames and UDP datagrams", IntegrationTests::t18);
        run("T20", "Separate histories, unread counters, 200-message cap", IntegrationTests::t20);
        run("T21", "Logout/Login: no stale tasks, endpoints or retries", IntegrationTests::t21);
        run("T23", "Password hashing: no plaintext anywhere", IntegrationTests::t23);
        run("T24", "Dedup cache bounds + TTL; session message IDs unique", IntegrationTests::t24);
        run("T25", "Server announcement broadcast + slow/closed client isolation", IntegrationTests::t25);

        long passed = results.stream().filter(r -> r.status().equals("PASSED")).count();
        System.out.println("\nSUMMARY: " + passed + " passed, " + (results.size() - passed) + " failed, " + results.size() + " executed.");
        if (args.length > 0) {
            StringBuilder sb = new StringBuilder("PeerConnect integration test run - " + new java.util.Date() + " - Java " + System.getProperty("java.version")
                    + " - " + System.getProperty("os.name") + "\n\n");
            for (Result r : results) sb.append(String.format("%-5s %-62s %-7s %6d ms  %s%n", r.id(), r.name(), r.status(), r.ms(), r.detail()));
            sb.append("\nSUMMARY: ").append(passed).append(" passed, ").append(results.size() - passed).append(" failed, ").append(results.size()).append(" executed.\n");
            Files.writeString(Paths.get(args[0]), sb.toString());
        }
        System.exit(passed == results.size() ? 0 : 1);
    }

    // ------------------------------------------------------------------ T01..T06: server, auth, presence

    static void t01() throws Exception {
        try (Env e = new Env()) {
            TC a = e.client("sara");
            String r = a.login("sara123");
            check(r.startsWith("OK"), "login result: " + r);
            check(a.c.isConnected(), "client reports connected");
            check(waitFor(() -> a.names().contains("sara"), 2000), "own entry in directory");
            eq(1, e.server.getConnectedCount(), "server connected count");
            UserInfo u = e.server.snapshot().get(0);
            eq("sara", u.username(), "directory username"); eq("127.0.0.1", u.ip(), "directory ip");
            eq(a.c.localUdpPort(), u.udpPort(), "advertised UDP port equals the real bound port");
            eq(Presence.ONLINE, u.presence(), "presence");
            check(u.lastSeen() > 0, "last-seen set");
        }
    }

    static void t02() throws Exception {
        try (Env e = new Env()) {
            TC a = e.client("sara");
            String r = a.login("WRONGPASSWORD!");
            check(r.equals("ERR:Wrong username or password"), "wrong password -> " + r);
            check(!a.c.isConnected(), "not connected after wrong password");
            check(e.server.getConnectedCount() == 0, "no session registered");
            TC b = e.client("nobody");
            check(b.login("whatever1").equals("ERR:Wrong username or password"), "unknown user gets the same generic error");
            try { e.client("sara").c.login("999.1.1.1", "8888", "sara", "x", "0"); check(false, "bad IP accepted"); } catch (IllegalArgumentException ex) { /* expected */ }
            try { e.client("sara").c.login("127.0.0.1", "70000", "sara", "x", "0"); check(false, "bad port accepted"); } catch (IllegalArgumentException ex) { /* expected */ }
            try { e.client("sara").c.login("127.0.0.1", "abc", "sara", "x", "0"); check(false, "non-numeric port accepted"); } catch (IllegalArgumentException ex) { /* expected */ }
            try { e.client("sara").c.login("127.0.0.1", "0", "sara", "x", "0"); check(false, "port 0 accepted"); } catch (IllegalArgumentException ex) { /* expected */ }
            TC d = e.client("sara");
            d.c.login("127.0.0.1", "1", "sara", "sara123", "0");   // nothing listens on port 1
            String dr = d.loginResults.poll(10, java.util.concurrent.TimeUnit.SECONDS);
            check(dr != null && dr.startsWith("ERR:Cannot connect"), "connection refused is explained: " + dr);
            check(e.serverLog.stream().noneMatch(l -> l.contains("WRONGPASSWORD") || l.contains("sara123")), "no password in server log");
        }
    }

    static void t03() throws Exception {
        try (Env e = new Env()) {
            TC a = e.loggedIn("sara");
            TC a2 = e.client("sara");
            check(a2.login("sara123").equals("ERR:This user is already logged in on another client"), "second login must be rejected");
            check(a.c.isConnected(), "first session still connected");
            eq(1, e.server.getConnectedCount(), "still exactly one session");
            // second LOGIN on the SAME connection must not disturb anything
            try (Raw r = new Raw(e.port)) {
                check(r.login("adam", "adam123", 7001).getBool("ok"), "adam login");
                Msg again = r.login("adam", "adam123", 7002);
                eq(Protocol.E_ALREADY_LOGGED_IN, again.getString("error"), "repeat LOGIN on same connection");
                eq(7001L, (long) e.server.snapshot().stream().filter(u -> u.username().equals("adam")).findFirst().get().udpPort(), "UDP port unchanged");
            }
        }
    }

    static void t04() throws Exception {
        try (Env e = new Env()) {
            final int N = 8;
            CyclicBarrier go = new CyclicBarrier(N);
            AtomicInteger ok = new AtomicInteger(), dup = new AtomicInteger(), other = new AtomicInteger();
            List<Raw> raws = new java.util.concurrent.CopyOnWriteArrayList<>();
            List<Thread> ts = new ArrayList<>();
            for (int i = 0; i < N; i++) {
                final int n = i;
                Thread t = new Thread(() -> {
                    try {
                        Raw r = new Raw(e.port); raws.add(r);
                        go.await();
                        Msg m = r.login("ali", "ali123", 7100 + n);
                        if (m.getBool("ok")) ok.incrementAndGet();
                        else if (Protocol.E_DUPLICATE_LOGIN.equals(m.getString("error"))) dup.incrementAndGet();
                        else other.incrementAndGet();
                    } catch (Exception ex) { other.incrementAndGet(); }
                });
                ts.add(t); t.start();
            }
            for (Thread t : ts) t.join(20000);
            eq(1, ok.get(), "exactly one concurrent login may succeed"); eq(N - 1, dup.get(), "all others get DUPLICATE_LOGIN"); eq(0, other.get(), "no other outcome");
            eq(1, e.server.getConnectedCount(), "directory holds a single session");
            // losing sessions closing must NOT remove the winner
            for (Raw r : raws) r.close();
            sleep(500);
            check(e.server.getConnectedCount() <= 1, "directory consistent after closes");
        }
    }

    static void t05() throws Exception {
        try (Env e = new Env()) {
            TC a = e.loggedIn("sara");
            eq(java.util.Set.of("sara"), a.names(), "A sees itself");
            TC b = e.loggedIn("adam");
            check(waitFor(() -> a.names().equals(java.util.Set.of("sara", "adam")), 3000), "A is updated automatically when B logs in");
            TC c = e.loggedIn("ali");
            check(waitFor(() -> a.names().size() == 3 && b.names().size() == 3 && c.names().size() == 3, 3000), "all three see three users");
            c.c.logout();
            check(waitFor(() -> a.names().size() == 2 && b.names().size() == 2, 3000), "A and B updated after C logout");
            check(!c.c.isConnected(), "C disconnected");
        }
    }

    static void t06() throws Exception {
        try (Env e = new Env()) {
            String[] users = { "sara", "adam", "ali", "aws", "saly", "omar" };
            List<TC> cs = new ArrayList<>();
            for (String u : users) cs.add(e.loggedIn(u));
            for (TC c : cs) check(waitFor(() -> c.names().size() == 6, 4000), c.user + " sees all 6 users: " + c.names());
            eq(6, e.server.getConnectedCount(), "server connected count");
            // everybody talks to everybody via TCP at the same time
            for (TC c : cs) for (TC d : cs) if (c != d) c.c.send(d.user, "hi from " + c.user, Transport.TCP);
            for (TC c : cs) check(c.waitIncoming(5, 6000), c.user + " received 5 messages, got " + c.incoming.size());
        }
    }

    // ------------------------------------------------------------------ T07..T10: messaging

    static void t07() throws Exception {
        try (Env e = new Env()) {
            TC a = e.loggedIn("sara"), b = e.loggedIn("adam");
            ChatMessage m = a.c.send("adam", "Hi Adam, direct UDP", Transport.UDP);
            check(b.waitIncoming(1, 4000), "B received the datagram");
            check(a.waitStatus(m.id, MsgStatus.ACKNOWLEDGED, 4000), "A got the ACK: " + a.evts(m.id));
            ChatMessage in = b.incoming.get(0);
            eq(Transport.UDP, in.transport, "transport"); eq("sara", in.sender, "sender"); eq("Hi Adam, direct UDP", in.text, "text");
            check(in.detail().contains("127.0.0.1:" + a.c.localUdpPort()), "datagram came directly from A's UDP socket: " + in.detail());
            eq(1L, a.count(m.id, MsgStatus.SENT), "exactly one transmission (no retry needed)");
            eq(0L, e.server.getRelayedCount(), "server relayed NOTHING");
            check(e.serverLog.stream().noneMatch(l -> l.contains("direct UDP")), "payload never appears in the server log");
            check(e.serverLog.stream().noneMatch(l -> l.contains("TCP RELAY")), "no relay event at the server");
        }
    }

    static void t08() throws Exception {
        try (Env e = new Env()) {
            TC a = e.loggedIn("sara"), b = e.loggedIn("adam"), c = e.loggedIn("ali");
            ChatMessage m = a.c.send("adam", "via server \"quoted\"", Transport.TCP);
            check(b.waitIncoming(1, 3000), "B received the relayed message");
            check(a.waitStatus(m.id, MsgStatus.SENT, 3000), "A got the server response");
            check(a.lastDetail(m.id).contains("NOT a read receipt"), "UI text clarifies relayed != read: " + a.lastDetail(m.id));
            eq(Transport.TCP, b.incoming.get(0).transport, "transport"); eq("sara", b.incoming.get(0).sender, "sender");
            sleep(1200);
            eq(0, c.incoming.size(), "C (not addressed) received nothing");
            eq(1L, e.server.getRelayedCount(), "server relayed exactly one message");
            eq(0, a.incoming.size(), "sender does not get its own message back");
        }
    }

    static void t09() throws Exception {
        try (Env e = new Env()) {
            TC a = e.loggedIn("sara"), b = e.loggedIn("adam");
            check(waitFor(() -> a.names().contains("adam"), 3000), "A sees B");
            b.c.setPresence(Presence.BUSY);
            check(waitFor(() -> a.users.stream().anyMatch(u -> u.username().equals("adam") && u.presence() == Presence.BUSY), 3000), "A sees Busy");
            b.c.setPresence(Presence.AWAY);
            check(waitFor(() -> a.users.stream().anyMatch(u -> u.username().equals("adam") && u.presence() == Presence.AWAY), 3000), "A sees Away");
            try (Raw r = new Raw(e.port)) {
                r.login("ali", "ali123", 7300);
                r.send(Msg.of().put("cmd", "PRESENCE").put("id", "p").put("status", "Invisible"));
                eq(Protocol.E_BAD_REQUEST, r.response().getString("error"), "invalid presence value rejected");
            }
        }
    }

    static void t10() throws Exception {
        try (Env e = new Env()) {
            TC a = e.loggedIn("sara");
            long t0 = System.nanoTime();
            ChatMessage m1 = a.c.send("omar", "anyone there?", Transport.TCP);
            ChatMessage m2 = a.c.send("omar", "anyone there (udp)?", Transport.UDP);
            check((System.nanoTime() - t0) / 1_000_000 < 300, "send() returns immediately (does not block the caller/GUI)");
            check(a.waitStatus(m1.id, MsgStatus.FAILED, 3000), "TCP to offline user fails"); check(a.lastDetail(m1.id).contains("offline"), "clear text: " + a.lastDetail(m1.id));
            check(a.waitStatus(m2.id, MsgStatus.FAILED, 3000), "UDP to offline user fails"); check(a.lastDetail(m2.id).contains("offline"), "clear text: " + a.lastDetail(m2.id));
            eq(0L, a.count(m2.id, MsgStatus.SENT), "no datagram was sent to an unknown endpoint");
            check(a.c.isConnected(), "client still connected after the errors");
        }
    }

    // ------------------------------------------------------------------ T11..T13: UDP reliability

    static void t11() throws Exception {
        try (Env e = new Env()) {
            TC a = e.loggedIn("sara"), b = e.loggedIn("adam");
            b.c.setUdpReceiveEnabled(false);                          // simulated packet loss, TCP stays connected
            ChatMessage m = a.c.send("adam", "will be lost", Transport.UDP);
            check(a.waitStatus(m.id, MsgStatus.FAILED, 6000), "final state Failed: " + a.evts(m.id));
            List<StatusEvt> ev = a.evts(m.id);
            eq(3L, a.count(m.id, MsgStatus.SENT), "original + two retries = 3 transmissions");
            long span = (ev.get(ev.size() - 1).nanos() - ev.get(0).nanos()) / 1_000_000;
            check(span >= 2700 && span <= 4200, "Failed shown ~3 s after the first send, was " + span + " ms");
            long gap = (ev.get(1).nanos() - ev.get(0).nanos()) / 1_000_000;
            check(gap >= 900 && gap <= 1500, "retry interval about 1 s, was " + gap + " ms");
            eq(3L, b.c.udpDroppedWhilePaused(), "B really dropped 3 datagrams");
            eq(0, b.incoming.size(), "message NOT considered received while dropped");
            check(a.lastDetail(m.id).startsWith("Failed"), "detail: " + a.lastDetail(m.id));
            check(b.c.isConnected() && a.c.isConnected(), "TCP control connections stayed up");
            // after re-enabling UDP the next message works
            b.c.setUdpReceiveEnabled(true);
            ChatMessage m2 = a.c.send("adam", "now it works", Transport.UDP);
            check(a.waitStatus(m2.id, MsgStatus.ACKNOWLEDGED, 4000), "next message acknowledged");
        }
    }

    static void t12() throws Exception {
        try (Env e = new Env()) {
            TC a = e.loggedIn("sara"), b = e.loggedIn("adam");
            b.c.dropNextAcks(2);                                      // the first two ACKs are lost
            ChatMessage m = a.c.send("adam", "ack will be lost twice", Transport.UDP);
            check(a.waitStatus(m.id, MsgStatus.ACKNOWLEDGED, 6000), "third attempt finally acknowledged: " + a.evts(m.id));
            eq(3L, a.count(m.id, MsgStatus.SENT), "three transmissions of the SAME message id");
            eq(1, b.incoming.size(), "B displayed the message exactly once");
            eq(1L, b.c.udpChatsDisplayed(), "display counter");
            check(b.logs.stream().filter(l -> l.contains("Duplicate datagram")).count() == 2, "B logged 2 duplicates, ACKed them, did not redisplay");
            // all ACKs lost: sender shows Failed although the receiver has the message (no guaranteed delivery claimed)
            b.c.dropNextAcks(3);
            ChatMessage m2 = a.c.send("adam", "all acks lost", Transport.UDP);
            check(a.waitStatus(m2.id, MsgStatus.FAILED, 6000), "sender shows Failed");
            eq(2, b.incoming.size(), "receiver displayed it once (only once)");
        }
    }

    static void t13() throws Exception {
        try (Env e = new Env(); DatagramSocket raw = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            TC b = e.loggedIn("adam");
            raw.setSoTimeout(2000);
            byte[] data = new UdpPacket(UdpPacket.CHAT, "dup-test-1", "sara", "adam", System.currentTimeMillis(), "duplicated datagram").encode();
            DatagramPacket p = new DatagramPacket(data, data.length, InetAddress.getByName("127.0.0.1"), b.c.localUdpPort());
            for (int i = 0; i < 3; i++) raw.send(p);
            int acks = 0;
            try { while (acks < 3) { byte[] buf = new byte[2048]; DatagramPacket r = new DatagramPacket(buf, buf.length); raw.receive(r); UdpPacket a = UdpPacket.decode(buf, 0, r.getLength()); if (a.type().equals("ACK_UDP") && a.messageId().equals("dup-test-1") && a.sender().equals("adam")) acks++; } } catch (SocketTimeoutException ex) { /* stop */ }
            eq(3, acks, "ACK sent for every copy (the first ACK may have been lost)");
            check(b.waitIncoming(1, 2000), "displayed once");
            sleep(400);
            eq(1, b.incoming.size(), "displayed exactly once");
            // the same id from a DIFFERENT sender is a different message
            byte[] d2 = new UdpPacket(UdpPacket.CHAT, "dup-test-1", "ali", "adam", System.currentTimeMillis(), "other sender same id").encode();
            raw.send(new DatagramPacket(d2, d2.length, InetAddress.getByName("127.0.0.1"), b.c.localUdpPort()));
            check(b.waitIncoming(2, 2000), "same id from another sender is not a duplicate");
        }
    }

    // ------------------------------------------------------------------ T14..T15: failures and lifecycle

    static void t14() throws Exception {
        try (Env e = new Env(2000, 0)) {
            TC a = e.loggedIn("sara");
            Raw r = new Raw(e.port);
            check(r.login("omar", "omar123", 7400).getBool("ok"), "omar logged in");
            check(waitFor(() -> a.names().contains("omar"), 3000), "A sees omar");
            r.s.setSoLinger(true, 0);                                  // abrupt close: TCP RST, no LOGOUT
            r.close();
            check(waitFor(() -> !a.names().contains("omar"), 4000), "omar removed from the directory and A informed");
            check(e.serverLog.stream().anyMatch(l -> l.contains("omar removed")), "server logged the removal");
            TC b = e.loggedIn("adam");                                  // server still accepts clients
            check(waitFor(() -> a.names().equals(java.util.Set.of("sara", "adam")), 3000), "server keeps running and updating");
            // silent stale session (no PING, no close): removed by the heartbeat timeout (2 s in this test)
            Raw silent = new Raw(e.port);
            check(silent.login("aws", "aws123", 7401).getBool("ok"), "aws logged in");
            check(waitFor(() -> a.names().contains("aws"), 3000), "A sees aws");
            check(waitFor(() -> !a.names().contains("aws"), 6000), "silent session removed by heartbeat timeout");
            check(e.serverLog.stream().anyMatch(l -> l.contains("TIMEOUT") && l.contains("aws")), "timeout logged");
            silent.close();
            check(a.c.isConnected() && b.c.isConnected(), "healthy clients (they send PING) were NOT removed");
        }
    }

    static void t15() throws Exception {
        int port;
        TC a, b;
        Env e1 = new Env();
        port = e1.port;
        a = e1.loggedIn("sara"); b = e1.loggedIn("adam");
        check(waitFor(() -> a.names().size() == 2, 3000), "two clients up");
        check(threadsNamed("pc-server") > 0, "server threads exist while running");
        e1.server.stop();
        check(waitFor(() -> !a.c.isConnected() && !b.c.isConnected(), 4000), "clients noticed the stop");
        check(a.tcpStates.stream().anyMatch(s -> s.startsWith("DISCONNECTED")), "client reported DISCONNECTED");
        check(waitFor(() -> threadsNamed("pc-server") == 0, 4000), "no server threads left: " + threadsNamed("pc-server"));
        eq(0, e1.server.getConnectedCount(), "directory empty");
        try (java.net.ServerSocket probe = new java.net.ServerSocket()) { probe.setReuseAddress(true); probe.bind(new java.net.InetSocketAddress("127.0.0.1", port)); } // port free again
        // start again on the SAME port
        try (Env e2 = new Env(15_000, port)) {
            eq(port, e2.port, "same port reused");
            TC c = e2.loggedIn("sara");
            check(waitFor(() -> c.names().contains("sara"), 3000), "login works after restart");
            TC d = e2.loggedIn("adam");
            ChatMessage m = c.c.send("adam", "after restart", Transport.TCP);
            check(d.waitIncoming(1, 3000), "relay works after restart");
        }
        a.c.shutdown(1000); b.c.shutdown(1000);
        check(waitFor(() -> threadsNamed("pc-server") == 0, 4000), "no leaked server threads after second stop");
    }

    // ------------------------------------------------------------------ T16..T18: content + hostile input

    static void t16() throws Exception {
        try (Env e = new Env()) {
            TC a = e.loggedIn("sara"), b = e.loggedIn("adam");
            String[] texts = {
                "مرحبًا بالعالم! هذه رسالة عربية\nالسطر الثاني مع \"اقتباس\" و \\ شرطة ' مفردة",
                "Emoji \uD83D\uDE00 \uD83C\uDDF5\uD83C\uDDF8 + {\"json\":[1,2],\"x\":\"y\"}\ttab",
                "line1\nline2\r\nline3\n\n\nline6 ending with newline-like \n literal",
                "Mixed: English + العربية + 日本語 + \u2028 separator",
            };
            int expected = 0;
            for (Transport t : new Transport[] { Transport.UDP, Transport.TCP }) {
                for (String s : texts) {
                    ChatMessage m = a.c.send("adam", s, t);
                    expected++;
                    final int n = expected;
                    check(b.waitIncoming(n, 4000), "received #" + n + " via " + t);
                    ChatMessage got = b.incoming.get(n - 1);
                    eq(s, got.text, "text must arrive byte-for-byte identical via " + t);
                    eq(t, got.transport, "transport");
                }
            }
        }
    }

    static void t17() throws Exception {
        try (Env e = new Env(); DatagramSocket raw = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            TC a = e.loggedIn("sara"), b = e.loggedIn("adam");
            for (String bad : new String[] { "", "   ", "\n\t " }) {
                try { a.c.send("adam", bad, Transport.TCP); check(false, "blank accepted"); } catch (IllegalArgumentException ex) { check(ex.getMessage().contains("empty"), "message: " + ex.getMessage()); }
            }
            try { a.c.send("adam", "x".repeat(1001), Transport.UDP); check(false, "1001 chars accepted"); } catch (IllegalArgumentException ex) { check(ex.getMessage().contains("too long"), ex.getMessage()); }
            try { a.c.send("adam", "\uD83D\uDE00".repeat(1001), Transport.UDP); check(false, "1001 emoji accepted"); } catch (IllegalArgumentException ex) { /* counted as code points */ }
            try { a.c.send(null, "hi", Transport.UDP); check(false, "no recipient accepted"); } catch (IllegalArgumentException ex) { /* expected */ }
            // boundary: exactly 1000 characters. Characters are counted, NOT UTF-8 bytes (1000 Arabic letters = 2000 bytes)
            String arabic1000 = "ع".repeat(1000), emoji1000 = "\uD83D\uDE00".repeat(1000), ctrl1000 = "\u0001".repeat(1000);
            int n = 0;
            for (String s : new String[] { arabic1000, emoji1000, ctrl1000 }) for (Transport t : Transport.values()) {
                a.c.send("adam", s, t); n++;
                final int k = n;
                check(b.waitIncoming(k, 5000), "1000-character message delivered via " + t + " (variant " + k + ")");
                eq(s, b.incoming.get(k - 1).text, "intact");
            }
            // server-side validation of oversized TCP text
            try (Raw r = new Raw(e.port)) {
                r.login("ali", "ali123", 7500);
                r.send(Msg.of().put("cmd", "CHAT_TCP").put("id", "c1").put("to", "adam").put("text", "y".repeat(1001)));
                eq(Protocol.E_INVALID_TEXT, r.response().getString("error"), "server rejects 1001 chars");
                r.send(Msg.of().put("cmd", "CHAT_TCP").put("id", "c2").put("to", "adam").put("text", "  "));
                eq(Protocol.E_INVALID_TEXT, r.response().getString("error"), "server rejects blank text");
            }
            // receive-side validation of an oversized UDP datagram (hand-crafted, bypasses the sender's GUI check)
            raw.setSoTimeout(1200);
            String big = "z".repeat(1001);
            byte[] data = Msg.of().put("v", 1L).put("type", "CHAT_UDP").put("messageId", "big-1").put("sender", "sara").put("recipient", "adam")
                    .put("timestamp", 1L).put("payload", big).toJson().getBytes(StandardCharsets.UTF_8);
            raw.send(new DatagramPacket(data, data.length, InetAddress.getByName("127.0.0.1"), b.c.localUdpPort()));
            boolean gotAck = true;
            try { raw.receive(new DatagramPacket(new byte[2048], 2048)); } catch (SocketTimeoutException ex) { gotAck = false; }
            check(!gotAck, "oversized datagram gets no ACK");
            sleep(300);
            eq(n, b.incoming.size(), "oversized datagram not displayed");
        }
    }

    static void t18() throws Exception {
        try (Env e = new Env(); DatagramSocket raw = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            try (Raw r = new Raw(e.port)) {
                r.sendLine("this is not json");                         eq(Protocol.E_BAD_REQUEST, r.response().getString("error"), "garbage");
                r.sendLine("[1,2,3]");                                  eq(Protocol.E_BAD_REQUEST, r.response().getString("error"), "array instead of object");
                r.sendLine("{}");                                       eq(Protocol.E_BAD_REQUEST, r.response().getString("error"), "missing cmd");
                r.sendLine("{\"cmd\":\"FLY\",\"id\":\"q\"}");           eq(Protocol.E_UNKNOWN_COMMAND, r.response().getString("error"), "unknown cmd");
                r.sendLine("{\"cmd\":\"CHAT_TCP\",\"id\":\"q\",\"to\":\"x\",\"text\":\"t\"}"); eq(Protocol.E_NOT_LOGGED_IN, r.response().getString("error"), "chat before login");
                r.sendLine("{\"cmd\":\"LOGIN\",\"id\":\"q\",\"username\":\"sara\"}");           eq(Protocol.E_BAD_REQUEST, r.response().getString("error"), "login without password");
                r.sendLine("{\"cmd\":\"LOGIN\",\"id\":\"q\",\"username\":\"sara\",\"password\":\"sara123\",\"udpPort\":0}"); eq(Protocol.E_BAD_REQUEST, r.response().getString("error"), "login with udpPort 0");
                r.sendLine("{\"cmd\":\"LOGIN\",\"id\":\"q\",\"username\":\"sa ra\",\"password\":\"x\",\"udpPort\":5000}");         eq(Protocol.E_BAD_REQUEST, r.response().getString("error"), "invalid username characters");
                r.sendLine("a".repeat(Protocol.MAX_FRAME_BYTES + 5000));                         eq(Protocol.E_FRAME_TOO_LARGE, r.response().getString("error"), "oversized frame");
                r.sendBytes(new byte[] { '{', (byte) 0xC3, 0x28, '}', '\n' });                  eq(Protocol.E_BAD_ENCODING, r.response().getString("error"), "invalid UTF-8");
                r.sendLine("{\"cmd\":\"PING\",\"id\":\"ok1\"}");
                Msg pong = r.response(); check(pong.getBool("ok") && "ok1".equals(pong.getString("id")), "connection still usable after all the garbage");
                check(r.login("sara", "sara123", 7600).getBool("ok"), "valid login still works on the same connection");
                r.send(Msg.of().put("cmd", "PEER").put("id", "pp").put("username", "nobody"));
                eq(Protocol.E_OFFLINE, r.response().getString("error"), "peer lookup of offline user");
            }
            check(e.server.isRunning(), "server survived");
            // malformed datagrams must not stop the UDP listener
            TC b = e.loggedIn("adam"), a = e.loggedIn("ali");
            InetAddress lo = InetAddress.getByName("127.0.0.1");
            byte[][] junk = { {1, 2, 3, 4}, new byte[0], "{\"v\":1}".getBytes(StandardCharsets.UTF_8), "[]".getBytes(StandardCharsets.UTF_8), {(byte) 0xC3, 0x28},
                new UdpPacket(UdpPacket.CHAT, "wrong-rcpt-1", "sara", "someoneelse", 1, "not for adam").encode(),
                "{\"v\":1,\"type\":\"CHAT_UDP\",\"messageId\":\"bad id!\",\"sender\":\"sara\",\"recipient\":\"adam\",\"timestamp\":1,\"payload\":\"x\"}".getBytes(StandardCharsets.UTF_8),
                "{\"v\":1,\"type\":\"ACK_UDP\",\"messageId\":\"never-sent\",\"sender\":\"sara\",\"recipient\":\"adam\",\"timestamp\":1,\"payload\":\"\"}".getBytes(StandardCharsets.UTF_8) };
            for (byte[] j : junk) raw.send(new DatagramPacket(j, j.length, lo, b.c.localUdpPort()));
            sleep(500);
            eq(0, b.incoming.size(), "no junk displayed");
            ChatMessage m = a.c.send("adam", "still alive", Transport.UDP);
            check(b.waitIncoming(1, 3000), "UDP listener survived all malformed datagrams");
            check(a.waitStatus(m.id, MsgStatus.ACKNOWLEDGED, 3000), "and still ACKs");
            check(b.logs.stream().anyMatch(l -> l.contains("Malformed datagram")), "malformed datagrams were logged");
        }
    }

    // ------------------------------------------------------------------ T20..T25

    static void t20() {
        ConversationStore s = new ConversationStore();
        for (int i = 0; i < 250; i++) s.add(new ChatMessage("m" + i, "adam", "me", "adam", "t" + i, i, Transport.UDP, true, MsgStatus.SENT, ""));
        s.add(new ChatMessage("x1", "ali", "ali", "me", "hello", 1, Transport.TCP, false, MsgStatus.RECEIVED, ""));
        eq(ConversationStore.MAX_PER_CONVERSATION, s.history("adam").size(), "history capped");
        check(ConversationStore.MAX_PER_CONVERSATION >= 50, "cap is at least 50 (assignment minimum)");
        eq("t50", s.history("adam").get(0).text, "oldest messages evicted first (last 200 kept)");
        eq("t249", s.history("adam").get(199).text, "newest kept");
        eq(1, s.history("ali").size(), "histories are separate per contact");
        check(s.find("m0") == null && s.find("m249") != null, "evicted ids removed from index");
        s.incrementUnread("ali"); s.incrementUnread("ali"); s.incrementUnread("adam");
        eq(2, s.unread("ali"), "unread per contact"); eq(1, s.unread("adam"), "unread per contact");
        s.clearUnread("ali");
        eq(0, s.unread("ali"), "cleared on open"); eq(1, s.unread("adam"), "others untouched");
    }

    static void t21() throws Exception {
        try (Env e = new Env()) {
            TC b = e.loggedIn("adam");
            b.c.setUdpReceiveEnabled(false);
            final int base = clientThreads();                          // threads owned by B only
            TC a = e.loggedIn("sara");
            check(clientThreads() > base, "A's session created its own threads");
            ChatMessage m1 = a.c.send("adam", "first session", Transport.UDP);
            check(waitFor(() -> b.c.udpDroppedWhilePaused() >= 1, 3000), "first datagram reached B (and was dropped)");
            a.c.logout();
            check(a.waitStatus(m1.id, MsgStatus.FAILED, 3000), "pending message cancelled by logout"); check(a.lastDetail(m1.id).contains("Cancelled"), a.lastDetail(m1.id));
            check(waitFor(() -> !a.c.isConnected() && clientThreads() == base, 4000),
                    "client session threads released after logout");
            check(waitFor(() -> !b.names().contains("sara"), 3000), "B informed of logout");
            String firstId = m1.id;
            String r = a.login("sara123");
            check(r.startsWith("OK"), "re-login: " + r);
            ChatMessage m2 = a.c.send("adam", "second session", Transport.UDP);
            check(!m2.id.substring(0, m2.id.indexOf('-')).equals(firstId.substring(0, firstId.indexOf('-'))), "new session => new message-id prefix: " + firstId + " vs " + m2.id);
            sleep(3800);
            long dropped = b.c.udpDroppedWhilePaused();
            eq(4L, dropped, "B saw exactly 1 (old, no retries after logout) + 3 (new session) datagrams; old message was never re-sent");
            eq(3L, a.count(m2.id, MsgStatus.SENT), "new message: its own 3 transmissions");
            eq(MsgStatus.FAILED, a.last(m1.id), "old message status untouched by the new session");
            a.c.logout(); a.c.logout();                               // double logout is harmless
            check(waitFor(() -> clientThreads() == base, 3000), "no leaked client threads");
        }
    }

    static int clientThreads() { return threadsNamed("pc-client-udp") + threadsNamed("pc-client-tcp") + threadsNamed("pc-client-work") + threadsNamed("pc-client-heartbeat"); }

    static void t23() throws Exception {
        String file = Files.readString(Env.accountsFile);
        for (String u : new String[] { "sara", "adam", "ali", "aws", "saly", "omar" }) check(!file.contains(u + "123"), "plaintext password of " + u + " found in accounts file");
        check(file.contains("pbkdf2$sha256$120000$"), "PBKDF2 parameters stored with the hash");
        String h1 = PasswordHasher.hash("same-password", 1000), h2 = PasswordHasher.hash("same-password", 1000);
        check(!h1.equals(h2), "random salt per hash: two hashes of the same password differ");
        check(PasswordHasher.verify("same-password", h1) && !PasswordHasher.verify("Same-password", h1), "verify accepts/rejects");
        check(!PasswordHasher.verify("x", "garbage") && !PasswordHasher.verify("x", "pbkdf2$sha256$abc$@@$@@"), "garbage hash strings are rejected without exception");
        try (Env e = new Env()) {
            TC a = e.loggedIn("sara");
            a.c.logout();
            sleep(300);
            check(e.serverLog.stream().noneMatch(l -> l.contains("sara123")), "no password in server log");
            check(a.logs.stream().noneMatch(l -> l.contains("sara123")), "no password in client log");
        }
    }

    static void t24() throws Exception {
        DedupCache c = new DedupCache(3, 1000);
        check(c.firstSeen("a", 0) && !c.firstSeen("a", 10), "duplicate detected");
        c.firstSeen("b", 20); c.firstSeen("c", 30); c.firstSeen("d", 40);
        eq(3, c.size(), "bounded by max entries");
        check(c.firstSeen("a", 50), "evicted oldest key is new again (bounded memory)");
        DedupCache t = new DedupCache(100, 1000);
        t.firstSeen("x", 0);
        check(t.firstSeen("x", 1500), "entry expired after TTL");
        try (Env e = new Env()) {
            TC a = e.loggedIn("sara"), b = e.loggedIn("adam");
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (int i = 0; i < 200; i++) check(ids.add(a.c.send("adam", "m" + i, i % 2 == 0 ? Transport.TCP : Transport.UDP).id), "id collision at " + i);
        }
    }

    static void t25() throws Exception {
        try (Env e = new Env()) {
            TC a = e.loggedIn("sara"), b = e.loggedIn("adam");
            e.server.broadcastAnnouncement("Maintenance at 17:00 \u2014 \u0635\u064A\u0627\u0646\u0629");
            check(waitFor(() -> a.announcements.size() == 1 && b.announcements.size() == 1, 3000), "announcement delivered to all clients");
            // a raw client that never reads must not stall the others
            Raw stuck = new Raw(e.port);
            check(stuck.login("ali", "ali123", 7700).getBool("ok"), "stuck client logged in");
            for (int i = 0; i < 300; i++) { ChatMessage m = a.c.send("adam", "flood " + i, Transport.TCP); }
            check(waitFor(() -> b.incoming.size() >= 300, 10000), "healthy client still receives everything: " + b.incoming.size());
            stuck.close();
        }
    }
}
