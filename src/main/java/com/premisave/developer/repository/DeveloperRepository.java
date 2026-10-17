package com.premisave.developer.repository;

import com.premisave.developer.model.Developer;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface DeveloperRepository extends MongoRepository<Developer, String> {

    Optional<Developer> findByEmail(String email);

    Optional<Developer> findByUsername(String username);

    boolean existsByUsername(String username);
}
