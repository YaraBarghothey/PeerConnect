/* PeerConnect - authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 */
package com.peerconnect.common;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/** Protocol constants and the validation rules shared by client and server. */
public final class Protocol {
    private Protocol() {}

    public static final int VERSION = 1;
    /** Assignment limit: 1,000 characters. We count Unicode CODE POINTS, never UTF-8 bytes. */
    public static final int MAX_TEXT_CHARS = 1000;
    /** Max size of one TCP frame (one JSON line) in bytes. */
    public static final int MAX_FRAME_BYTES = 32 * 1024;
    /** UDP receive buffer. 1000 chars with worst-case JSON escaping (6 bytes/char) + envelope < 8192. */
    public static final int MAX_DATAGRAM_BYTES = 8192;
    public static final int MAX_ID_LEN = 80;

    // ----- TCP commands (client -> server)
    public static final String CMD_LOGIN = "LOGIN", CMD_LOGOUT = "LOGOUT", CMD_USERS = "USERS", CMD_PRESENCE = "PRESENCE",
            CMD_PEER = "PEER", CMD_CHAT_TCP = "CHAT_TCP", CMD_PING = "PING";
    // ----- frame types (server -> client)
    public static final String T_RESPONSE = "RESPONSE", T_EVENT = "EVENT";
    // ----- events (server -> client)
    public static final String EV_USERS_UPDATE = "USERS_UPDATE", EV_CHAT_TCP = "CHAT_TCP", EV_ANNOUNCEMENT = "ANNOUNCEMENT",
            EV_SERVER_SHUTDOWN = "SERVER_SHUTDOWN";
    // ----- error codes
    public static final String E_BAD_REQUEST = "BAD_REQUEST", E_BAD_CREDENTIALS = "BAD_CREDENTIALS", E_DUPLICATE_LOGIN = "DUPLICATE_LOGIN",
            E_NOT_LOGGED_IN = "NOT_LOGGED_IN", E_ALREADY_LOGGED_IN = "ALREADY_LOGGED_IN", E_OFFLINE = "OFFLINE",
            E_INVALID_TEXT = "INVALID_TEXT", E_UNKNOWN_COMMAND = "UNKNOWN_COMMAND", E_DELIVERY_FAILED = "DELIVERY_FAILED",
            E_FRAME_TOO_LARGE = "FRAME_TOO_LARGE", E_BAD_ENCODING = "BAD_ENCODING";

    public static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_.-]{2,20}");
    private static final Pattern MESSAGE_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,80}");
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static boolean isValidUsername(String s) { return s != null && USERNAME.matcher(s).matches(); }

    public static boolean isValidMessageId(String s) { return s != null && MESSAGE_ID.matcher(s).matches(); }

    public static int charCount(String s) { return s.codePointCount(0, s.length()); }

    /** @return null if the chat text is valid, otherwise a human-readable reason. */
    public static String validateText(String text) {
        if (text == null || text.isBlank()) return "Message is empty";
        int n = charCount(text);
        if (n > MAX_TEXT_CHARS) return "Message too long (" + n + " characters, limit " + MAX_TEXT_CHARS + ")";
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 0) return "Message contains a NUL character";
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) return "Message contains invalid Unicode";
                i++;
            } else if (Character.isLowSurrogate(c)) return "Message contains invalid Unicode";
        }
        return null;
    }

    /** Parses a TCP/UDP port (1-65535). @throws IllegalArgumentException with a friendly message. */
    public static int parsePort(String s, String what, boolean allowZero) {
        int p;
        try { p = Integer.parseInt(s == null ? "" : s.trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(what + " must be a number" + (allowZero ? " (0-65535)" : " (1-65535)")); }
        if (p < (allowZero ? 0 : 1) || p > 65535) throw new IllegalArgumentException(what + " must be between " + (allowZero ? 0 : 1) + " and 65535");
        return p;
    }

    /** Accepts an IPv4 literal (each octet 0-255) or a plain host name. */
    public static String validateHost(String h) {
        if (h == null || h.trim().isEmpty()) throw new IllegalArgumentException("Server IP/host is empty");
        h = h.trim();
        if (h.length() > 253 || !h.matches("[A-Za-z0-9.-]+")) throw new IllegalArgumentException("Invalid server IP/host: '" + h + "'");
        if (h.matches("[0-9.]+")) {
            String[] parts = h.split("\\.", -1);
            if (parts.length != 4) throw new IllegalArgumentException("Invalid IPv4 address: '" + h + "'");
            for (String p : parts) {
                if (p.isEmpty() || p.length() > 3 || Integer.parseInt(p) > 255) throw new IllegalArgumentException("Invalid IPv4 address: '" + h + "'");
            }
        }
        return h;
    }

    public static String now() { return LocalTime.now().format(HMS); }
}
