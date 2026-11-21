/* PeerConnect - authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 */
package com.peerconnect.tests;

import com.peerconnect.client.core.ClientConfig;
import com.peerconnect.client.ui.ClientFrame;
import com.peerconnect.server.AccountStore;
import com.peerconnect.server.ServerConfig;
import com.peerconnect.server.ServerFrame;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;

/**
 * Drives the REAL Swing windows (server + 3-4 clients) through the assignment's demo scenario, takes screenshots of the real
 * windows, measures Swing-EDT latency while the network is waiting, and records checks. Needs a display (e.g. xvfb-run).
 * Usage: java -cp ... com.peerconnect.tests.GuiScenario <outDir> [slowFactor]
 */
public final class GuiScenario {
    static double slow = 1.0;
    static Path out;
    static JWindow caption; static JLabel captionLabel;
    static final List<String> report = new ArrayList<>();
    static final List<long[]> samples = new ArrayList<>();          // {timestampNanos, latencyNanos}
    static volatile boolean monitoring = true;
    static ServerFrame serverFrame;

    // ---------------------------------------------------------------- helpers
    static <T> T edt(Callable<T> c) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return c.call();
        final Object[] r = new Object[1]; final Exception[] ex = new Exception[1];
        SwingUtilities.invokeAndWait(() -> { try { r[0] = c.call(); } catch (Exception e) { ex[0] = e; } });
        if (ex[0] != null) throw ex[0];
        @SuppressWarnings("unchecked") T t = (T) r[0];
        return t;
    }
    static void run(Runnable r) throws Exception { edt(() -> { r.run(); return null; }); }
    static void pause(double sec) { try { Thread.sleep((long) (sec * 1000 * slow)); } catch (InterruptedException ignored) { } }
    static void sleepRaw(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    @SuppressWarnings("unchecked")
    static <T extends Component> T by(Container root, String name) {
        for (Component c : root.getComponents()) {
            if (name.equals(c.getName())) return (T) c;
            if (c instanceof Container) { T t = by((Container) c, name); if (t != null) return t; }
        }
        if (root instanceof javax.swing.JFrame) return by(((javax.swing.JFrame) root).getContentPane(), name);
        return null;
    }
    static String text(Container f, String name) throws Exception {
        return edt(() -> { Component c = by(f, name); if (c instanceof JLabel) return ((JLabel) c).getText(); if (c instanceof javax.swing.text.JTextComponent) return ((javax.swing.text.JTextComponent) c).getText(); if (c instanceof AbstractButton) return ((AbstractButton) c).getText(); return "?"; });
    }
    static void click(Container f, String name) throws Exception { run(() -> ((AbstractButton) by(f, name)).doClick()); }
    static void setText(Container f, String name, String t) throws Exception { run(() -> ((javax.swing.text.JTextComponent) by(f, name)).setText(t)); }
    static boolean waitUntil(java.util.function.BooleanSupplier c, long ms) { return Harness.waitFor(c, ms); }
    static boolean waitText(Container f, String name, String contains, long ms) {
        return waitUntil(() -> { try { return text(f, name).contains(contains); } catch (Exception e) { return false; } }, ms);
    }
    static void say(String s) throws Exception { System.out.println("[scenario] " + s); run(() -> captionLabel.setText("  " + s)); }
    static void check(String id, String what, boolean ok) { String line = (ok ? "PASSED  " : "FAILED  ") + id + "  " + what; report.add(line); System.out.println(line); }

    static void shot(Container f, String file) throws Exception {
        edt(() -> {
            BufferedImage img = new BufferedImage(f.getWidth(), f.getHeight(), BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = img.createGraphics();
            g.setColor(Color.WHITE); g.fillRect(0, 0, f.getWidth(), f.getHeight());
            f.paint(g); g.dispose();
            try { ImageIO.write(img, "png", out.resolve(file).toFile()); } catch (Exception e) { throw new RuntimeException(e); }
            return null;
        });
    }

    static void select(ClientFrame f, String peer) throws Exception {
        run(() -> {
            @SuppressWarnings("unchecked") JList<Object> l = by(f, "contactList");
            for (int i = 0; i < l.getModel().getSize(); i++) if (l.getModel().getElementAt(i).toString().contains("name=" + peer + ",")) { l.setSelectedIndex(i); return; }
            throw new IllegalStateException("contact " + peer + " not in list");
        });
    }
    static int contactUnread(ClientFrame f, String peer) throws Exception { return edt(() -> f.unreadCount(peer)); }
    static void send(ClientFrame f, String to, String transport, String msg) throws Exception {
        select(f, to);
        click(f, transport.equals("UDP") ? "udpBtn" : "tcpBtn");
        setText(f, "compose", msg);
        click(f, "sendBtn");
    }
    static void login(ClientFrame f, String user) throws Exception {
        setText(f, "passField", user + "123");
        click(f, "loginBtn");
        if (!waitText(f, "tcpState", "CONNECTED", 8000)) throw new IllegalStateException(user + " did not connect");
    }
    static void front(JFrame f) throws Exception { run(() -> { f.setVisible(true); f.toFront(); f.repaint(); }); }

    static long maxLatencyMs(long fromNanos, long toNanos) {
        long m = 0;
        synchronized (samples) { for (long[] s : samples) if (s[0] >= fromNanos && s[0] <= toNanos) m = Math.max(m, s[1]); }
        return m / 1_000_000;
    }

    // ---------------------------------------------------------------- scenario
    public static void main(String[] args) throws Exception {
        out = Paths.get(args[0]);
        Files.createDirectories(out);
        if (args.length > 1) slow = Double.parseDouble(args[1]);
        try { for (javax.swing.UIManager.LookAndFeelInfo i : javax.swing.UIManager.getInstalledLookAndFeels()) if ("Nimbus".equals(i.getName())) javax.swing.UIManager.setLookAndFeel(i.getClassName()); } catch (Exception ignored) { }

        // EDT latency monitor: posts a tiny task every 20 ms and measures how long the EDT needs to run it
        Thread mon = new Thread(() -> {
            while (monitoring) {
                final long t0 = System.nanoTime();
                SwingUtilities.invokeLater(() -> { synchronized (samples) { samples.add(new long[] { t0, System.nanoTime() - t0 }); } });
                sleepRaw(20);
            }
        }, "edt-monitor");
        mon.setDaemon(true); mon.start();

        Path accounts = Paths.get(System.getProperty("peerconnect.accounts", "config/accounts.txt"));
        ServerConfig sc = new ServerConfig(); sc.bindIp = "127.0.0.1"; sc.port = 18900; sc.timeoutMs = 15000;
        run(() -> {
            try { serverFrame = new ServerFrame(sc, AccountStore.load(accounts)); } catch (Exception e) { throw new RuntimeException(e); }
            serverFrame.setSize(900, 640); serverFrame.setLocation(0, 0); serverFrame.setVisible(true);
            caption = new JWindow(); captionLabel = new JLabel("  PeerConnect demo");
            captionLabel.setOpaque(true); captionLabel.setBackground(new Color(0x12304F)); captionLabel.setForeground(Color.WHITE);
            captionLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
            caption.getContentPane().add(captionLabel);
            caption.setBounds(0, 1000, 1920, 70); caption.setAlwaysOnTop(true); caption.setVisible(true);
        });
        pause(1);

        // ---- 1. server start (real bind) + three clients
        say("1) Server GUI: press START - status changes to LISTENING only after the real bind");
        check("G01", "server status is STOPPED before Start", text(serverFrame, "statusLabel").contains("STOPPED"));
        click(serverFrame, "startBtn");
        check("G02", "server status LISTENING after bind", waitText(serverFrame, "statusLabel", "LISTENING", 5000));
        shot(serverFrame, "server_listening.png"); pause(2);

        ClientConfig cc = new ClientConfig(); cc.serverHost = "127.0.0.1"; cc.serverPort = 18900;
        final ClientFrame[] cf = new ClientFrame[4];
        String[] names = { "sara", "adam", "ali" };
        for (int i = 0; i < 3; i++) {
            final int k = i;
            run(() -> { cf[k] = new ClientFrame(cc, names[k], "PeerConnect - " + names[k]); cf[k].setSize(1180, 720); cf[k].setLocation(700 - 40 * k, 60 + 30 * k); cf[k].setVisible(true); });
        }
        ClientFrame sara = cf[0], adam = cf[1], ali = cf[2];
        say("2) Three GUI clients log in (sara, adam, ali) - directory updates automatically");
        for (int i = 0; i < 3; i++) { front(cf[i]); login(cf[i], names[i]); pause(1); }
        check("G03", "three clients CONNECTED", true);
        check("G04", "each client lists the other two users", waitUntil(() -> { try { return edt(() -> { for (ClientFrame f : cf) if (f != null && f.getTitle().startsWith("PeerConnect - ")) { @SuppressWarnings("unchecked") JList<Object> l = by(f, "contactList"); if (f != cf[3] && l.getModel().getSize() != 2) return false; } return true; }); } catch (Exception e) { return false; } }, 5000));
        check("G05", "server GUI shows Connected: 3", waitText(serverFrame, "connectedLabel", "3", 4000));
        front(serverFrame); shot(serverFrame, "server_three_clients.png"); pause(2);
        front(sara); shot(sara, "client_login_contacts.png"); pause(1);

        // ---- 2b. wrong credentials + duplicate login (4th window)
        say("3) Duplicate login is rejected (second 'sara'), and wrong passwords give a clear error");
        run(() -> { cf[3] = new ClientFrame(cc, "sara", "PeerConnect - sara (duplicate attempt)"); cf[3].setSize(1180, 720); cf[3].setLocation(300, 120); cf[3].setVisible(true); });
        ClientFrame dup = cf[3]; front(dup);
        setText(dup, "passField", "wrong-password"); click(dup, "loginBtn");
        check("G06", "wrong password -> 'Wrong username or password'", waitText(dup, "loginStatus", "Wrong username or password", 6000));
        shot(dup, "client_wrong_credentials.png"); pause(2);
        setText(dup, "passField", "sara123"); click(dup, "loginBtn");
        check("G07", "duplicate login rejected with clear message", waitText(dup, "loginStatus", "already logged in", 6000));
        shot(dup, "client_duplicate_login.png"); pause(2);
        setText(dup, "hostField", "999.1.1.1"); click(dup, "loginBtn");
        check("G08", "invalid IP is rejected locally with a friendly message", waitText(dup, "loginStatus", "Invalid IPv4", 2000));
        setText(dup, "hostField", "127.0.0.1"); setText(dup, "portField", "70000"); click(dup, "loginBtn");
        check("G09", "invalid port is rejected locally", waitText(dup, "loginStatus", "between 1 and 65535", 2000));
        shot(dup, "client_invalid_port.png"); pause(1);

        // ---- 3. EDT responsiveness while the network blocks: connect to a non-routable address
        say("4) GUI stays responsive while connecting to an unreachable server");
        setText(dup, "portField", "8888"); setText(dup, "hostField", "10.255.255.1");
        long p0 = System.nanoTime();
        click(dup, "loginBtn");
        pause(1.5); shot(dup, "client_connecting_unreachable.png");
        waitText(dup, "loginStatus", "", 100);
        waitUntil(() -> { try { return !text(dup, "loginStatus").startsWith("Connecting"); } catch (Exception e) { return true; } }, 9000);
        long p1 = System.nanoTime();
        long m1 = maxLatencyMs(p0 + 200_000_000L, p1);
        check("G10", "EDT max latency while connecting to unreachable host (" + (p1 - p0) / 1_000_000 + " ms of blocking network wait) = " + m1 + " ms (< 250 ms)", m1 < 250);
        report.add("        login result text: " + text(dup, "loginStatus"));
        run(() -> dup.dispose());

        // ---- 4. UDP P2P with ACK
        say("5) sara -> adam over UDP P2P: delivered directly, ACK received (server does not relay)");
        front(sara);
        send(sara, "adam", "UDP", "Hi Adam, can you test direct messaging?");
        check("G11", "adam received the UDP message (unread badge)", waitUntil(() -> { try { return contactUnread(adam, "sara") == 1; } catch (Exception e) { return false; } }, 4000));
        pause(1.5);
        check("G12", "sara's message status is ACKNOWLEDGED (set only after the ACK arrived)", waitUntil(() -> { try { return edt(() -> sara.lastStatusOf("adam").equals("ACKNOWLEDGED")); } catch (Exception e) { return false; } }, 3000));
        shot(sara, "client_udp_ack.png");
        front(adam); shot(adam, "client_unread_badge.png"); pause(2);
        run(() -> {}); select(adam, "sara");
        check("G13", "unread badge cleared when the conversation is opened", contactUnread(adam, "sara") == 0);
        send(adam, "sara", "UDP", "Received! Direct P2P works.");
        pause(2); shot(adam, "client_udp_reply.png");
        front(serverFrame); shot(serverFrame, "server_no_udp_relay.png"); pause(1);

        // ---- 5. TCP via server to ali
        say("6) sara -> ali over TCP via Server: the server relays to the addressed user only");
        front(sara); send(sara, "ali", "TCP", "Hello Ali, this message is relayed by the server");
        check("G14", "ali received the relayed message", waitUntil(() -> { try { return contactUnread(ali, "sara") == 1; } catch (Exception e) { return false; } }, 4000));
        check("G15", "adam (not addressed) has no unread for ali", contactUnread(adam, "sara") == 0);
        pause(1.5); shot(sara, "client_tcp_relay.png");
        front(ali); select(ali, "sara"); shot(ali, "client_tcp_received.png"); pause(1.5);
        front(serverFrame); shot(serverFrame, "server_tcp_relay_log.png"); pause(1.5);

        // ---- 6. Arabic multiline
        say("7) Arabic + multiline + quotes over UDP");
        front(sara); send(sara, "adam", "UDP", "مرحبًا آدم!\nهذه رسالة عربية متعددة الأسطر مع \"اقتباس\"\nالسطر الثالث");
        pause(2.5); shot(sara, "client_arabic_multiline.png");
        front(adam); select(adam, "sara"); pause(1); shot(adam, "client_arabic_received.png");
        check("G16", "separate histories: adam<->sara has 3 bubbles, adam has no conversation with ali", edt(() -> adam.historySize("sara") == 3 && adam.historySize("ali") == 0));
        check("G17", "separate histories: sara has 3 with adam and 1 with ali", edt(() -> sara.historySize("adam") == 3 && sara.historySize("ali") == 1));

        // ---- 7. presence
        say("8) adam changes presence to Busy - everybody's contact list updates");
        front(adam);
        run(() -> { @SuppressWarnings("unchecked") javax.swing.JComboBox<String> b = by(adam, "presenceBox"); b.setSelectedItem("Busy"); });
        pause(2);
        front(sara); check("G18", "sara sees adam as Busy", waitUntil(() -> { try { return edt(() -> { @SuppressWarnings("unchecked") JList<Object> l = by(sara, "contactList"); for (int i = 0; i < l.getModel().getSize(); i++) if (l.getModel().getElementAt(i).toString().contains("name=adam,") && l.getModel().getElementAt(i).toString().contains("BUSY")) return true; return false; }); } catch (Exception e) { return false; } }, 4000));
        select(sara, "adam"); shot(sara, "client_presence_busy.png"); pause(2);

        // ---- 8. offline user
        say("9) Sending to an offline user: clear error, GUI keeps working");
        run(() -> sara.openConversation("omar"));
        long o0 = System.nanoTime();
        click(sara, "tcpBtn"); setText(sara, "compose", "Anyone there?"); click(sara, "sendBtn");
        click(sara, "udpBtn"); setText(sara, "compose", "Anyone there (UDP)?"); click(sara, "sendBtn");
        pause(2);
        long o1 = System.nanoTime();
        check("G19", "offline recipient: both messages (TCP, UDP) end as FAILED", waitUntil(() -> { try { return edt(() -> sara.historySize("omar") == 2 && sara.lastStatusOf("omar").equals("FAILED")); } catch (Exception e) { return false; } }, 3000));
        check("G20", "EDT max latency while sending to offline user = " + maxLatencyMs(o0, o1) + " ms (< 250 ms)", maxLatencyMs(o0, o1) < 250);
        shot(sara, "client_offline_error.png"); pause(1);

        // ---- 9. UDP packet loss simulation
        say("10) Simulated UDP packet loss: adam drops incoming UDP, sara retries 3 times then Failed");
        front(adam); click(adam, "dropUdp");
        check("G21", "adam UDP state shows PAUSED (test mode)", waitText(adam, "udpState", "PAUSED", 2000));
        shot(adam, "client_udp_paused.png");
        front(sara); select(sara, "adam");
        long l0 = System.nanoTime();
        send(sara, "adam", "UDP", "This datagram will be lost");
        sleepRaw(1500);
        shot(sara, "client_udp_retry.png");
        sleepRaw(2200);
        long l1 = System.nanoTime();
        shot(sara, "client_udp_failed.png");
        check("G22", "sara's message ends as FAILED after 3 attempts (no ACK)", edt(() -> sara.historySize("adam") == 4 && sara.lastStatusOf("adam").equals("FAILED")));
        check("G23", "EDT max latency during the 3-second retry window = " + maxLatencyMs(l0 + 100_000_000L, l1) + " ms (< 250 ms)", maxLatencyMs(l0 + 100_000_000L, l1) < 250);
        front(adam); shot(adam, "client_udp_dropped_log.png"); pause(2);
        click(adam, "dropUdp");
        front(sara); send(sara, "adam", "UDP", "UDP is back"); pause(2.5); shot(sara, "client_udp_recovered.png");
        check("G22b", "after UDP is re-enabled the next message is ACKNOWLEDGED", edt(() -> sara.lastStatusOf("adam").equals("ACKNOWLEDGED")));

        // ---- 10. keyboard bindings + counter + disabled Send
        say("11) Send is disabled for empty text / over 1000 characters; counter counts characters");
        front(sara); select(sara, "adam");
        setText(sara, "compose", "");
        check("G24", "Send disabled for empty text", !edt(() -> ((AbstractButton) by(sara, "sendBtn")).isEnabled()));
        setText(sara, "compose", "ع".repeat(1001));
        check("G25", "Send disabled for 1001 characters; counter shows 1001/1000", !edt(() -> ((AbstractButton) by(sara, "sendBtn")).isEnabled()) && text(sara, "counter").equals("1001/1000"));
        shot(sara, "client_limit_1000.png");
        setText(sara, "compose", "ع".repeat(1000));
        check("G26", "Send enabled at exactly 1000 characters (2000 UTF-8 bytes)", edt(() -> ((AbstractButton) by(sara, "sendBtn")).isEnabled()));
        setText(sara, "compose", "");
        check("G27", "Enter = send, Shift+Enter = newline key bindings installed", edt(() -> {
            JTextArea t = by(sara, "compose");
            return "send-message".equals(String.valueOf(t.getInputMap().get(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0))))
                    && "insert-break".equals(String.valueOf(t.getInputMap().get(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, java.awt.event.InputEvent.SHIFT_DOWN_MASK))));
        }));
        run(() -> { JTextArea t = by(sara, "compose"); t.setText("sent with the Enter key action"); t.getActionMap().get("send-message").actionPerformed(null); });
        pause(2);
        check("G28", "Enter action sent the message (history grew)", edt(() -> sara.historySize("adam") == 6));

        // ---- 11. server announcement + stop/start
        say("12) Server broadcast announcement");
        front(serverFrame);
        setText(serverFrame, "announceField", "System maintenance at 17:00");
        click(serverFrame, "broadcastBtn");
        front(sara);
        check("G29", "announcement reached the client", waitUntil(() -> { try { return edt(() -> by(sara, "banner") != null) && text(sara, "banner").contains("maintenance"); } catch (Exception e) { return false; } }, 3000));
        shot(sara, "client_announcement.png"); pause(2);

        say("13) Server STOP: clients detect it; START again: cleanup, no stuck port");
        front(serverFrame); click(serverFrame, "stopBtn");
        check("G30", "server shows STOPPED", waitText(serverFrame, "statusLabel", "STOPPED", 6000));
        check("G31", "clients report DISCONNECTED", waitText(sara, "tcpState", "DISCONNECTED", 6000) && waitText(adam, "tcpState", "DISCONNECTED", 6000));
        shot(serverFrame, "server_stopped.png");
        front(sara); shot(sara, "client_server_down.png"); pause(2);
        run(() -> { ((javax.swing.text.JTextComponent) by(sara, "compose")).setText("typed while disconnected"); });
        check("G32", "Send disabled while disconnected", !edt(() -> ((AbstractButton) by(sara, "sendBtn")).isEnabled()));
        front(serverFrame); click(serverFrame, "startBtn");
        check("G33", "server LISTENING again on the same port", waitText(serverFrame, "statusLabel", "LISTENING", 6000));
        front(sara); login(sara, "sara"); pause(1);
        shot(sara, "client_relogin_after_restart.png");
        check("G34", "login works after server restart", text(sara, "tcpState").contains("CONNECTED"));
        front(serverFrame); shot(serverFrame, "server_after_restart.png"); pause(1);

        // overall responsiveness
        long worst = maxLatencyMs(0, Long.MAX_VALUE);
        report.add("INFO  EDT latency samples: " + samples.size() + ", overall max (includes screenshot painting on the EDT) = " + worst + " ms");
        monitoring = false;
        try { BufferedImage full = new Robot().createScreenCapture(new java.awt.Rectangle(0, 0, 1920, 1080)); ImageIO.write(full, "png", out.resolve("fullscreen.png").toFile()); } catch (Exception ignored) { }
        say("Scenario finished");
        long failed = report.stream().filter(r -> r.startsWith("FAILED")).count();
        report.add("SUMMARY GUI scenario: " + report.stream().filter(r -> r.startsWith("PASSED")).count() + " passed, " + failed + " failed");
        Files.write(out.resolve("gui-scenario-results.txt"), report);
        System.exit(failed == 0 ? 0 : 1);
    }
}
