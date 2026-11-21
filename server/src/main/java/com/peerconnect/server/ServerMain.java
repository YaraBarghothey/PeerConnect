/* PeerConnect - authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 */
package com.peerconnect.server;

import com.peerconnect.common.ConfigPaths;
import java.nio.file.Path;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * SERVER ENTRY POINT.   java -jar peerconnect-server.jar [--headless] [--bind IP] [--port N] [--config DIR]
 * Without --headless the Swing administration GUI opens (press START to listen).
 */
public final class ServerMain {
    public static void main(String[] args) throws Exception {
        boolean headless = false;
        String bind = null, port = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--headless": headless = true; break;
                case "--bind": bind = args[++i]; break;
                case "--port": port = args[++i]; break;
                case "--config": System.setProperty("peerconnect.config", args[++i]); break;
                default: System.err.println("Unknown option: " + args[i]);
            }
        }
        Path dir = ConfigPaths.configDir();
        ServerConfig cfg;
        AccountStore accounts;
        try {
            cfg = ServerConfig.load(dir);                       // creates documented defaults if missing
            Path acc = dir.resolve(cfg.accountsFile);
            AccountStore.ensureDemoAccounts(acc);               // creates demo accounts if missing
            accounts = AccountStore.load(acc);
            System.out.println("Config folder: " + dir.toAbsolutePath() + "  |  accounts loaded: " + accounts.size());
        } catch (Exception e) {
            System.err.println("Cannot load server configuration from " + dir.toAbsolutePath() + ": " + e.getMessage());
            System.exit(2);
            return;
        }
        if (bind != null) cfg.bindIp = bind;
        if (port != null) cfg.port = com.peerconnect.common.Protocol.parsePort(port, "port", false);

        if (headless) {
            final PeerConnectServer s = new PeerConnectServer(cfg, accounts, line -> System.out.println(line));
            try { s.start(); } catch (Exception e) { System.err.println("Cannot start server: " + e.getMessage()); System.exit(1); }
            Runtime.getRuntime().addShutdownHook(new Thread(s::stop));
            Thread.currentThread().join();
        } else {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
            final ServerConfig c = cfg; final AccountStore a = accounts;
            SwingUtilities.invokeLater(() -> new ServerFrame(c, a).setVisible(true));
        }
    }
}
