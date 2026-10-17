package com.premisave.developer.repository;

import com.premisave.developer.enums.AppStatus;
import com.premisave.developer.enums.Environment;
import com.premisave.developer.model.DeveloperApp;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;
import java.util.Optional;

public interface DeveloperAppRepository extends MongoRepository<DeveloperApp, String> {

    Page<DeveloperApp> findByDeveloperId(String developerId, Pageable pageable);

    Page<DeveloperApp> findByDeveloperIdAndEnvironment(String developerId, Environment environment, Pageable pageable);

    Page<DeveloperApp> findByDeveloperIdAndStatus(String developerId, AppStatus status, Pageable pageable);

    Page<DeveloperApp> findByDeveloperIdAndEnvironmentAndStatus(String developerId, Environment environment,
                                                                AppStatus status, Pageable pageable);

    Page<DeveloperApp> findByDeveloperIdAndMerchantId(String developerId, String merchantId, Pageable pageable);

    Page<DeveloperApp> findByDeveloperIdAndMerchantIdAndEnvironment(String developerId, String merchantId,
                                                                    Environment environment, Pageable pageable);

    Page<DeveloperApp> findByDeveloperIdAndMerchantIdAndStatus(String developerId, String merchantId, AppStatus status,
                                                               Pageable pageable);

    Page<DeveloperApp> findByDeveloperIdAndMerchantIdAndEnvironmentAndStatus(String developerId, String merchantId,
                                                                             Environment environment, AppStatus status,
                                                                             Pageable pageable);

    long countByDeveloperIdAndEnvironment(String developerId, Environment environment);

    long countByMerchantIdAndEnvironment(String merchantId, Environment environment);

    long countByDeveloperId(String developerId);

    long countByDeveloperIdAndMerchantId(String developerId, String merchantId);

    long countByEnvironment(Environment environment);

    long countByStatus(AppStatus status);

    /**
     * Staff search. Every pattern is already escaped; ".*" means any value. The first four are matched against the whole
     * value and the last, the app name, anywhere in it ignoring case.
     */
    @Query("{ developerId: { $regex: ?0 }, merchantId: { $regex: ?1 }, environment: { $regex: ?2 }, status: { $regex: ?3 },"
            + " name: { $regex: ?4, $options: 'i' } }")
    Page<DeveloperApp> adminSearch(String developerPattern, String merchantPattern, String environmentPattern,
                                   String statusPattern, String namePattern, Pageable pageable);

    Optional<DeveloperApp> findByPremisaveWalletId(String premisaveWalletId);

    List<DeveloperApp> findByContextSyncPendingTrue();
}
