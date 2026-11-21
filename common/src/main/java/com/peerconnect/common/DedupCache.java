/* PeerConnect - authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 */
package com.peerconnect.common;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded (max entries) + expiring (TTL) set of already-seen keys; used to deduplicate UDP message IDs. */
public final class DedupCache {
    private final int maxEntries;
    private final long ttlMs;
    private final LinkedHashMap<String, Long> seen = new LinkedHashMap<>(); // insertion order == time order

    public DedupCache(int maxEntries, long ttlMs) { this.maxEntries = maxEntries; this.ttlMs = ttlMs; }

    /** @return true if the key is new (first time seen), false if it is a duplicate. */
    public synchronized boolean firstSeen(String key, long nowMs) {
        purge(nowMs);
        if (seen.containsKey(key)) return false;
        seen.put(key, nowMs);
        while (seen.size() > maxEntries) {
            Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
            it.next();
            it.remove();
        }
        return true;
    }

    public synchronized void purge(long nowMs) {
        Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            if (nowMs - it.next().getValue() > ttlMs) it.remove(); else break;
        }
    }

    public synchronized int size() { return seen.size(); }
}
