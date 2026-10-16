package com.premisave.developer.auth;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface RevokedTokenRepository extends MongoRepository<RevokedToken, String> {
}
