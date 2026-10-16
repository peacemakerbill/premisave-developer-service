package com.premisave.developer.key;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ApiKeyRepository extends MongoRepository<ApiKey, String> {

    List<ApiKey> findByAppId(String appId);
}
