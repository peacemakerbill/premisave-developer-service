package com.premisave.developer.repository;

import com.premisave.developer.model.RevokedToken;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface RevokedTokenRepository extends MongoRepository<RevokedToken, String> {
}
