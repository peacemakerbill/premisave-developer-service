package com.premisave.developer.security;

import com.premisave.developer.common.FlatJson;
import com.premisave.developer.common.Hashing;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Signs and checks HS256 access tokens using only the JDK. Only tokens whose header says HS256 are accepted
 * (never "none"), the signature is compared in constant time, and issuer and expiry are enforced.
 */
public final class JwtService {

    /** What a valid token says. */
    public record Claims(String developerId, int tokenVersion, long issuedAt, long expiresAt, String tokenId) {}

    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();
    private static final String HEADER = ENC.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private final byte[] secret;
    private final String issuer;
    private final long ttlSeconds;
    private final Clock clock;

    public JwtService(String secret, String issuer, int ttlMinutes) {
        this(secret, issuer, ttlMinutes, Clock.systemUTC());
    }

    public JwtService(String secret, String issuer, int ttlMinutes, Clock clock) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.issuer = issuer;
        this.ttlSeconds = ttlMinutes * 60L;
        this.clock = clock;
    }

    public long ttlSeconds() { return ttlSeconds; }

    public String issue(String developerId, int tokenVersion) {
        long now = clock.instant().getEpochSecond();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", issuer);
        payload.put("sub", developerId);
        payload.put("ver", tokenVersion);
        payload.put("jti", com.premisave.developer.common.Ids.randomToken(12));
        payload.put("iat", now);
        payload.put("exp", now + ttlSeconds);
        String body = HEADER + "." + ENC.encodeToString(FlatJson.write(payload).getBytes(StandardCharsets.UTF_8));
        return body + "." + ENC.encodeToString(Hashing.hmacSha256(secret, body.getBytes(StandardCharsets.UTF_8)));
    }

    public Optional<Claims> verify(String token) {
        if (token == null) return Optional.empty();
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) return Optional.empty();
        try {
            if (!HEADER.equals(parts[0])) return Optional.empty();
            byte[] expected = Hashing.hmacSha256(secret, (parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
            if (!java.security.MessageDigest.isEqual(expected, DEC.decode(parts[2]))) return Optional.empty();
            Map<String, Object> p = FlatJson.read(new String(DEC.decode(parts[1]), StandardCharsets.UTF_8));
            if (!issuer.equals(p.get("iss"))) return Optional.empty();
            Object sub = p.get("sub");
            if (!(sub instanceof String s) || s.isBlank()) return Optional.empty();
            long exp = number(p.get("exp"));
            long iat = number(p.get("iat"));
            if (clock.instant().getEpochSecond() >= exp) return Optional.empty();
            Object jti = p.get("jti");
            return Optional.of(new Claims(s, (int) number(p.get("ver")), iat, exp, jti instanceof String j ? j : ""));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static long number(Object o) {
        if (o instanceof Number n) return n.longValue();
        throw new IllegalArgumentException("not a number");
    }
}
