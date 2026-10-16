package com.premisave.developer.app;

import com.premisave.developer.wallet.WalletApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tells the wallet service about every change to an app, so its background work (scheduled settlements, subscription
 * billing) sees a suspension or a new limit even when the app is not sending requests. A failed push leaves the app marked
 * pending and is retried every half minute.
 */
@Component("developerAppContextSync")
public class AppContextSync {

    private static final Logger log = LoggerFactory.getLogger(AppContextSync.class);

    private final WalletApi wallet;
    private final DeveloperAppRepository apps;

    public AppContextSync(WalletApi wallet, DeveloperAppRepository apps) {
        this.wallet = wallet;
        this.apps = apps;
    }

    /** Best effort. Returns true when the wallet service accepted the app's current settings. */
    public boolean push(DeveloperApp app) {
        try {
            wallet.pushAppContext(app);
        } catch (RuntimeException e) {
            log.warn("Could not send app {} to the wallet service yet: {}", app.getAppId(), e.getMessage());
            return false;
        }
        // Clear the mark only if nothing changed while the call was in flight.
        apps.findById(app.getAppId()).ifPresent(current -> {
            if (current.getVersion() == app.getVersion() && current.isContextSyncPending()) {
                current.setContextSyncPending(false);
                apps.save(current);
            }
        });
        return true;
    }

    @Scheduled(fixedDelayString = "${developer.apps.sync-interval-ms:30000}")
    public void retryPending() {
        for (DeveloperApp app : apps.findByContextSyncPendingTrue()) {
            push(app);
        }
    }
}
