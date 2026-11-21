/* PeerConnect - authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 */
package com.peerconnect.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/** Locates the /config folder WITHOUT absolute paths: -Dpeerconnect.config, ./config, ../config, or next to the JAR. */
public final class ConfigPaths {
    private ConfigPaths() {}

    public static Path configDir() {
        String override = System.getProperty("peerconnect.config");
        if (override != null && !override.isBlank()) return Paths.get(override);
        Path cwd = Paths.get("").toAbsolutePath();
        Path[] candidates = { cwd.resolve("config"), cwd.resolve("..").resolve("config") };
        for (Path c : candidates) if (Files.isDirectory(c)) return c.normalize();
        try {
            Path jar = Paths.get(ConfigPaths.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path dir = Files.isDirectory(jar) ? jar : jar.getParent();
            for (Path c : new Path[] { dir.resolve("config"), dir.resolve("..").resolve("config"), dir.resolve("..").resolve("..").resolve("config") })
                if (Files.isDirectory(c)) return c.normalize();
        } catch (Exception ignored) { /* fall through */ }
        return cwd.resolve("config");
    }

    public static Properties load(Path file) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) { p.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)); }
        return p;
    }

    public static void writeText(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file)) { out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    }

    public static int intProp(Properties p, String key, int def) {
        try { return Integer.parseInt(p.getProperty(key, String.valueOf(def)).trim()); } catch (NumberFormatException e) { return def; }
    }
}
