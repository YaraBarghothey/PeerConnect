/* PeerConnect - authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 */
package com.peerconnect.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Thin typed wrapper over a JSON object: one TCP frame (or the body of a UDP datagram). */
public final class Msg {
    private final Map<String, Object> map;

    private Msg(Map<String, Object> map) { this.map = map; }

    public static Msg of() { return new Msg(new LinkedHashMap<>()); }

    public static Msg parse(String line) throws Json.JsonException { return new Msg(Json.parseObject(line)); }

    public Msg put(String k, Object v) { map.put(k, v); return this; }

    public Map<String, Object> asMap() { return map; }

    /** Returns the string value, or null when the key is missing or not a string. */
    public String getString(String k) {
        Object o = map.get(k);
        return (o instanceof String) ? (String) o : null;
    }

    public long getLong(String k, long def) {
        Object o = map.get(k);
        return (o instanceof Long) ? (Long) o : def;
    }

    public boolean getBool(String k) { return Boolean.TRUE.equals(map.get(k)); }

    public List<?> getList(String k) {
        Object o = map.get(k);
        return (o instanceof List) ? (List<?>) o : null;
    }

    public boolean has(String k) { return map.containsKey(k); }

    public String toJson() { return Json.stringify(map); }

    @Override public String toString() { return "Msg(" + String.valueOf(map.get("type")) + "/" + String.valueOf(map.get("cmd")) + ")"; }
}
