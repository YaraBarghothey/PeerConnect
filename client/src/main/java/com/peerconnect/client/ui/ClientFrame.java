/* PeerConnect - authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 */
package com.peerconnect.client.ui;

import com.peerconnect.client.core.ClientConfig;
import com.peerconnect.client.core.MessengerClient;
import com.peerconnect.client.core.MessengerListener;
import com.peerconnect.client.model.ChatMessage;
import com.peerconnect.client.model.ConversationStore;
import com.peerconnect.client.model.MsgStatus;
import com.peerconnect.client.model.TcpState;
import com.peerconnect.client.model.Transport;
import com.peerconnect.common.Presence;
import com.peerconnect.common.Protocol;
import com.peerconnect.common.UserInfo;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.ComponentOrientation;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Three-column messenger window: identity+contacts | conversation | network+diagnostics.
 * THREADING RULE: this class only touches Swing on the EDT. MessengerListener callbacks arrive on network threads and are
 * re-posted with SwingUtilities.invokeLater (see ui()). No socket call, DNS lookup or wait is ever made from here:
 * everything goes through MessengerClient, which returns immediately.
 */
public final class ClientFrame extends JFrame implements MessengerListener {
    private static final Color OUT_BG = new Color(0xD4E8FB), IN_BG = new Color(0xEEF2F6), NAVY = new Color(0x12304F);
    private static final int MAX = Protocol.MAX_TEXT_CHARS;

    private final MessengerClient client;
    private final ConversationStore store = new ConversationStore();
    private final SimpleDateFormat hms = new SimpleDateFormat("HH:mm:ss");

    // left
    private final JTextField userField = new JTextField(10), hostField = new JTextField(10), portField = new JTextField(5), udpField = new JTextField(5), searchField = new JTextField();
    private final JPasswordField passField = new JPasswordField(10);
    private final JButton loginBtn = new JButton("Login"), logoutBtn = new JButton("Logout"), newConvBtn = new JButton("New conversation"), refreshBtn = new JButton("Refresh users");
    private final JLabel loginStatus = new JLabel(" ");
    private final JComboBox<String> presenceBox = new JComboBox<>(new String[] { "Online", "Busy", "Away" });
    private final DefaultListModel<Contact> contactModel = new DefaultListModel<>();
    private final JList<Contact> contactList = new JList<>(contactModel);
    // centre
    private final JLabel chatTitle = new JLabel("No conversation selected"), chatSub = new JLabel(" "), banner = new JLabel(" ");
    private final JToggleButton udpBtn = new JToggleButton("UDP P2P", true), tcpBtn = new JToggleButton("TCP via Server");
    private final JPanel bubbles = new JPanel();
    private final JScrollPane bubbleScroll;
    private final JTextArea compose = new JTextArea(3, 30);
    private final JLabel counter = new JLabel("0/" + MAX), composeHint = new JLabel("Enter = send   |   Shift+Enter = new line");
    private final JButton sendBtn = new JButton("SEND");
    // right
    private final JTextField localUdpField = new JTextField("-"), peerIpField = new JTextField("-"), peerPortField = new JTextField("-");
    private final JLabel tcpState = new JLabel("DISCONNECTED"), udpState = new JLabel("Closed"), lastTransport = new JLabel("-");
    private final JCheckBox dropUdp = new JCheckBox("<html>TEST: simulate UDP packet loss<br>(drop incoming UDP, TCP stays connected)</html>");
    private final JTextArea logArea = new JTextArea();
    private final JButton clearLog = new JButton("Clear log");

    // EDT-only state
    private final Map<String, UserInfo> online = new HashMap<>();
    private final Set<String> extraPeers = new LinkedHashSet<>();
    private final Map<String, JLabel> statusLabels = new HashMap<>();
    private String selectedPeer;
    private boolean connected, connecting, adjusting;

    record Contact(String name, UserInfo info, int unread) { }

