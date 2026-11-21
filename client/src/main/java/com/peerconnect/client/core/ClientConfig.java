/* PeerConnect - authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 */
package com.peerconnect.client.core;

import com.peerconnect.common.ConfigPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Client settings from config/client.properties (defaults are created when the file is missing). */
public final class ClientConfig {
    public String serverHost = "127.0.0.1";
    public int serverPort = 8888;
    public int udpPortBase = 6000;        // auto-selection tries base, base+1, ... then falls back to an OS-chosen port
    public int pingIntervalMs = 5000;
    public int serverTimeoutMs = 15000;   // no frame from the server for this long => connection considered dead
    public int connectTimeoutMs = 5000;
    public int requestTimeoutMs = 5000;
    public String defaultUsername = "";

    public static final String DEFAULT_PROPERTIES =
            "# PeerConnect client configuration\n"
            + "# serverHost: IP of the PC running the server (127.0.0.1 = same PC, LAN IP, or Tailscale/ZeroTier IP)\n"
            + "serverHost=127.0.0.1\nserverPort=8888\n"
            + "# first UDP port tried for P2P chat; the next free one is used if several clients run on the same PC\n"
            + "udpPortBase=6000\npingIntervalSeconds=5\nserverTimeoutSeconds=15\ndefaultUsername=\n";

    public static ClientConfig load(Path dir) {
        ClientConfig c = new ClientConfig();
        try {
            Path f = dir.resolve("client.properties");
            if (!Files.exists(f)) ConfigPaths.writeText(f, DEFAULT_PROPERTIES);
            Properties p = ConfigPaths.load(f);
            c.serverHost = p.getProperty("serverHost", c.serverHost).trim();
            c.serverPort = ConfigPaths.intProp(p, "serverPort", c.serverPort);
            c.udpPortBase = ConfigPaths.intProp(p, "udpPortBase", c.udpPortBase);
            c.pingIntervalMs = 1000 * ConfigPaths.intProp(p, "pingIntervalSeconds", 5);
            c.serverTimeoutMs = 1000 * ConfigPaths.intProp(p, "serverTimeoutSeconds", 15);
            c.defaultUsername = p.getProperty("defaultUsername", "").trim();
        } catch (IOException | RuntimeException e) {
            System.err.println("client.properties not readable (" + e.getMessage() + "); using built-in defaults");
        }
        return c;
    }
}
