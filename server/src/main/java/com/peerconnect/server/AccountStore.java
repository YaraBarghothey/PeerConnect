/* PeerConnect - authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 */
package com.peerconnect.server;

import com.peerconnect.common.ConfigPaths;
import com.peerconnect.common.PasswordHasher;
import com.peerconnect.common.Protocol;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Registered accounts loaded from a local text file. One line per account:  username:pbkdf2$sha256$iter$salt$hash
 * Plaintext passwords are NEVER stored. Lines starting with '#' are comments.
 */
public final class AccountStore {
    private static final String DUMMY_HASH = PasswordHasher.hash("not-a-real-password");
    private final Map<String, String> hashes;

    private AccountStore(Map<String, String> hashes) { this.hashes = hashes; }

    public static AccountStore load(Path file) throws IOException {
        Map<String, String> m = new HashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int i = line.indexOf(':');
            if (i < 0) continue;
            String u = line.substring(0, i).trim();
            if (Protocol.isValidUsername(u)) m.put(u, line.substring(i + 1).trim());
        }
        return new AccountStore(m);
    }

    /** Creates the demo accounts file when it does not exist (documented demo passwords: see README). */
    public static void ensureDemoAccounts(Path file) throws IOException {
        if (Files.exists(file)) return;
        writeDemo(file);
    }

    public static final String[] DEMO_USERS = { "sara", "adam", "ali", "aws", "saly", "omar" };

    public static void writeDemo(Path file) throws IOException {
        StringBuilder sb = new StringBuilder("# PeerConnect demo accounts (classroom use only). Format: username:pbkdf2$sha256$iterations$salt$hash\n");
        for (String u : DEMO_USERS) sb.append(u).append(':').append(PasswordHasher.hash(u + "123")).append('\n');
        ConfigPaths.writeText(file, sb.toString());
    }

    /** Adds or replaces one account (used by AccountTool). */
    public static void addAccount(Path file, String user, String password) throws IOException {
        if (!Protocol.isValidUsername(user)) throw new IllegalArgumentException("Invalid username");
        List<String> keep = new ArrayList<>();
        if (Files.exists(file)) for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) if (!l.startsWith(user + ":")) keep.add(l);
        keep.add(user + ":" + PasswordHasher.hash(password));
        ConfigPaths.writeText(file, String.join("\n", keep) + "\n");
    }

    /** Verifies credentials; always performs one PBKDF2 derivation so unknown users cost the same time. */
    public boolean verify(String user, String password) {
        String stored = hashes.get(user);
        if (stored == null) { PasswordHasher.verify(password, DUMMY_HASH); return false; }
        return PasswordHasher.verify(password, stored);
    }

    public int size() { return hashes.size(); }
    public TreeSet<String> usernames() { return new TreeSet<>(hashes.keySet()); }
}