    public ClientFrame(ClientConfig cfg, String presetUser, String title) {
        super(title == null ? "PeerConnect / Desktop Messenger" : title);
        setName("clientFrame");
        client = new MessengerClient(cfg, this);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        hostField.setText(cfg.serverHost);
        portField.setText(String.valueOf(cfg.serverPort));
        udpField.setText("0");
        userField.setText(presetUser != null ? presetUser : cfg.defaultUsername);
        udpField.setToolTipText("0 = choose a free UDP port automatically");
        bubbleScroll = new JScrollPane(wrapBubbles());
        nameComponents();
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        root.add(header(), BorderLayout.NORTH);
        JPanel cols = new JPanel(new java.awt.GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.fill = GridBagConstraints.BOTH; g.weighty = 1; g.gridy = 0; g.insets = new Insets(0, 0, 0, 8);
        g.gridx = 0; g.weightx = 0.25; cols.add(leftPanel(), g);
        g.gridx = 1; g.weightx = 0.5; cols.add(centerPanel(), g);
        g.gridx = 2; g.weightx = 0.25; g.insets = new Insets(0, 0, 0, 0); cols.add(rightPanel(), g);
        root.add(cols, BorderLayout.CENTER);
        setContentPane(root);
        setSize(1180, 720);
        setMinimumSize(new Dimension(1000, 600));
        setLocationByPlatform(true);
        wire();
        updateControls();
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { closeApp(); }
        });
    }

    // ------------------------------------------------------------------ construction

    private void nameComponents() {
        userField.setName("userField"); passField.setName("passField"); hostField.setName("hostField"); portField.setName("portField");
        udpField.setName("udpField"); loginBtn.setName("loginBtn"); logoutBtn.setName("logoutBtn"); loginStatus.setName("loginStatus");
        presenceBox.setName("presenceBox"); searchField.setName("searchField"); contactList.setName("contactList"); newConvBtn.setName("newConvBtn");
        refreshBtn.setName("refreshBtn"); chatTitle.setName("chatTitle"); udpBtn.setName("udpBtn"); tcpBtn.setName("tcpBtn");
        compose.setName("compose"); sendBtn.setName("sendBtn"); counter.setName("counter"); localUdpField.setName("localUdpField");
        peerIpField.setName("peerIpField"); peerPortField.setName("peerPortField"); tcpState.setName("tcpState"); udpState.setName("udpState");
        lastTransport.setName("lastTransport"); dropUdp.setName("dropUdp"); logArea.setName("logArea"); clearLog.setName("clearLog"); banner.setName("banner");
        bubbles.setName("bubbles");
    }

    private JComponent header() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(NAVY);
        p.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        JLabel t = new JLabel("PeerConnect  /  Desktop Messenger");
        t.setForeground(Color.WHITE);
        t.setFont(t.getFont().deriveFont(Font.BOLD, 18f));
        p.add(t, BorderLayout.WEST);
        banner.setForeground(new Color(0xFFE08A));
        p.add(banner, BorderLayout.EAST);
        return p;
    }

    private JComponent leftPanel() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createTitledBorder("IDENTITY & CONTACTS"));
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 2, 2, 2); c.fill = GridBagConstraints.HORIZONTAL;
        String[] labels = { "Username", "Password", "Server IP", "Server port", "My UDP port" };
        JComponent[] fields = { userField, passField, hostField, portField, udpField };
        for (int i = 0; i < labels.length; i++) {
            c.gridx = 0; c.gridy = i; c.weightx = 0; form.add(new JLabel(labels[i]), c);
            c.gridx = 1; c.weightx = 1; form.add(fields[i], c);
        }
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        btns.add(loginBtn); btns.add(logoutBtn);
        c.gridx = 0; c.gridy = 5; c.gridwidth = 2; form.add(btns, c);
        c.gridy = 6; form.add(loginStatus, c);
        c.gridy = 7; c.gridwidth = 1; c.gridx = 0; form.add(new JLabel("My status"), c);
        c.gridx = 1; form.add(presenceBox, c);

        JPanel list = new JPanel(new BorderLayout(4, 4));
        searchField.setToolTipText("Search online users");
        list.add(searchField, BorderLayout.NORTH);
        contactList.setCellRenderer(new ContactRenderer());
        contactList.setFixedCellHeight(46);
        list.add(new JScrollPane(contactList), BorderLayout.CENTER);
        JPanel lb = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        lb.add(newConvBtn); lb.add(refreshBtn);
        list.add(lb, BorderLayout.SOUTH);
        p.add(form, BorderLayout.NORTH);
        p.add(list, BorderLayout.CENTER);
        p.setPreferredSize(new Dimension(290, 600));
        return p;
    }

    private JComponent wrapBubbles() {
        bubbles.setLayout(new BoxLayout(bubbles, BoxLayout.Y_AXIS));
        bubbles.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(Color.WHITE);
        bubbles.setBackground(Color.WHITE);
        wrapper.add(bubbles, BorderLayout.NORTH);
        return wrapper;
    }

    private JComponent centerPanel() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createTitledBorder("CONVERSATION"));
        JPanel top = new JPanel(new BorderLayout());
        chatTitle.setFont(chatTitle.getFont().deriveFont(Font.BOLD, 16f));
        chatSub.setForeground(Color.DARK_GRAY);
        JPanel titles = new JPanel(new GridLayout2());
        titles.add(chatTitle); titles.add(chatSub);
        top.add(titles, BorderLayout.WEST);
        JPanel tr = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        ButtonGroup bg = new ButtonGroup();
        bg.add(udpBtn); bg.add(tcpBtn);
        tr.add(new JLabel("Transport mode:")); tr.add(udpBtn); tr.add(tcpBtn);
        top.add(tr, BorderLayout.EAST);
        p.add(top, BorderLayout.NORTH);
        p.add(bubbleScroll, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(4, 4));
        compose.setLineWrap(true); compose.setWrapStyleWord(true);
        JPanel meta = new JPanel(new BorderLayout());
        composeHint.setForeground(Color.GRAY);
        meta.add(composeHint, BorderLayout.WEST); meta.add(counter, BorderLayout.EAST);
        south.add(new JScrollPane(compose), BorderLayout.CENTER);
        sendBtn.setPreferredSize(new Dimension(90, 60));
        south.add(sendBtn, BorderLayout.EAST);
        south.add(meta, BorderLayout.SOUTH);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    /** Tiny helper layout: stacks two labels vertically. */
    private static final class GridLayout2 extends java.awt.GridLayout { GridLayout2() { super(2, 1); } }

    private JComponent rightPanel() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createTitledBorder("NETWORK & DIAGNOSTICS"));
        JPanel info = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 2, 2, 2); c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1; c.gridx = 0;
        for (JTextField f : new JTextField[] { localUdpField, peerIpField, peerPortField }) { f.setEditable(false); }
        int row = 0;
        Object[][] rows = { { "My UDP port", localUdpField }, { "Selected peer IP", peerIpField }, { "Selected peer UDP port", peerPortField },
                { "TCP connection", tcpState }, { "UDP listening", udpState }, { "Last transport used", lastTransport } };
        for (Object[] r : rows) {
            c.gridy = row++; info.add(new JLabel((String) r[0]), c);
            c.gridy = row++; info.add((Component) r[1], c);
        }
        c.gridy = row++; info.add(dropUdp, c);
        logArea.setEditable(false); logArea.setLineWrap(true); logArea.setWrapStyleWord(true); logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JPanel logP = new JPanel(new BorderLayout(2, 2));
        logP.setBorder(BorderFactory.createTitledBorder("Event log / errors"));
        logP.add(new JScrollPane(logArea), BorderLayout.CENTER);
        logP.add(clearLog, BorderLayout.SOUTH);
        p.add(info, BorderLayout.NORTH);
        p.add(logP, BorderLayout.CENTER);
        p.setPreferredSize(new Dimension(300, 600));
        return p;
    }

    private void wire() {
        loginBtn.addActionListener(e -> doLogin());
        passField.addActionListener(e -> doLogin());
        logoutBtn.addActionListener(e -> { client.logout(); });
        refreshBtn.addActionListener(e -> client.refreshUsers());
        newConvBtn.addActionListener(e -> newConversation());
        clearLog.addActionListener(e -> logArea.setText(""));
        sendBtn.addActionListener(e -> doSend());
        presenceBox.addActionListener(e -> { if (!adjusting && connected) client.setPresence(Presence.parse((String) presenceBox.getSelectedItem())); });
        dropUdp.addActionListener(e -> client.setUdpReceiveEnabled(!dropUdp.isSelected()));
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { refreshContacts(); }
            public void removeUpdate(DocumentEvent e) { refreshContacts(); }
            public void changedUpdate(DocumentEvent e) { refreshContacts(); }
        });
        contactList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || adjusting) return;
            Contact c = contactList.getSelectedValue();
            if (c != null && !c.name().equals(selectedPeer)) selectPeer(c.name());
        });
        compose.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { updateControls(); }
            public void removeUpdate(DocumentEvent e) { updateControls(); }
            public void changedUpdate(DocumentEvent e) { updateControls(); }
        });
        // Enter = send, Shift+Enter = newline (the text area's default "insert-break" action)
        compose.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send-message");
        compose.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "insert-break");
        compose.getActionMap().put("send-message", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { if (sendBtn.isEnabled()) doSend(); }
        });
    }

    // ------------------------------------------------------------------ user actions (EDT)

    private void doLogin() {
        if (connected || connecting) return;
        try {
            connecting = true;
            loginStatus.setForeground(Color.DARK_GRAY);
            loginStatus.setText("Connecting...");
            updateControls();
            client.login(hostField.getText(), portField.getText(), userField.getText().trim(), new String(passField.getPassword()), udpField.getText());
        } catch (IllegalArgumentException ex) {          // invalid IP/port/username: friendly message, nothing sent
            connecting = false;
            loginStatus.setForeground(new Color(0xB00020));
            loginStatus.setText(ex.getMessage());
            log("Input error: " + ex.getMessage());
            updateControls();
        }
    }

    private void doSend() {
        String text = compose.getText().strip();
        if (selectedPeer == null) return;
        String err = Protocol.validateText(text);
        if (err != null) { banner.setText(err); log("Cannot send: " + err); return; }
        Transport t = udpBtn.isSelected() ? Transport.UDP : Transport.TCP;
        try {
            ChatMessage m = client.send(selectedPeer, text, t);     // returns immediately; I/O happens in the background
            store.add(m);
            appendBubble(m);
            compose.setText("");
            lastTransport.setText(t.label() + "  (" + hms.format(new Date()) + ")");
            banner.setText(" ");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            banner.setText(ex.getMessage());
            log("Cannot send: " + ex.getMessage());
        }
    }

    private void newConversation() {
        JComboBox<String> box = new JComboBox<>();
        box.setEditable(true);
        for (String n : new TreeSet<>(online.keySet())) if (!n.equals(client.username())) box.addItem(n);
        box.setSelectedItem("");
        int r = JOptionPane.showConfirmDialog(this, new Object[] { "Choose or type the username to chat with:", box }, "New conversation", JOptionPane.OK_CANCEL_OPTION);
        if (r != JOptionPane.OK_OPTION) return;
        openConversation(String.valueOf(box.getSelectedItem()).trim());
    }

    /** Opens (or creates) a conversation with the given username; used by the "New conversation" dialog and by GUI tests. EDT only. */
    public void openConversation(String n) {
        if (!Protocol.isValidUsername(n)) { banner.setText("Invalid username: '" + n + "'"); return; }
        if (n.equals(client.username())) { banner.setText("You cannot chat with yourself"); return; }
        extraPeers.add(n);
        store.ensureConversation(n);
        refreshContacts();
        selectPeer(n);
    }

    /** Test accessors (EDT only). */
    public int unreadCount(String peer) { return store.unread(peer); }
    public int historySize(String peer) { return store.history(peer).size(); }
    public String lastStatusOf(String peer) { List<ChatMessage> h = store.history(peer); return h.isEmpty() ? "-" : h.get(h.size() - 1).status().name(); }
    public int visibleBubbleCount() { return bubbles.getComponentCount(); }

    private void closeApp() {
        setEnabled(false);
        Thread t = new Thread(() -> {
            client.shutdown(2500);                               // network work: NOT on the EDT
            SwingUtilities.invokeLater(() -> { dispose(); System.exit(0); });
        }, "pc-client-shutdown");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ state -> widgets (EDT)

    private void selectPeer(String name) {
        selectedPeer = name;
        store.clearUnread(name);
        rebuildBubbles();
        refreshContacts();
        updateHeader();
        updateControls();
    }

    private void updateHeader() {
        if (selectedPeer == null) { chatTitle.setText("No conversation selected"); chatSub.setText(" "); peerIpField.setText("-"); peerPortField.setText("-"); return; }
        chatTitle.setText(selectedPeer);
        UserInfo u = online.get(selectedPeer);
        if (u == null) {
            chatSub.setText(connected ? "Offline (messages will fail)" : "Not connected");
            peerIpField.setText("-"); peerPortField.setText("-");
        } else {
            chatSub.setText(u.presence().label() + "  |  UDP ready (" + u.ip() + ":" + u.udpPort() + ")");
            peerIpField.setText(u.ip()); peerPortField.setText(String.valueOf(u.udpPort()));
        }
    }

    private void updateControls() {
        boolean idle = !connected && !connecting;
        for (JComponent c : new JComponent[] { userField, passField, hostField, portField, udpField }) c.setEnabled(idle);
        loginBtn.setEnabled(idle);
        logoutBtn.setEnabled(connected);
        presenceBox.setEnabled(connected);
        refreshBtn.setEnabled(connected);
        newConvBtn.setEnabled(connected);
        String text = compose.getText();
        int n = Protocol.charCount(text);
        counter.setText(n + "/" + MAX);
        counter.setForeground(n > MAX ? new Color(0xB00020) : Color.DARK_GRAY);
        boolean valid = !text.isBlank() && n <= MAX;
        sendBtn.setEnabled(connected && selectedPeer != null && valid);   // disabled: no recipient / not connected / empty / too long
        sendBtn.setToolTipText(!connected ? "Not connected" : selectedPeer == null ? "Select a recipient first" : !valid ? "Type a message (max " + MAX + " characters)" : null);
    }

    private void refreshContacts() {
        String filter = searchField.getText().trim().toLowerCase();
        TreeSet<String> names = new TreeSet<>(online.keySet());
        String me = client.username();
        if (me != null) names.remove(me);
        names.addAll(store.peers());
        names.addAll(extraPeers);
        adjusting = true;
        try {
            contactModel.clear();
            int sel = -1;
            for (String n : names) {
                if (!filter.isEmpty() && !n.toLowerCase().contains(filter)) continue;
                if (n.equals(selectedPeer)) sel = contactModel.size();
                contactModel.addElement(new Contact(n, online.get(n), store.unread(n)));
            }
            if (sel >= 0) contactList.setSelectedIndex(sel); else contactList.clearSelection();
        } finally { adjusting = false; }
    }

    private void log(String line) {
        logArea.append(line + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    // ------------------------------------------------------------------ bubbles

    private void rebuildBubbles() {
        bubbles.removeAll();
        statusLabels.clear();
        if (selectedPeer != null) for (ChatMessage m : store.history(selectedPeer)) bubbles.add(makeBubble(m));
        bubbles.revalidate(); bubbles.repaint();
        scrollDown();
    }

    private void appendBubble(ChatMessage m) {
        if (!m.peer.equals(selectedPeer)) return;
        bubbles.add(makeBubble(m));
        bubbles.revalidate(); bubbles.repaint();
        scrollDown();
    }

    private void scrollDown() {
        SwingUtilities.invokeLater(() -> { JScrollBar b = bubbleScroll.getVerticalScrollBar(); b.setValue(b.getMaximum()); });
    }

    private JComponent makeBubble(ChatMessage m) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
        JPanel bubble = new JPanel();
        bubble.setLayout(new BoxLayout(bubble, BoxLayout.Y_AXIS));
        bubble.setBackground(m.outgoing ? OUT_BG : IN_BG);
        bubble.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(m.outgoing ? new Color(0xA9CBEF) : new Color(0xD3DAE2)), BorderFactory.createEmptyBorder(5, 8, 5, 8)));

        JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        head.setOpaque(false);
        String who = m.outgoing ? "You \u2192 " + m.recipient : m.sender + " \u2192 You";
        JLabel who1 = new JLabel(who + "  |  " + hms.format(new Date(m.timestamp)));
        who1.setFont(who1.getFont().deriveFont(Font.PLAIN, 11f));
        who1.setForeground(Color.DARK_GRAY);
        JLabel badge = new JLabel(" " + m.transport.badge() + " ");
        badge.setOpaque(true);
        badge.setForeground(Color.WHITE);
        badge.setBackground(m.transport == Transport.UDP ? new Color(0x2E7D6B) : new Color(0x2563A8));
        badge.setFont(badge.getFont().deriveFont(Font.BOLD, 10f));
        head.add(who1); head.add(badge);
        head.setAlignmentX(Component.LEFT_ALIGNMENT);

        JTextArea t = new JTextArea(m.text);
        t.setEditable(false); t.setLineWrap(true); t.setWrapStyleWord(true); t.setBorder(null);
        t.setBackground(m.outgoing ? OUT_BG : IN_BG);   // same colour as the bubble (Nimbus ignores setOpaque(false) on text areas)
        t.setComponentOrientation(isRtl(m.text) ? ComponentOrientation.RIGHT_TO_LEFT : ComponentOrientation.LEFT_TO_RIGHT);
        FontMetrics fm = t.getFontMetrics(t.getFont());
        int w = 150;
        for (String line : m.text.split("\n", -1)) w = Math.max(w, fm.stringWidth(line) + 14);
        w = Math.min(w, 380);
        t.setSize(w, Short.MAX_VALUE);
        t.setPreferredSize(new Dimension(w, t.getPreferredSize().height));
        t.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel st = new JLabel();
        st.setFont(st.getFont().deriveFont(Font.BOLD, 11f));
        st.setAlignmentX(Component.LEFT_ALIGNMENT);
        applyStatus(st, m);
        statusLabels.put(m.id, st);

        bubble.add(head); bubble.add(t); bubble.add(st);
        row.add(bubble, m.outgoing ? BorderLayout.EAST : BorderLayout.WEST);
        return row;
    }

    private static boolean isRtl(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (Character.isLetter(cp)) { byte d = Character.getDirectionality(cp); return d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC; }
            i += Character.charCount(cp);
        }
        return false;
    }

    private static void applyStatus(JLabel l, ChatMessage m) {
        String text; Color c;
        switch (m.status()) {
            case PENDING: text = "\u23F3 Pending"; c = Color.GRAY; break;
            case SENT: text = "\u2713 Sent - " + m.detail(); c = new Color(0x2563A8); break;
            case ACKNOWLEDGED: text = "\u2713\u2713 Acknowledged - " + m.detail(); c = new Color(0x1B7F4B); break;
            case RECEIVED: text = "Received - " + m.detail(); c = new Color(0x555555); break;
            case TIMEOUT: text = "\u231B Timeout - " + m.detail(); c = new Color(0xC77700); break;
            default: text = "\u2717 " + m.detail(); c = new Color(0xB00020);
        }
        l.setText("<html><body style='width:340px'>" + escape(text) + "</body></html>");
        l.setForeground(c);
    }

    private static String escape(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    // ------------------------------------------------------------------ MessengerListener (network threads -> EDT)

    private static void ui(Runnable r) { if (SwingUtilities.isEventDispatchThread()) r.run(); else SwingUtilities.invokeLater(r); }

    @Override public void onTcpState(TcpState state, String detail) {
        ui(() -> {
            tcpState.setText(state + (detail == null || detail.isEmpty() ? "" : "  (" + detail + ")"));
            tcpState.setForeground(state == TcpState.CONNECTED ? new Color(0x1B7F4B) : state == TcpState.CONNECTING ? Color.DARK_GRAY : new Color(0xB00020));
            connected = state == TcpState.CONNECTED;
            if (state == TcpState.CONNECTING) connecting = true;
            if (state == TcpState.DISCONNECTED) {
                connecting = false;
                online.clear();
                if (detail != null && !"Logged out".equals(detail)) { banner.setText("Disconnected: " + detail); loginStatus.setForeground(new Color(0xB00020)); loginStatus.setText(detail); }
                else loginStatus.setText("Logged out");
                refreshContacts(); updateHeader();
            }
            updateControls();
        });
    }

    @Override public void onUdpState(String text, int localPort) {
        ui(() -> { udpState.setText(text); localUdpField.setText(localPort > 0 ? String.valueOf(localPort) : "-"); });
    }

    @Override public void onUsers(List<UserInfo> users) {
        ui(() -> {
            online.clear();
            for (UserInfo u : users) online.put(u.username(), u);
            UserInfo me = online.get(client.username());
            if (me != null) { adjusting = true; presenceBox.setSelectedItem(me.presence().label()); adjusting = false; }
            refreshContacts();
            updateHeader();       // keeps the selected contact consistent with the new directory
            updateControls();
        });
    }

    @Override public void onIncoming(ChatMessage m) {
        ui(() -> {
            store.add(m);
            if (m.peer.equals(selectedPeer)) appendBubble(m); else store.incrementUnread(m.peer);
            refreshContacts();
            log(Protocol.now() + "  Received " + m.transport.badge() + " message from " + m.sender);
        });
    }

    @Override public void onStatus(String id, MsgStatus st, String detail) {
        ui(() -> {
            ChatMessage m = store.find(id);
            if (m == null) return;
            m.setStatus(st, detail);
            JLabel l = statusLabels.get(id);
            if (l != null) { applyStatus(l, m); l.revalidate(); l.repaint(); bubbles.revalidate(); }
            if (st == MsgStatus.FAILED || st == MsgStatus.TIMEOUT) log(Protocol.now() + "  " + m.transport.badge() + " to " + m.peer + ": " + detail);
        });
    }

    @Override public void onLog(String line) { ui(() -> log(line)); }

    @Override public void onAnnouncement(String text) { ui(() -> { banner.setText("Server: " + text); log(Protocol.now() + "  SERVER ANNOUNCEMENT: " + text); }); }

    @Override public void onLoginResult(boolean ok, String message, int udpPort) {
        ui(() -> {
            connecting = false;
            loginStatus.setForeground(ok ? new Color(0x1B7F4B) : new Color(0xB00020));
            loginStatus.setText(message);
            if (ok) { passField.setText(""); banner.setText(" "); }
            updateControls();
        });
    }

    // ------------------------------------------------------------------ list renderer

    private final class ContactRenderer implements ListCellRenderer<Contact> {
        @Override public Component getListCellRendererComponent(JList<? extends Contact> list, Contact c, int index, boolean selected, boolean focus) {
            JPanel p = new JPanel(new BorderLayout(8, 0));
            p.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            p.setBackground(selected ? new Color(0xD9E8F7) : Color.WHITE);
            final Color dot = c.info() == null ? Color.LIGHT_GRAY : switch (c.info().presence()) { case ONLINE -> new Color(0x2EAD6B); case BUSY -> new Color(0xD64545); case AWAY -> new Color(0xF0B040); };
            JComponent d = new JComponent() {
                @Override protected void paintComponent(Graphics g) { g.setColor(dot); g.fillOval(0, 4, 12, 12); }
            };
            d.setPreferredSize(new Dimension(14, 20));
            JLabel name = new JLabel(c.name());
            name.setFont(name.getFont().deriveFont(Font.BOLD));
            JLabel sub = new JLabel(c.info() == null ? "Offline" : c.info().presence().label() + "  |  UDP ready");
            sub.setFont(sub.getFont().deriveFont(Font.PLAIN, 11f));
            sub.setForeground(Color.GRAY);
            JPanel mid = new JPanel(new java.awt.GridLayout(2, 1));
            mid.setOpaque(false);
            mid.add(name); mid.add(sub);
            p.add(d, BorderLayout.WEST); p.add(mid, BorderLayout.CENTER);
            if (c.unread() > 0) {
                JLabel badge = new JLabel(" " + c.unread() + " ", JLabel.CENTER);
                badge.setOpaque(true); badge.setBackground(new Color(0xD64545)); badge.setForeground(Color.WHITE);
                badge.setFont(badge.getFont().deriveFont(Font.BOLD, 11f));
                JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 10));
                east.setOpaque(false); east.add(badge);
                p.add(east, BorderLayout.EAST);
            }
            return p;
        }
    }
}
