package com.premisave.developer.repository;

import com.premisave.developer.model.AdminAction;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

public interface AdminActionRepository extends MongoRepository<AdminAction, String> {

    /** Every pattern is already escaped. ".*" means any value. */
    @Query("{ actorId: { $regex: ?0 }, targetId: { $regex: ?1 }, action: { $regex: ?2 } }")
    Page<AdminAction> search(String actorPattern, String targetPattern, String actionPattern, Pageable pageable);
}
