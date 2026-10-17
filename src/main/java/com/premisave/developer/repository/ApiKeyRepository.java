package com.premisave.developer.repository;

import com.premisave.developer.model.ApiKey;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ApiKeyRepository extends MongoRepository<ApiKey, String> {

    List<ApiKey> findByAppId(String appId);

    long deleteByAppId(String appId);
}
