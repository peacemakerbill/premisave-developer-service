package com.premisave.developer.model;

import com.premisave.developer.enums.OAuthProvider;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** A developer's account at a sign in provider. The id is the provider and the provider's own subject, so it is unique. */
@Document(collection = "oauth_identities")
public class OAuthIdentity {

    @Id
    private String id;

    @Indexed
    private String developerId;

    private String provider;
    private String email;
    private Instant linkedAt;

    public OAuthIdentity() {}

    public static String idOf(OAuthProvider provider, String subject) {
        return provider.key() + ":" + subject;
    }

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getDeveloperId() { return developerId; }
    public void setDeveloperId(String v) { this.developerId = v; }
    public String getProvider() { return provider; }
    public void setProvider(String v) { this.provider = v; }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }
    public Instant getLinkedAt() { return linkedAt; }
    public void setLinkedAt(Instant v) { this.linkedAt = v; }
}
