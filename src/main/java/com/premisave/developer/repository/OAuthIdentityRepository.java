package com.premisave.developer.repository;

import com.premisave.developer.model.OAuthIdentity;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface OAuthIdentityRepository extends MongoRepository<OAuthIdentity, String> {
}
