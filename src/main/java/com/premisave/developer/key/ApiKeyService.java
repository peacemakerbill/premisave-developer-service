package com.premisave.developer.key;

import com.premisave.developer.app.DeveloperApp;
import com.premisave.developer.common.DeveloperException;
import com.premisave.developer.common.Hashing;
import com.premisave.developer.common.Ids;
import com.premisave.developer.config.DeveloperProperties;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.enums.KeyStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Issues, rotates, revokes and checks API keys. A secret is generated once, handed back once, and stored only as an
 * HMAC-SHA256 under the configured pepper. A wrong key and a wrong secret look identical to the caller, and a
 * lookup that finds nothing still does the same hashing work so timing does not reveal which keys exist.
 */
@Service("developerApiKeyService")
public class ApiKeyService {

    /** A freshly issued key together with its secret, which is never available again. */
    public record Issued(ApiKey key, String secret) {}

    private static final Duration TOUCH_EVERY = Duration.ofMinutes(1);
    private static final String UNKNOWN_HASH = Hashing.hmacSha256Hex("unknown", "unknown");

    private final ApiKeyRepository keys;
    private final DeveloperProperties.Keys config;
    private final Clock clock;

    @Autowired
    public ApiKeyService(ApiKeyRepository keys, DeveloperProperties props) {
        this(keys, props.keys(), Clock.systemUTC());
    }

    public ApiKeyService(ApiKeyRepository keys, DeveloperProperties.Keys config, Clock clock) {
        this.keys = keys;
        this.config = config;
        this.clock = clock;
    }

    public Issued issue(DeveloperApp app, String label) {
        Instant now = clock.instant();
        long active = keys.findByAppId(app.getAppId()).stream().filter(k -> k.getStatus() == KeyStatus.ACTIVE).count();
        if (active >= config.maxActivePerApp()) {
            throw new DeveloperException(ErrorCode.LIMIT_REACHED,
                    "An app can have " + config.maxActivePerApp() + " active keys; revoke one first");
        }
        return create(app, label, now);
    }

    public List<ApiKey> list(String appId) {
        return keys.findByAppId(appId).stream()
                .sorted(Comparator.comparing(ApiKey::getCreatedAt).reversed()).toList();
    }

    /** Replaces a key. The old one keeps working for the grace period so the merchant can switch over without downtime. */
    public Issued rotate(DeveloperApp app, String keyId) {
        ApiKey old = require(app, keyId);
        if (old.getStatus() != KeyStatus.ACTIVE) {
            throw DeveloperException.invalidState("Only an active key can be rotated");
        }
        Instant now = clock.instant();
        Issued fresh = create(app, old.getLabel(), now);
        int graceHours = config.rotationGraceHours();
        if (graceHours == 0) {
            old.setStatus(KeyStatus.REVOKED);
            old.setRevokedAt(now);
        } else {
            old.setStatus(KeyStatus.ROTATING);
            old.setExpiresAt(now.plus(Duration.ofHours(graceHours)));
        }
        keys.save(old);
        return fresh;
    }

    public ApiKey revoke(DeveloperApp app, String keyId) {
        ApiKey k = require(app, keyId);
        if (k.getStatus() != KeyStatus.REVOKED) {
            k.setStatus(KeyStatus.REVOKED);
            k.setRevokedAt(clock.instant());
            keys.save(k);
        }
        return k;
    }

    /** Returns the key when the pair is valid and usable, otherwise throws the same INVALID_API_KEY for every reason. */
    public ApiKey authenticate(String clientKey, String clientSecret) {
        Instant now = clock.instant();
        ApiKey k = clientKey == null ? null : keys.findById(clientKey).orElse(null);
        String presented = hash(clientSecret == null ? "" : clientSecret);
        boolean match = Hashing.constantTimeEquals(presented, k == null ? UNKNOWN_HASH : k.getSecretHash());
        if (k == null || !match || !k.usableAt(now)) {
            throw new DeveloperException(ErrorCode.INVALID_API_KEY, "The API key or secret is not valid");
        }
        if (k.getLastUsedAt() == null || Duration.between(k.getLastUsedAt(), now).compareTo(TOUCH_EVERY) > 0) {
            k.setLastUsedAt(now);
            keys.save(k);
        }
        return k;
    }

    String hash(String secret) {
        return Hashing.hmacSha256Hex(config.pepper(), secret);
    }

    private ApiKey require(DeveloperApp app, String keyId) {
        return keys.findById(keyId).filter(k -> k.getAppId().equals(app.getAppId()))
                .orElseThrow(() -> new DeveloperException(ErrorCode.KEY_NOT_FOUND, "Key not found"));
    }

    private Issued create(DeveloperApp app, String label, Instant now) {
        String env = app.getEnvironment().pathSegment();
        String secret = "sk_" + env + "_" + Ids.randomToken(32);
        ApiKey k = new ApiKey();
        k.setKey("pk_" + env + "_" + Ids.randomToken(18));
        k.setAppId(app.getAppId());
        k.setDeveloperId(app.getDeveloperId());
        k.setEnvironment(app.getEnvironment());
        k.setLabel(label == null || label.isBlank() ? "Default" : label.trim());
        k.setSecretHash(hash(secret));
        k.setSecretHint(secret.substring(secret.length() - 4));
        k.setStatus(KeyStatus.ACTIVE);
        k.setCreatedAt(now);
        try {
            keys.insert(k);
        } catch (DuplicateKeyException e) {
            throw new DeveloperException(ErrorCode.INTERNAL_ERROR, "Could not issue a key, try again");
        }
        return new Issued(k, secret);
    }
}
