package com.premisave.developer.service;

import com.premisave.developer.dto.UpdateAppRequest;
import com.premisave.developer.enums.AppStatus;
import com.premisave.developer.enums.DeveloperStatus;
import com.premisave.developer.enums.Environment;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.ApiKey;
import com.premisave.developer.model.Developer;
import com.premisave.developer.model.DeveloperApp;
import com.premisave.developer.repository.DeveloperAppRepository;
import com.premisave.developer.repository.DeveloperRepository;
import com.premisave.developer.util.SearchText;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

/**
 * Staff control of apps and their keys: search, read, update, suspend, hand to another account and delete, and issue, rotate
 * and revoke keys. The same rules apply as for the owner (an app never widens its business), except that nothing here depends
 * on who owns the app. Every change is recorded in the audit trail.
 */
@Service("developerAdminAppService")
public class AdminAppService {

    private final DeveloperAppRepository apps;
    private final DeveloperRepository developers;
    private final AppService appService;
    private final ApiKeyService keys;
    private final BusinessService businesses;
    private final AppContextSync sync;
    private final AdminAudit audit;
    private final Clock clock;

    @Autowired
    public AdminAppService(DeveloperAppRepository apps, DeveloperRepository developers, AppService appService,
                           ApiKeyService keys, BusinessService businesses, AppContextSync sync, AdminAudit audit) {
        this(apps, developers, appService, keys, businesses, sync, audit, Clock.systemUTC());
    }

    public AdminAppService(DeveloperAppRepository apps, DeveloperRepository developers, AppService appService,
                           ApiKeyService keys, BusinessService businesses, AppContextSync sync, AdminAudit audit, Clock clock) {
        this.apps = apps;
        this.developers = developers;
        this.appService = appService;
        this.keys = keys;
        this.businesses = businesses;
        this.sync = sync;
        this.audit = audit;
        this.clock = clock;
    }

    // Apps

    /** Newest first. Each filter is optional. name matches anywhere in the app name, the others must match exactly. */
    public Page<DeveloperApp> search(String developerId, String businessId, String environment, AppStatus status, String name,
                                     Pageable pageable) {
        Environment env = environment == null || environment.isBlank() ? null : AppService.parseEnvironment(environment);
        return apps.adminSearch(SearchText.exact(developerId), SearchText.exact(businessId),
                env == null ? SearchText.ANY : SearchText.exact(env.name()),
                status == null ? SearchText.ANY : SearchText.exact(status.name()),
                SearchText.contains(name), pageable);
    }

    public DeveloperApp get(String appId) {
        return appService.adminGet(appId);
    }

    public DeveloperApp update(AdminActor actor, String appId, UpdateAppRequest req) {
        DeveloperApp a = appService.adminUpdate(appId, req);
        audit.record(actor, "APP_UPDATED", "APP", appId, "settings changed");
        return a;
    }

    public DeveloperApp setStatus(AdminActor actor, String appId, AppStatus status, String reason) {
        DeveloperApp a = appService.adminSetStatus(appId, status, reason);
        audit.record(actor, status == AppStatus.SUSPENDED ? "APP_SUSPENDED" : "APP_REACTIVATED", "APP", appId,
                status == AppStatus.SUSPENDED ? "suspended: " + reason : "reactivated");
        return a;
    }

    /**
     * Hands the app to another active account without the code a developer needs, and gives that account use of the business.
     * The keys are revoked first so the old owner can never call the API with the app after it moved.
     */
    public DeveloperApp changeOwner(AdminActor actor, String appId, String toDeveloperId) {
        DeveloperApp a = appService.adminGet(appId);
        Developer to = developers.findById(toDeveloperId)
                .orElseThrow(() -> new DeveloperException(ErrorCode.NOT_FOUND, "Developer not found"));
        if (to.getStatus() != DeveloperStatus.ACTIVE) {
            throw DeveloperException.invalidState("The account the app is going to is not active");
        }
        if (to.getDeveloperId().equals(a.getDeveloperId())) {
            throw DeveloperException.invalidState("This account already owns the app");
        }
        String from = a.getDeveloperId();
        businesses.share(to, a.getMerchantId());
        int revoked = keys.revokeAll(appId);
        a.setDeveloperId(to.getDeveloperId());
        a.touch(clock.instant());
        apps.save(a);
        sync.push(a);
        audit.record(actor, "APP_OWNER_CHANGED", "APP", appId, "from " + from + " to " + toDeveloperId + ", " + revoked + " keys revoked");
        return a;
    }

    /**
     * Deletes the app and its keys. The wallet service is told first that the app is suspended, so its background work stops;
     * if that cannot be done the app stays suspended and the delete is refused, to be tried again.
     */
    public void delete(AdminActor actor, String appId) {
        DeveloperApp a = appService.adminGet(appId);
        DeveloperApp suspended = appService.adminSetStatus(appId, AppStatus.SUSPENDED, "Deleted by Premisave staff");
        if (!sync.push(suspended)) {
            throw DeveloperException.invalidState("The wallet service could not be told the app is closing. The app is suspended; try again shortly");
        }
        long removed = keys.deleteAll(appId);
        apps.deleteById(appId);
        audit.record(actor, "APP_DELETED", "APP", appId, a.getName() + " of " + a.getDeveloperId() + ", " + removed + " keys removed");
    }

    // Keys

    public List<ApiKey> keys(String appId) {
        return keys.list(appService.adminGet(appId).getAppId());
    }

    public ApiKeyService.Issued issueKey(AdminActor actor, String appId, String label) {
        ApiKeyService.Issued issued = keys.issue(appService.adminGet(appId), label);
        audit.record(actor, "KEY_ISSUED", "KEY", issued.key().getKey(), "for app " + appId);
        return issued;
    }

    public ApiKeyService.Issued rotateKey(AdminActor actor, String appId, String keyId) {
        ApiKeyService.Issued issued = keys.rotate(appService.adminGet(appId), keyId);
        audit.record(actor, "KEY_ROTATED", "KEY", keyId, "replaced by " + issued.key().getKey());
        return issued;
    }

    public ApiKey revokeKey(AdminActor actor, String appId, String keyId) {
        ApiKey k = keys.revoke(appService.adminGet(appId), keyId);
        audit.record(actor, "KEY_REVOKED", "KEY", keyId, "for app " + appId);
        return k;
    }

    /** Ends every key of the app and says how many there were. */
    public int revokeAllKeys(AdminActor actor, String appId) {
        appService.adminGet(appId);
        int ended = keys.revokeAll(appId);
        audit.record(actor, "KEYS_REVOKED", "APP", appId, ended + " keys revoked");
        return ended;
    }
}
