package com.premisave.developer.model;

import com.premisave.developer.enums.Environment;
import com.premisave.developer.enums.KeyStatus;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A key and secret pair for one app. The key (public, like pk_sandbox_...) is the id; the secret is shown once when
 * it is issued and only a keyed hash of it is stored.
 */
@Document(collection = "api_keys")
public class ApiKey {

    @Id
    private String key;

    @Indexed
    private String appId;
    private String developerId;
    private Environment environment;
    private String label;

    private String secretHash;
    /** Last four characters of the secret, to help a person tell keys apart. Never enough to use the secret. */
    private String secretHint;

    private KeyStatus status = KeyStatus.ACTIVE;
    private Instant expiresAt;
    private Instant createdAt;
    private Instant lastUsedAt;
    private Instant revokedAt;

    public ApiKey() {}

    /** Whether the key is accepted at the given time. */
    public boolean usableAt(Instant now) {
        return switch (status) {
            case ACTIVE -> true;
            case ROTATING -> expiresAt != null && now.isBefore(expiresAt);
            case REVOKED -> false;
        };
    }

    public String getKey() { return key; }
    public void setKey(String v) { this.key = v; }
    public String getAppId() { return appId; }
    public void setAppId(String v) { this.appId = v; }
    public String getDeveloperId() { return developerId; }
    public void setDeveloperId(String v) { this.developerId = v; }
    public Environment getEnvironment() { return environment; }
    public void setEnvironment(Environment v) { this.environment = v; }
    public String getLabel() { return label; }
    public void setLabel(String v) { this.label = v; }
    public String getSecretHash() { return secretHash; }
    public void setSecretHash(String v) { this.secretHash = v; }
    public String getSecretHint() { return secretHint; }
    public void setSecretHint(String v) { this.secretHint = v; }
    public KeyStatus getStatus() { return status; }
    public void setStatus(KeyStatus v) { this.status = v; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { this.expiresAt = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant v) { this.lastUsedAt = v; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant v) { this.revokedAt = v; }
}
