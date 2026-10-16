package com.premisave.developer.common;

import java.security.SecureRandom;
import java.util.Base64;

/** Prefixed random identifiers and the random parts of API keys. */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz".toCharArray();
    private static final int LENGTH = 12;

    private Ids() {}

    public static String next(String prefix) {
        char[] chars = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            chars[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return prefix + "_" + new String(chars);
    }

    public static String requestId() {
        return next("req");
    }

    /** URL safe random text from the given number of random bytes (no padding). */
    public static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static byte[] randomBytes(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return b;
    }
}
