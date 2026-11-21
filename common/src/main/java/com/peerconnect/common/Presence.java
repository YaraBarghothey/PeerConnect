/* PeerConnect - authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 */
package com.peerconnect.common;

/** User presence as required by the assignment: Online / Busy / Away. */
public enum Presence {
    ONLINE("Online"), BUSY("Busy"), AWAY("Away");

    private final String label;
    Presence(String label) { this.label = label; }
    public String label() { return label; }

    /** Case-insensitive parse; returns null if the text is not a valid presence value. */
    public static Presence parse(String s) {
        if (s == null) return null;
        for (Presence p : values()) if (p.label.equalsIgnoreCase(s.trim())) return p;
        return null;
    }
}
