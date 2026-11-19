package com.premisave.developer.repository;

import com.premisave.developer.model.OAuthIdentity;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface OAuthIdentityRepository extends MongoRepository<OAuthIdentity, String> {

    long deleteByDeveloperId(String developerId);

    List<OAuthIdentity> findByDeveloperId(String developerId);
}
