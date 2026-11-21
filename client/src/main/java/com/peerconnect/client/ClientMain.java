/* PeerConnect - authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 */
package com.peerconnect.client;

import com.peerconnect.client.core.ClientConfig;
import com.peerconnect.client.ui.ClientFrame;
import com.peerconnect.common.ConfigPaths;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * CLIENT ENTRY POINT.   java -jar peerconnect-client.jar [--user NAME] [--server HOST:PORT] [--title TEXT] [--config DIR]
 * Several clients can run on the same PC: each one automatically picks a different free UDP port.
 */
public final class ClientMain {
    public static void main(String[] args) {
        String user = null, title = null, server = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--user": user = args[++i]; break;
                case "--title": title = args[++i]; break;
                case "--server": server = args[++i]; break;
                case "--config": System.setProperty("peerconnect.config", args[++i]); break;
                default: System.err.println("Unknown option: " + args[i]);
            }
        }
        ClientConfig cfg = ClientConfig.load(ConfigPaths.configDir());   // creates documented defaults if missing
        if (server != null && server.contains(":")) {
            cfg.serverHost = server.substring(0, server.lastIndexOf(':'));
            try { cfg.serverPort = Integer.parseInt(server.substring(server.lastIndexOf(':') + 1)); } catch (NumberFormatException ignored) { }
        }
        try {
            boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
            if (win) UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            else for (UIManager.LookAndFeelInfo f : UIManager.getInstalledLookAndFeels()) if ("Nimbus".equals(f.getName())) UIManager.setLookAndFeel(f.getClassName());
        } catch (Exception ignored) { }
        final String u = user, t = (title != null ? title : (user != null ? "PeerConnect - " + user : null));
        SwingUtilities.invokeLater(() -> new ClientFrame(cfg, u, t).setVisible(true));
    }
}
