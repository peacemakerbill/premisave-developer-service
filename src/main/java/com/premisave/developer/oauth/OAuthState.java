package com.premisave.developer.oauth;

import com.premisave.developer.common.DeveloperException;
import com.premisave.developer.common.FlatJson;
import com.premisave.developer.common.Hashing;
import com.premisave.developer.common.Ids;
import com.premisave.developer.enums.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The state value of an authorization request. It is signed and short lived, and says which provider and which return
 * address it was made for, so a code obtained for one request cannot be replayed into another. It needs no server side
 * storage. The portal must also keep the state it was given and compare it with the one the provider returns: that
 * is what ties the sign in to the browser that started it.
 */
public final class OAuthState {

    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private final byte[] key;
    private final long ttlSeconds;
    private final Clock clock;

    public OAuthState(String secret, int ttlMinutes, Clock clock) {
        // A key of its own, so a state value can never be mistaken for a sign in token.
        this.key = Hashing.hmacSha256(secret.getBytes(StandardCharsets.UTF_8), "oauth-state-v1".getBytes(StandardCharsets.UTF_8));
        this.ttlSeconds = ttlMinutes * 60L;
        this.clock = clock;
    }

    public String create(OAuthProvider provider, String redirectUri) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("p", provider.key());
        p.put("r", digest(redirectUri));
        p.put("n", Ids.randomToken(12));
        p.put("exp", clock.instant().getEpochSecond() + ttlSeconds);
        String body = ENC.encodeToString(FlatJson.write(p).getBytes(StandardCharsets.UTF_8));
        return body + "." + ENC.encodeToString(Hashing.hmacSha256(key, body.getBytes(StandardCharsets.UTF_8)));
    }

    /** Throws OAUTH_FAILED unless the state is genuine, current, and made for this provider and return address. */
    public void verify(String state, OAuthProvider provider, String redirectUri) {
        try {
            if (state == null) throw bad();
            String[] parts = state.split("\\.", -1);
            if (parts.length != 2) throw bad();
            byte[] expected = Hashing.hmacSha256(key, parts[0].getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(expected, DEC.decode(parts[1]))) throw bad();
            Map<String, Object> p = FlatJson.read(new String(DEC.decode(parts[0]), StandardCharsets.UTF_8));
            if (!provider.key().equals(p.get("p"))) throw bad();
            if (!digest(redirectUri).equals(p.get("r"))) throw bad();
            Object exp = p.get("exp");
            if (!(exp instanceof Number n) || clock.instant().getEpochSecond() >= n.longValue()) throw bad();
        } catch (DeveloperException e) {
            throw e;
        } catch (RuntimeException e) {
            throw bad();
        }
    }

    private static String digest(String redirectUri) {
        return Hashing.sha256Hex(redirectUri.getBytes(StandardCharsets.UTF_8));
    }

    private static DeveloperException bad() {
        return new DeveloperException(ErrorCode.OAUTH_FAILED, "The sign in request is invalid or has expired. Start again.");
    }
}
