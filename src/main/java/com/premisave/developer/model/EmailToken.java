package com.premisave.developer.model;

import com.premisave.developer.enums.EmailTokenType;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A single use link sent by email. Only a keyed hash of the token is stored, so a copy of the database cannot be used
 * to verify an address or take over an account. Expired rows are removed by the database.
 */
@Document(collection = "email_tokens")
public class EmailToken {

    @Id
    private String id;

    @Indexed(unique = true)
    private String tokenHash;

    @Indexed
    private String developerId;

    private EmailTokenType type;

    /** The address the link was sent to. A verification link only counts while the account still has this address. */
    private String email;

    private Instant createdAt;

    @Indexed(expireAfter = "0s")
    private Instant expiresAt;

    public EmailToken() {}

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String v) { this.tokenHash = v; }
    public String getDeveloperId() { return developerId; }
    public void setDeveloperId(String v) { this.developerId = v; }
    public EmailTokenType getType() { return type; }
    public void setType(EmailTokenType v) { this.type = v; }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { this.expiresAt = v; }
}
