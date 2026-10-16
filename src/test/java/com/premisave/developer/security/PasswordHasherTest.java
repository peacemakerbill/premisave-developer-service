package com.premisave.developer.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher(10_000);

    @Test
    void correctPasswordVerifiesAndWrongOneDoesNot() {
        String stored = hasher.hash("correct horse battery");
        assertTrue(hasher.verify("correct horse battery", stored));
        assertFalse(hasher.verify("correct horse batterY", stored));
        assertFalse(hasher.verify("", stored));
    }

    @Test
    void sameWordGivesDifferentHashesBecauseOfTheSalt() {
        assertNotEquals(hasher.hash("same password"), hasher.hash("same password"));
    }

    @Test
    void storedFormatNamesTheIterations() {
        String stored = hasher.hash("whatever it is");
        assertTrue(stored.startsWith("pbkdf2-sha256$10000$"));
        assertFalse(stored.contains("whatever"));
    }

    @Test
    void malformedStoredValuesNeverVerify() {
        assertFalse(hasher.verify("x", null));
        assertFalse(hasher.verify("x", ""));
        assertFalse(hasher.verify("x", "plain-text"));
        assertFalse(hasher.verify("x", "pbkdf2-sha256$abc$AAAA$AAAA"));
        assertFalse(hasher.verify("x", "pbkdf2-sha256$10000$!!!$!!!"));
        assertFalse(hasher.verify("x", "md5$10000$AAAA$AAAA"));
    }

    @Test
    void olderHashesAreFlaggedForRehash() {
        String old = new PasswordHasher(10_000).hash("a password");
        assertTrue(new PasswordHasher(20_000).needsRehash(old));
        assertFalse(new PasswordHasher(10_000).needsRehash(old));
        assertTrue(hasher.needsRehash("garbage"));
        assertEquals(true, new PasswordHasher(20_000).verify("a password", old));
    }
}
