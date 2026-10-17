package com.premisave.developer.repository;

import com.premisave.developer.enums.DeveloperStatus;
import com.premisave.developer.model.Developer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.Optional;

public interface DeveloperRepository extends MongoRepository<Developer, String> {

    Optional<Developer> findByEmail(String email);

    Optional<Developer> findByUsername(String username);

    boolean existsByUsername(String username);

    Page<Developer> findByStatus(DeveloperStatus status, Pageable pageable);

    /** pattern is already escaped; it is matched anywhere in the email, username, full name or company, ignoring case. */
    @Query("{ $or: [ { _id: ?1 }, { email: { $regex: ?0, $options: 'i' } }, { username: { $regex: ?0, $options: 'i' } },"
            + " { fullName: { $regex: ?0, $options: 'i' } }, { companyName: { $regex: ?0, $options: 'i' } } ] }")
    Page<Developer> search(String pattern, String developerId, Pageable pageable);

    @Query("{ status: ?2, $or: [ { _id: ?1 }, { email: { $regex: ?0, $options: 'i' } }, { username: { $regex: ?0, $options: 'i' } },"
            + " { fullName: { $regex: ?0, $options: 'i' } }, { companyName: { $regex: ?0, $options: 'i' } } ] }")
    Page<Developer> searchByStatus(String pattern, String developerId, DeveloperStatus status, Pageable pageable);
}
