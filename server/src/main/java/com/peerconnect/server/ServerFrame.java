/* PeerConnect - authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 */
package com.peerconnect.server;

import com.peerconnect.common.Protocol;
import com.peerconnect.common.UserInfo;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;

/**
 * Server administration window. All Swing access happens on the EDT; start()/stop() (socket bind, thread joins)
 * run on a single background executor, and the "Listening" label is changed only AFTER the bind really succeeded.
 */
public final class ServerFrame extends JFrame implements ServerListener {
    private final ServerConfig cfg;
    private final AccountStore accounts;
    private PeerConnectServer server;                       // touched only from the control executor
    private final ExecutorService control = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "pc-server-gui-control"); t.setDaemon(true); return t; });

    private final JTextField bindField = new JTextField(12), portField = new JTextField(6), announceField = new JTextField();
    private final JButton startBtn = new JButton("START"), stopBtn = new JButton("STOP"), broadcastBtn = new JButton("BROADCAST TO CLIENTS");
    private final JLabel statusLabel = new JLabel("STOPPED"), connectedLabel = new JLabel("Connected: 0"), errorLabel = new JLabel(" ");
    private final DefaultTableModel tableModel = new DefaultTableModel(new Object[] { "Username", "Presence", "IP address", "UDP port", "Last seen" }, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTextArea logArea = new JTextArea();
    private final SimpleDateFormat hms = new SimpleDateFormat("HH:mm:ss");
    private volatile boolean running;

    public ServerFrame(ServerConfig cfg, AccountStore accounts) {
        super("PeerConnect / TCP Directory & Relay Server");
        this.cfg = cfg;
        this.accounts = accounts;
        setName("serverFrame");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        build();
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { shutdownAndExit(); }
        });
        new Timer(1000, e -> refreshTable()).start();      // keeps "last seen" fresh; runs on the EDT
        setStopped();
    }

    private void build() {
        bindField.setText(cfg.bindIp);
        portField.setText(String.valueOf(cfg.port));
        bindField.setName("bindField"); portField.setName("portField"); startBtn.setName("startBtn"); stopBtn.setName("stopBtn");
        statusLabel.setName("statusLabel"); connectedLabel.setName("connectedLabel"); announceField.setName("announceField");
        broadcastBtn.setName("broadcastBtn"); logArea.setName("logArea");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 14f));
        errorLabel.setForeground(new Color(0xB00020));
        startBtn.setBackground(new Color(0x2E9E6B)); startBtn.setForeground(Color.WHITE); startBtn.setOpaque(true);
        stopBtn.setBackground(new Color(0xC95555)); stopBtn.setForeground(Color.WHITE); stopBtn.setOpaque(true);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        top.setBackground(new Color(0xE6F0F8));
        top.add(new JLabel("Bind IP:")); top.add(bindField);
        top.add(new JLabel("TCP port:")); top.add(portField);
        top.add(startBtn); top.add(stopBtn);
        top.add(statusLabel); top.add(connectedLabel);

        JTable table = new JTable(tableModel);
        table.setName("sessionsTable");
        table.setRowHeight(22);
        JPanel sessions = titled("ACTIVE CLIENT SESSIONS", new JScrollPane(table));

        JPanel ann = new JPanel(new BorderLayout(6, 6));
        ann.add(announceField, BorderLayout.CENTER);
        ann.add(broadcastBtn, BorderLayout.SOUTH);
        JPanel announce = titled("SERVER ANNOUNCEMENT", ann);
        announce.setPreferredSize(new Dimension(280, 100));

        JPanel mid = new JPanel(new BorderLayout(8, 8));
        mid.add(sessions, BorderLayout.CENTER);
        mid.add(announce, BorderLayout.EAST);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setPreferredSize(new Dimension(800, 190));
        JPanel logPanel = titled("REAL-TIME EVENT LOG", logScroll);

        JPanel south = new JPanel(new BorderLayout());
        south.add(errorLabel, BorderLayout.NORTH);
        south.add(logPanel, BorderLayout.CENTER);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        root.add(top, BorderLayout.NORTH);
        root.add(mid, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
        setSize(900, 640);
        setLocationByPlatform(true);

        startBtn.addActionListener(e -> doStart());
        stopBtn.addActionListener(e -> doStop());
        broadcastBtn.addActionListener(e -> doBroadcast());
    }

    private static JPanel titled(String title, java.awt.Component c) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createTitledBorder(title));
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    // ------------------------------------------------------------------ actions (EDT) -> work on background executor

    private void doStart() {
        errorLabel.setText(" ");
        final int port;
        final String ip = bindField.getText().trim();
        try {
            port = Protocol.parsePort(portField.getText(), "TCP port", false);
            if (ip.isEmpty()) throw new IllegalArgumentException("Bind IP is empty (use 0.0.0.0 for all interfaces)");
        } catch (IllegalArgumentException ex) {
            errorLabel.setText(ex.getMessage());
            return;
        }
        startBtn.setEnabled(false);
        statusLabel.setText("STARTING...");
        statusLabel.setForeground(Color.DARK_GRAY);
        control.execute(() -> {
            try {
                ServerConfig c = new ServerConfig();
                c.bindIp = ip; c.port = port; c.accountsFile = cfg.accountsFile; c.timeoutMs = cfg.timeoutMs;
                PeerConnectServer s = new PeerConnectServer(c, accounts, this);
                s.start();                                   // throws if the bind fails -> label never says Listening
                server = s;
            } catch (Exception ex) {
                String m = ex instanceof java.net.BindException ? "Cannot bind " + ip + ":" + port + " - " + ex.getMessage()
                        : "Start failed: " + ex.getMessage();
                SwingUtilities.invokeLater(() -> { errorLabel.setText(m); setStopped(); });
                onLog(Protocol.now() + "  [ERROR] " + m);
            }
        });
    }

    private void doStop() {
        stopBtn.setEnabled(false);
        statusLabel.setText("STOPPING...");
        control.execute(() -> { PeerConnectServer s = server; if (s != null) s.stop(); server = null; });
    }

    private void doBroadcast() {
        String text = announceField.getText();
        String err = Protocol.validateText(text);
        if (!running) err = "Server is not running";
        if (err != null) { errorLabel.setText(err); return; }
        errorLabel.setText(" ");
        control.execute(() -> { PeerConnectServer s = server; if (s != null) { try { s.broadcastAnnouncement(text); } catch (IllegalArgumentException ignored) { } } });
        announceField.setText("");
    }

    private void shutdownAndExit() {
        control.execute(() -> {
            PeerConnectServer s = server;
            if (s != null) s.stop();
            SwingUtilities.invokeLater(() -> { dispose(); System.exit(0); });
        });
    }

    // ------------------------------------------------------------------ ServerListener (any thread) -> EDT

    @Override public void onLog(String line) {
        SwingUtilities.invokeLater(() -> { logArea.append(line + "\n"); logArea.setCaretPosition(logArea.getDocument().getLength()); });
    }

    @Override public void onDirectoryChanged() { SwingUtilities.invokeLater(this::refreshTable); }

    @Override public void onStateChanged(boolean isRunning) {
        SwingUtilities.invokeLater(() -> { if (isRunning) setRunning(); else setStopped(); });
    }

    private void setRunning() {
        running = true;
        PeerConnectServer s = server;
        statusLabel.setText("LISTENING on " + bindField.getText().trim() + ":" + portField.getText().trim());
        statusLabel.setForeground(new Color(0x1B7F4B));
        startBtn.setEnabled(false); stopBtn.setEnabled(true);
        bindField.setEnabled(false); portField.setEnabled(false); broadcastBtn.setEnabled(true);
        refreshTable();
    }

    private void setStopped() {
        running = false;
        statusLabel.setText("STOPPED");
        statusLabel.setForeground(new Color(0xB00020));
        startBtn.setEnabled(true); stopBtn.setEnabled(false);
        bindField.setEnabled(true); portField.setEnabled(true); broadcastBtn.setEnabled(false);
        refreshTable();
    }

    private void refreshTable() {
        PeerConnectServer s = server;
        List<UserInfo> users = (running && s != null) ? s.snapshot() : List.of();
        tableModel.setRowCount(0);
        for (UserInfo u : users)
            tableModel.addRow(new Object[] { u.username(), u.presence().label(), u.ip(), u.udpPort(), hms.format(new Date(u.lastSeen())) });
        connectedLabel.setText("Connected: " + users.size());
    }
}
