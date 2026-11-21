/* PeerConnect - authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 */
package com.peerconnect.server;

import com.peerconnect.common.Protocol;
import com.peerconnect.common.UserInfo;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
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
import javax.swing.border.TitledBorder;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicGraphicsUtils;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;

/**
 * Server administration window. All Swing access happens on the EDT; start()/stop() (socket bind, thread joins)
 * run on a single background executor, and the "Listening" label is changed only AFTER the bind really succeeded.
 */
public final class ServerFrame extends JFrame implements ServerListener {
    private static final Color JADE = new Color(0x042D22);
    private static final Color LIME = new Color(0xD9E991);
    private static final Color FIELD = new Color(0xEFF5DD);
    private static final Color IVORY = new Color(0xF7F9EF);
    private static final Color SECONDARY = new Color(0x3F6858);
    private static final Color BORDER = new Color(0x6F8D7E);
    private static final Color ERROR = new Color(0xFF9C9C);
    private static final Color SUCCESS = new Color(0xAADCAC);

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
        errorLabel.setForeground(ERROR);
        connectedLabel.setForeground(IVORY);
        for (JTextField field : new JTextField[] { bindField, portField, announceField }) {
            field.setBackground(FIELD);
            field.setForeground(JADE);
            field.setCaretColor(JADE);
            field.setDisabledTextColor(SECONDARY);
            recolorBorder(field, BORDER);
        }
        styleButton(startBtn, LIME, JADE);
        styleButton(stopBtn, SECONDARY, IVORY);
        styleButton(broadcastBtn, SECONDARY, IVORY);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        top.setBackground(JADE);
        JLabel bindLabel = new JLabel("Bind IP:");
        bindLabel.setForeground(IVORY);
        JLabel portLabel = new JLabel("TCP port:");
        portLabel.setForeground(IVORY);
        top.add(bindLabel); top.add(bindField);
        top.add(portLabel); top.add(portField);
        top.add(startBtn); top.add(stopBtn);
        top.add(statusLabel); top.add(connectedLabel);

        JTable table = new JTable(tableModel);
        table.setName("sessionsTable");
        table.setRowHeight(22);
        table.setBackground(IVORY);
        table.setForeground(JADE);
        table.setGridColor(BORDER);
        table.setSelectionBackground(LIME);
        table.setSelectionForeground(JADE);

        JTableHeader header = table.getTableHeader();
        header.setBackground(SECONDARY);
        header.setForeground(IVORY);
        TableCellRenderer headerRenderer = header.getDefaultRenderer();
        header.setDefaultRenderer((t, value, selected, focus, row, column) -> {
            java.awt.Component cell = headerRenderer.getTableCellRendererComponent(
                    t, value, selected, focus, row, column);
            cell.setBackground(SECONDARY);
            cell.setForeground(IVORY);
            if (cell instanceof JComponent component) {
                component.setOpaque(true);
                recolorBorder(component, focus ? LIME : BORDER);
            }
            return cell;
        });

        JScrollPane sessionScroll = new JScrollPane(table);
        sessionScroll.setBackground(IVORY);
        sessionScroll.getViewport().setBackground(IVORY);
        sessionScroll.setColumnHeaderView(header);
        sessionScroll.getColumnHeader().setBackground(SECONDARY);        recolorBorder(sessionScroll, BORDER);
        JPanel sessions = titled("ACTIVE CLIENT SESSIONS", sessionScroll);

        JPanel ann = new JPanel(new BorderLayout(6, 6));
        ann.setBackground(JADE);
        ann.add(announceField, BorderLayout.CENTER);
        ann.add(broadcastBtn, BorderLayout.SOUTH);
        JPanel announce = titled("SERVER ANNOUNCEMENT", ann);
        announce.setPreferredSize(new Dimension(280, 100));

        JPanel mid = new JPanel(new BorderLayout(8, 8));
        mid.setBackground(JADE);
        mid.add(sessions, BorderLayout.CENTER);
        mid.add(announce, BorderLayout.EAST);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setBackground(IVORY);
        logArea.setForeground(JADE);
        logArea.setCaretColor(JADE);
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBackground(IVORY);
        logScroll.getViewport().setBackground(IVORY);
        recolorBorder(logScroll, BORDER);
        logScroll.setPreferredSize(new Dimension(800, 190));
        JPanel logPanel = titled("REAL-TIME EVENT LOG", logScroll);

        JPanel south = new JPanel(new BorderLayout());
        south.setBackground(JADE);
        south.add(errorLabel, BorderLayout.NORTH);
        south.add(logPanel, BorderLayout.CENTER);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBackground(JADE);
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
        p.setBackground(JADE);
        TitledBorder border = BorderFactory.createTitledBorder(title);
        Insets insets = border.getBorder().getBorderInsets(p);
        border.setBorder(BorderFactory.createMatteBorder(
                insets.top, insets.left, insets.bottom, insets.right, BORDER));
        border.setTitleColor(IVORY);
        p.setBorder(border);
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    private static void recolorBorder(JComponent component, Color color) {
        Insets insets = component.getInsets();
        component.setBorder(BorderFactory.createMatteBorder(
                insets.top, insets.left, insets.bottom, insets.right, color));
    }

    private static void styleButton(JButton button, Color background, Color foreground) {
        Font font = button.getFont();
        Insets insets = button.getInsets();
        button.setUI(new BasicButtonUI() {
            @Override public void update(Graphics g, JComponent c) {
                g.setColor(button.isEnabled() ? background : SECONDARY);
                g.fillRect(0, 0, c.getWidth(), c.getHeight());
                paint(g, c);
                if (button.isFocusPainted() && button.hasFocus()) {
                    g.setColor(foreground);
                    g.drawRect(2, 2, c.getWidth() - 5, c.getHeight() - 5);
                }
            }

            @Override protected void paintText(Graphics g, javax.swing.AbstractButton b,
                                               Rectangle bounds, String text) {
                g.setColor(b.isEnabled() ? foreground : new Color(0xAFC2B8));
                BasicGraphicsUtils.drawStringUnderlineCharAt(g, text, b.getDisplayedMnemonicIndex(),
                        bounds.x, bounds.y + g.getFontMetrics().getAscent());
            }
        });
        button.setFont(font);
        button.setOpaque(true);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(
                        insets.top - 1, insets.left - 1, insets.bottom - 1, insets.right - 1)));
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
        statusLabel.setForeground(IVORY);
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
        statusLabel.setForeground(SUCCESS);
        startBtn.setEnabled(false); stopBtn.setEnabled(true);
        bindField.setEnabled(false); portField.setEnabled(false); broadcastBtn.setEnabled(true);
        refreshTable();
    }

    private void setStopped() {
        running = false;
        statusLabel.setText("STOPPED");
        statusLabel.setForeground(ERROR);
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