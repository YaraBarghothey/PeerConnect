/* PeerConnect - authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 */
package com.peerconnect.server;

import com.peerconnect.common.ConfigPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Server settings loaded from config/server.properties (created with documented defaults when missing). */
public final class ServerConfig {
    public String bindIp = "0.0.0.0";
    public int port = 8888;
    /** File name (relative to the config folder) or path of the accounts file. */
    public String accountsFile = "accounts.txt";
    /** A session with no frame for this long is considered stale and is removed. */
    public long timeoutMs = 15_000;
    public int outboxCapacity = 512;
    public int maxConnections = 500;

    public static final String DEFAULT_PROPERTIES =
            "# PeerConnect server configuration\n"
            + "# bindIp: 0.0.0.0 = all interfaces (LAN / Tailscale / ZeroTier), 127.0.0.1 = this PC only\n"
            + "bindIp=0.0.0.0\n"
            + "port=8888\n"
            + "# accounts file is looked up inside this config folder\n"
            + "accountsFile=accounts.txt\n"
            + "# heartbeat timeout in seconds (clients send PING every 5 s)\n"
            + "timeoutSeconds=15\n";

    public static ServerConfig load(Path configDir) throws IOException {
        Path f = configDir.resolve("server.properties");
        if (!Files.exists(f)) ConfigPaths.writeText(f, DEFAULT_PROPERTIES);
        Properties p = ConfigPaths.load(f);
        ServerConfig c = new ServerConfig();
        c.bindIp = p.getProperty("bindIp", c.bindIp).trim();
        c.port = ConfigPaths.intProp(p, "port", c.port);
        c.accountsFile = p.getProperty("accountsFile", c.accountsFile).trim();
        c.timeoutMs = 1000L * ConfigPaths.intProp(p, "timeoutSeconds", 15);
        return c;
    }
}
