/* PeerConnect - authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 */
package com.peerconnect.server;

import com.peerconnect.common.UserInfo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe directory of logged-in users. The two critical operations are single atomic calls on the
 * ConcurrentHashMap: putIfAbsent (duplicate-login check + registration) and remove(key, value) (remove ONLY
 * if the user is still mapped to THIS session, so an old cleanup never deletes a newer session).
 */
final class UserDirectory {
    private final ConcurrentHashMap<String, ClientSession> map = new ConcurrentHashMap<>();

    boolean tryRegister(ClientSession s) { return map.putIfAbsent(s.username, s) == null; }

    boolean remove(ClientSession s) { return s.username != null && map.remove(s.username, s); }

    ClientSession get(String username) { return map.get(username); }

    List<ClientSession> sessions() { return new ArrayList<>(map.values()); }

    int size() { return map.size(); }

    void clear() { map.clear(); }

    List<UserInfo> snapshot() {
        List<UserInfo> l = new ArrayList<>();
        for (ClientSession s : map.values()) l.add(s.toUserInfo());
        l.sort(Comparator.comparing(UserInfo::username));
        return l;
    }
}
