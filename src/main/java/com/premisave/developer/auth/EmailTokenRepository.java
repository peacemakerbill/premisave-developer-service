package com.premisave.developer.auth;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.Optional;

public interface EmailTokenRepository extends MongoRepository<EmailToken, String> {

    Optional<EmailToken> findByTokenHash(String tokenHash);

    /** Removes the token and says how many rows went. Only the caller that gets 1 owns the token. */
    long deleteByTokenHash(String tokenHash);

    long deleteByDeveloperIdAndType(String developerId, EmailTokenType type);

    Optional<EmailToken> findFirstByDeveloperIdAndTypeOrderByCreatedAtDesc(String developerId, EmailTokenType type);

    long countByDeveloperIdAndTypeAndCreatedAtAfter(String developerId, EmailTokenType type, Instant after);
}
