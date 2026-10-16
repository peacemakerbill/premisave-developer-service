package com.premisave.developer.app;

import com.premisave.developer.enums.AppStatus;
import com.premisave.developer.enums.Environment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface DeveloperAppRepository extends MongoRepository<DeveloperApp, String> {

    Page<DeveloperApp> findByDeveloperId(String developerId, Pageable pageable);

    Page<DeveloperApp> findByDeveloperIdAndEnvironment(String developerId, Environment environment, Pageable pageable);

    Page<DeveloperApp> findByDeveloperIdAndStatus(String developerId, AppStatus status, Pageable pageable);

    Page<DeveloperApp> findByDeveloperIdAndEnvironmentAndStatus(String developerId, Environment environment,
                                                                AppStatus status, Pageable pageable);

    long countByDeveloperIdAndEnvironment(String developerId, Environment environment);

    Optional<DeveloperApp> findByPremisaveWalletId(String premisaveWalletId);

    List<DeveloperApp> findByContextSyncPendingTrue();
}
