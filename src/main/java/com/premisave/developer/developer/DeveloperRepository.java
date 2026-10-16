package com.premisave.developer.developer;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface DeveloperRepository extends MongoRepository<Developer, String> {

    Optional<Developer> findByEmail(String email);
}
