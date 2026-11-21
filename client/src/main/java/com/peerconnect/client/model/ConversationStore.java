/* PeerConnect - authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 */
package com.peerconnect.client.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** In-memory conversations: separate history per peer (last 200 messages, assignment minimum is 50) + unread counters. Thread-safe. */
public final class ConversationStore {
    public static final int MAX_PER_CONVERSATION = 200;
    private final Map<String, ArrayDeque<ChatMessage>> history = new HashMap<>();
    private final Map<String, Integer> unread = new HashMap<>();
    private final Map<String, ChatMessage> byId = new HashMap<>();

    public synchronized void add(ChatMessage m) {
        ArrayDeque<ChatMessage> q = history.computeIfAbsent(m.peer, k -> new ArrayDeque<>());
        q.addLast(m);
        byId.put(m.id, m);
        while (q.size() > MAX_PER_CONVERSATION) byId.remove(q.removeFirst().id);
    }
    public synchronized void ensureConversation(String peer) { history.computeIfAbsent(peer, k -> new ArrayDeque<>()); }
    public synchronized List<ChatMessage> history(String peer) {
        ArrayDeque<ChatMessage> q = history.get(peer);
        return q == null ? new ArrayList<>() : new ArrayList<>(q);
    }
    public synchronized ChatMessage find(String id) { return byId.get(id); }
    public synchronized TreeSet<String> peers() { return new TreeSet<>(history.keySet()); }
    public synchronized void incrementUnread(String peer) { unread.merge(peer, 1, Integer::sum); }
    public synchronized void clearUnread(String peer) { unread.remove(peer); }
    public synchronized int unread(String peer) { return unread.getOrDefault(peer, 0); }
    public synchronized void clear() { history.clear(); unread.clear(); byId.clear(); }
}
