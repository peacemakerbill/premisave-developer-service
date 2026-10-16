package com.premisave.developer.oauth;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface OAuthIdentityRepository extends MongoRepository<OAuthIdentity, String> {
}
