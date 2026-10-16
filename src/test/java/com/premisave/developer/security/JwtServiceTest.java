package com.premisave.developer.security;

import com.premisave.developer.TestProps;
import com.premisave.developer.common.FlatJson;
import com.premisave.developer.common.Hashing;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-16T08:00:00Z");
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();

    private static Clock at(Instant t) {
        return Clock.fixed(t, ZoneOffset.UTC);
    }

    private final JwtService jwt = new JwtService(TestProps.JWT_SECRET, "iss", 60, at(T0));

    @Test
    void roundTripCarriesTheDeveloperAndVersion() {
        Optional<JwtService.Claims> c = jwt.verify(jwt.issue("dev_1", 3));
        assertTrue(c.isPresent());
        assertEquals("dev_1", c.get().developerId());
        assertEquals(3, c.get().tokenVersion());
        assertEquals(T0.getEpochSecond() + 3600, c.get().expiresAt());
    }

    @Test
    void expiredTokensAreRejected() {
        String token = jwt.issue("dev_1", 0);
        JwtService later = new JwtService(TestProps.JWT_SECRET, "iss", 60, at(T0.plus(Duration.ofMinutes(61))));
        assertFalse(later.verify(token).isPresent());
        JwtService justBefore = new JwtService(TestProps.JWT_SECRET, "iss", 60, at(T0.plus(Duration.ofMinutes(59))));
        assertTrue(justBefore.verify(token).isPresent());
    }

    @Test
    void aTokenSignedWithAnotherSecretOrIssuerIsRejected() {
        String foreign = new JwtService("another-secret-another-secret-123456", "iss", 60, at(T0)).issue("dev_1", 0);
        assertFalse(jwt.verify(foreign).isPresent());
        String otherIssuer = new JwtService(TestProps.JWT_SECRET, "someone-else", 60, at(T0)).issue("dev_1", 0);
        assertFalse(jwt.verify(otherIssuer).isPresent());
    }

    @Test
    void aTamperedPayloadIsRejected() {
        String[] parts = jwt.issue("dev_1", 0).split("\\.");
        Map<String, Object> p = FlatJson.read(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        p.put("sub", "dev_2");
        String forged = parts[0] + "." + ENC.encodeToString(FlatJson.write(p).getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        assertFalse(jwt.verify(forged).isPresent());
    }

    @Test
    void theNoneAlgorithmIsNeverAccepted() {
        String header = ENC.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("iss", "iss");
        p.put("sub", "dev_1");
        p.put("ver", 0);
        p.put("iat", T0.getEpochSecond());
        p.put("exp", T0.getEpochSecond() + 3600);
        String payload = ENC.encodeToString(FlatJson.write(p).getBytes(StandardCharsets.UTF_8));
        assertFalse(jwt.verify(header + "." + payload + ".").isPresent());
        assertFalse(jwt.verify(header + "." + payload + ".AAAA").isPresent());
    }

    @Test
    void aValidSignatureOverAnotherHeaderIsStillRejected() {
        String header = ENC.encodeToString("{\"alg\":\"HS512\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = jwt.issue("dev_1", 0).split("\\.")[1];
        String sig = ENC.encodeToString(Hashing.hmacSha256(TestProps.JWT_SECRET.getBytes(StandardCharsets.UTF_8),
                (header + "." + payload).getBytes(StandardCharsets.UTF_8)));
        assertFalse(jwt.verify(header + "." + payload + "." + sig).isPresent());
    }

    @Test
    void garbageNeverThrows() {
        for (String s : new String[] {null, "", "a", "a.b", "a.b.c", "a.b.c.d", "...", "eyJ.eyJ.eyJ", " "}) {
            assertFalse(jwt.verify(s).isPresent());
        }
    }

    @Test
    void everyTokenHasItsOwnIdentifier() {
        JwtService.Claims a = jwt.verify(jwt.issue("dev_1", 1)).orElseThrow();
        JwtService.Claims b = jwt.verify(jwt.issue("dev_1", 1)).orElseThrow();
        assertFalse(a.tokenId().isBlank());
        assertTrue(a.tokenId().length() >= 16);
        assertFalse(a.tokenId().equals(b.tokenId()));
    }
}
