package com.premisave.developer.repository;

import com.premisave.developer.enums.TransferKind;
import com.premisave.developer.enums.TransferStatus;
import com.premisave.developer.model.TransferRequest;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TransferRequestRepository extends MongoRepository<TransferRequest, String> {

    /** The newest 50 that the account started or that are moving something to it. */
    List<TransferRequest> findFirst50ByRequesterIdOrToDeveloperIdOrderByCreatedAtDesc(String requesterId, String toDeveloperId);

    List<TransferRequest> findByRequesterIdAndKindAndStatus(String requesterId, TransferKind kind, TransferStatus status);

    Optional<TransferRequest> findFirstByRequesterIdOrderByCreatedAtDesc(String requesterId);

    long countByRequesterIdAndCreatedAtAfter(String requesterId, Instant after);

    long countByStatus(TransferStatus status);

    /** Staff search. Every pattern is already escaped; ".*" means any value. The developer is either side of the request. */
    @Query("{ status: { $regex: ?0 }, kind: { $regex: ?1 }, $or: [ { requesterId: { $regex: ?2 } }, { toDeveloperId: { $regex: ?2 } } ] }")
    Page<TransferRequest> adminSearch(String statusPattern, String kindPattern, String developerPattern, Pageable pageable);
}
