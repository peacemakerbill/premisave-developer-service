package com.premisave.developer.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** A sign in token that was ended by logging out. Kept only until the token would have expired anyway. */
@Document(collection = "revoked_tokens")
public class RevokedToken {

    @Id
    private String tokenId;

    private String developerId;

    @Indexed(expireAfter = "0s")
    private Instant expiresAt;

    public RevokedToken() {}

    public RevokedToken(String tokenId, String developerId, Instant expiresAt) {
        this.tokenId = tokenId;
        this.developerId = developerId;
        this.expiresAt = expiresAt;
    }

    public String getTokenId() { return tokenId; }
    public void setTokenId(String v) { this.tokenId = v; }
    public String getDeveloperId() { return developerId; }
    public void setDeveloperId(String v) { this.developerId = v; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { this.expiresAt = v; }
}
