package com.premisave.developer.security;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Password hashing with PBKDF2 (HMAC-SHA256) from the JDK, a fresh 16 byte salt per password.
 * Stored form: pbkdf2-sha256$iterations$salt$hash (salt and hash in base64).
 */
public final class PasswordHasher {

    private static final String PREFIX = "pbkdf2-sha256";
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final int iterations;

    public PasswordHasher(int iterations) {
        this.iterations = iterations;
    }

    public String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] h = derive(password, salt, iterations);
        return PREFIX + "$" + iterations + "$" + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(h);
    }

    public boolean verify(String password, String stored) {
        if (password == null || stored == null) return false;
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !parts[0].equals(PREFIX)) return false;
        try {
            int it = Integer.parseInt(parts[1]);
            if (it < 1) return false;
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            return MessageDigest.isEqual(expected, derive(password, salt, it));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** True when the stored hash was made with fewer iterations than the current setting. */
    public boolean needsRehash(String stored) {
        try {
            return Integer.parseInt(stored.split("\\$")[1]) < iterations;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Password hashing is not available", e);
        }
    }
}
