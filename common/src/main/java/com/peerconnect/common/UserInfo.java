/* PeerConnect - authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 */
package com.peerconnect.common;

import java.util.ArrayList;
import java.util.List;

/** One row of the server directory: username, IP, advertised UDP port, presence, last-seen (epoch ms). */
public record UserInfo(String username, String ip, int udpPort, Presence presence, long lastSeen) {

    public Msg toMsg() {
        return Msg.of().put("username", username).put("ip", ip).put("udpPort", (long) udpPort)
                .put("presence", presence.label()).put("lastSeen", lastSeen);
    }

    public static List<Msg> toMsgs(List<UserInfo> users) {
        List<Msg> l = new ArrayList<>();
        for (UserInfo u : users) l.add(u.toMsg());
        return l;
    }

    /** Parses a "users" JSON array; silently skips malformed entries (never throws). */
    public static List<UserInfo> parseList(List<?> raw) {
        List<UserInfo> out = new ArrayList<>();
        if (raw == null) return out;
        for (Object o : raw) {
            if (!(o instanceof java.util.Map)) continue;
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> m = (java.util.Map<String, Object>) o;
            Object n = m.get("username"), ip = m.get("ip"), port = m.get("udpPort"), pr = m.get("presence"), ls = m.get("lastSeen");
            if (!(n instanceof String) || !(ip instanceof String) || !(port instanceof Long) || !(pr instanceof String)) continue;
            Presence p = Presence.parse((String) pr);
            if (p == null || !Protocol.isValidUsername((String) n)) continue;
            out.add(new UserInfo((String) n, (String) ip, ((Long) port).intValue(), p, (ls instanceof Long) ? (Long) ls : 0L));
        }
        return out;
    }
}
