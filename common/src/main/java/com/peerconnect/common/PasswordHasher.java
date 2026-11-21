/* PeerConnect - authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 */
package com.peerconnect.common;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PBKDF2-HMAC-SHA256 with a random 16-byte salt per account.
 * Stored format: pbkdf2$sha256$&lt;iterations&gt;$&lt;saltBase64&gt;$&lt;hashBase64&gt;  (all parameters are stored with the hash).
 */
public final class PasswordHasher {
    private PasswordHasher() {}
    public static final int DEFAULT_ITERATIONS = 120_000;
    private static final SecureRandom RNG = new SecureRandom();

    public static String hash(String password) { return hash(password, DEFAULT_ITERATIONS); }

    public static String hash(String password, int iterations) {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        byte[] h = derive(password, salt, iterations);
        return "pbkdf2$sha256$" + iterations + "$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(h);
    }

    public static boolean verify(String password, String stored) {
        try {
            String[] p = stored.split("\\$");
            if (p.length != 5 || !p[0].equals("pbkdf2") || !p[1].equals("sha256")) return false;
            int it = Integer.parseInt(p[2]);
            byte[] salt = Base64.getDecoder().decode(p[3]);
            byte[] expected = Base64.getDecoder().decode(p[4]);
            byte[] actual = derive(password, salt, it);
            return MessageDigest.isEqual(expected, actual); // constant-time comparison
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        try {
            KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 not available", e);
        }
    }
}
