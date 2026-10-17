package com.premisave.developer.security;

import com.premisave.developer.TestProps;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.enums.OAuthProvider;
import com.premisave.developer.exception.DeveloperException;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OAuthStateTest {

    private static final String URI = "https://portal.example.com/auth/callback";

    private Instant now = Instant.parse("2026-10-16T08:00:00Z");
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };
    private final OAuthState states = new OAuthState(TestProps.JWT_SECRET, 10, clock);

    private static void refused(Runnable r) {
        assertEquals(ErrorCode.OAUTH_FAILED, assertThrows(DeveloperException.class, r::run).code());
    }

    @Test
    void aStateWorksForItsOwnProviderAndReturnAddress() {
        String s = states.create(OAuthProvider.GITHUB, URI);
        states.verify(s, OAuthProvider.GITHUB, URI);
    }

    @Test
    void everyStateIsDifferent() {
        assertNotEquals(states.create(OAuthProvider.GITHUB, URI), states.create(OAuthProvider.GITHUB, URI));
    }

    @Test
    void itCannotBeUsedForAnotherProviderOrAnotherReturnAddress() {
        String s = states.create(OAuthProvider.GITHUB, URI);
        refused(() -> states.verify(s, OAuthProvider.GOOGLE, URI));
        refused(() -> states.verify(s, OAuthProvider.GITHUB, "https://evil.example.com/cb"));
    }

    @Test
    void itExpires() {
        String s = states.create(OAuthProvider.GOOGLE, URI);
        now = now.plusSeconds(599);
        states.verify(s, OAuthProvider.GOOGLE, URI);
        now = now.plusSeconds(1);
        refused(() -> states.verify(s, OAuthProvider.GOOGLE, URI));
    }

    @Test
    void tamperingAndGarbageAreRefused() {
        String s = states.create(OAuthProvider.GOOGLE, URI);
        String[] parts = s.split("\\.");
        refused(() -> states.verify(parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "AA", OAuthProvider.GOOGLE, URI));
        refused(() -> states.verify(parts[0] + "x." + parts[1], OAuthProvider.GOOGLE, URI));
        refused(() -> states.verify(null, OAuthProvider.GOOGLE, URI));
        refused(() -> states.verify("", OAuthProvider.GOOGLE, URI));
        refused(() -> states.verify("a.b.c", OAuthProvider.GOOGLE, URI));
        refused(() -> states.verify("!!!.???", OAuthProvider.GOOGLE, URI));
    }

    @Test
    void aStateFromAnotherSecretIsRefused() {
        OAuthState other = new OAuthState("a completely different secret 0123456789", 10, clock);
        refused(() -> states.verify(other.create(OAuthProvider.GOOGLE, URI), OAuthProvider.GOOGLE, URI));
    }

    @Test
    void aSignInTokenIsNotAState() {
        com.premisave.developer.security.JwtService jwt = new com.premisave.developer.security.JwtService(TestProps.JWT_SECRET, "iss", 60, clock);
        refused(() -> states.verify(jwt.issue("dev_1", 0), OAuthProvider.GOOGLE, URI));
    }
}
