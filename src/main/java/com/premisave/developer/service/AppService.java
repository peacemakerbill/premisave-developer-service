package com.premisave.developer.service;

import com.premisave.developer.client.WalletApi;
import com.premisave.developer.config.DeveloperProperties;
import com.premisave.developer.dto.CreateAppRequest;
import com.premisave.developer.dto.LimitsDto;
import com.premisave.developer.dto.MerchantInfo;
import com.premisave.developer.dto.UpdateAppRequest;
import com.premisave.developer.dto.WalletInfo;
import com.premisave.developer.enums.AppStatus;
import com.premisave.developer.enums.Environment;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.enums.MethodType;
import com.premisave.developer.enums.SuspendedBy;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.Developer;
import com.premisave.developer.model.DeveloperApp;
import com.premisave.developer.model.Limits;
import com.premisave.developer.repository.DeveloperAppRepository;
import com.premisave.developer.util.Ids;
import com.premisave.developer.view.AppViews;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Apps for sandbox and production. A production app needs the merchant's own PRM number and the Premisave wallet it goes live
 * with, and a wallet can be linked to one production app only. A sandbox app uses one of the configured test merchant numbers.
 * An app can only narrow what its merchant has, never widen it.
 */
@Service("developerAppService")
public class AppService {

    /** A new or changed app. keyIssued is set only when a key was created with it. */
    public record Created(DeveloperApp app, ApiKeyService.Issued key) {}

    private final DeveloperAppRepository apps;
    private final BusinessService businesses;
    private final WalletApi wallet;
    private final ApiKeyService keys;
    private final AppContextSync sync;
    private final DeveloperProperties.Apps config;
    private final Clock clock;

    @Autowired
    public AppService(DeveloperAppRepository apps, BusinessService businesses, WalletApi wallet, ApiKeyService keys,
                      AppContextSync sync, DeveloperProperties props) {
        this(apps, businesses, wallet, keys, sync, props.apps(), Clock.systemUTC());
    }

    public AppService(DeveloperAppRepository apps, BusinessService businesses, WalletApi wallet, ApiKeyService keys,
                      AppContextSync sync, DeveloperProperties.Apps config, Clock clock) {
        this.apps = apps;
        this.businesses = businesses;
        this.wallet = wallet;
        this.keys = keys;
        this.sync = sync;
        this.config = config;
        this.clock = clock;
    }

    public Created create(Developer developer, CreateAppRequest req) {
        MerchantInfo merchant = wallet.getMerchant(businesses.resolve(developer, req.businessId()));
        Environment env = parseEnvironment(req.environment());
        String status = merchant.statusFor(env);
        if (env == Environment.LIVE && "PENDING_APPROVAL".equals(status)) {
            throw new DeveloperException(ErrorCode.LIVE_NOT_ENABLED, "Production access has not been approved for your business yet");
        }
        if (!"ACTIVE".equals(status)) {
            throw new DeveloperException(ErrorCode.MERCHANT_SUSPENDED,
                    "Your business is " + status.toLowerCase(Locale.ROOT) + " in this environment");
        }
        if (apps.countByMerchantIdAndEnvironment(merchant.merchantId(), env) >= config.maxPerEnvironment()) {
            throw new DeveloperException(ErrorCode.LIMIT_REACHED,
                    "A business can have " + config.maxPerEnvironment() + " " + env.headerValue() + " apps");
        }

        DeveloperApp a = new DeveloperApp();
        a.setAppId(Ids.next("app"));
        a.setDeveloperId(developer.getDeveloperId());
        a.setMerchantId(merchant.merchantId());
        a.setEnvironment(env);
        a.setName(req.name().trim());
        a.setDescription(blankToNull(req.description()));
        a.setCallbackUrl(callbackUrl(req.callbackUrl(), env));

        String number = normaliseNumber(req.merchantNumber());
        if (env == Environment.LIVE) {
            if (merchant.merchantNumber() == null) {
                throw new DeveloperException(ErrorCode.INVALID_STATE, "This business has no merchant number yet. Try again shortly");
            }
            if (number != null && !number.equals(merchant.merchantNumber())) {
                throw DeveloperException.validation("merchantNumber", "does not match this business. Leave it out to use "
                        + merchant.merchantNumber());
            }
            number = merchant.merchantNumber();
            String walletRef = req.premisaveWalletAccount();
            if ((walletRef == null || walletRef.isBlank()) && developer.appliedFor(merchant.merchantId())) {
                walletRef = businesses.linkedWalletAccount(merchant.merchantId());
            }
            WalletInfo w = resolveWallet(walletRef);
            if (apps.findByPremisaveWalletId(w.walletId()).isPresent()) throw walletTaken();
            a.setMerchantNumber(number);
            a.setPremisaveWalletId(w.walletId());
            a.setPremisaveWalletAccount(w.accountNumber());
        } else {
            if (req.premisaveWalletAccount() != null && !req.premisaveWalletAccount().isBlank()) {
                throw DeveloperException.validation("premisaveWalletAccount", "applies to production apps only");
            }
            if (number == null) {
                number = config.defaultTestNumber();
            } else if (!config.isTestNumber(number)) {
                throw DeveloperException.validation("merchantNumber",
                        "must be one of the test merchant numbers " + config.testMerchantNumbers());
            }
            a.setMerchantNumber(number);
        }
        a.setEnabledMethods(narrowedMethods(merchant, env, req.enabledMethods()));
        a.setLimits(narrowedLimits(merchant, env, req.limits()));
        Instant now = clock.instant();
        a.setCreatedAt(now);
        a.touch(now);
        try {
            apps.insert(a);
        } catch (DuplicateKeyException raced) {
            throw walletTaken();
        }
        ApiKeyService.Issued key = keys.issue(a, "Default");
        sync.push(a);
        return new Created(a, key);
    }

    public Page<DeveloperApp> list(Developer developer, String environment, AppStatus status, String businessId,
                                   Pageable pageable) {
        Environment env = environment == null || environment.isBlank() ? null : parseEnvironment(environment);
        String id = developer.getDeveloperId();
        if (businessId != null && !businessId.isBlank()) {
            String business = businesses.requireOwned(developer, businessId.trim());
            if (env != null && status != null) {
                return apps.findByDeveloperIdAndMerchantIdAndEnvironmentAndStatus(id, business, env, status, pageable);
            }
            if (env != null) return apps.findByDeveloperIdAndMerchantIdAndEnvironment(id, business, env, pageable);
            if (status != null) return apps.findByDeveloperIdAndMerchantIdAndStatus(id, business, status, pageable);
            return apps.findByDeveloperIdAndMerchantId(id, business, pageable);
        }
        if (env != null && status != null) return apps.findByDeveloperIdAndEnvironmentAndStatus(id, env, status, pageable);
        if (env != null) return apps.findByDeveloperIdAndEnvironment(id, env, pageable);
        if (status != null) return apps.findByDeveloperIdAndStatus(id, status, pageable);
        return apps.findByDeveloperId(id, pageable);
    }

    public DeveloperApp get(Developer developer, String appId) {
        return apps.findById(appId).filter(a -> a.getDeveloperId().equals(developer.getDeveloperId()))
                .orElseThrow(() -> new DeveloperException(ErrorCode.APP_NOT_FOUND, "App not found"));
    }

    public DeveloperApp update(Developer developer, String appId, UpdateAppRequest req) {
        return applyUpdate(get(developer, appId), req);
    }

    /** Premisave staff: change any app. The same rules apply as for its owner, so an app still cannot widen its business. */
    public DeveloperApp adminUpdate(String appId, UpdateAppRequest req) {
        return applyUpdate(adminGet(appId), req);
    }

    private DeveloperApp applyUpdate(DeveloperApp a, UpdateAppRequest req) {
        MerchantInfo merchant = null;
        if (req.name() != null && !req.name().isBlank()) a.setName(req.name().trim());
        if (req.description() != null) a.setDescription(blankToNull(req.description()));
        if (req.callbackUrl() != null) a.setCallbackUrl(callbackUrl(req.callbackUrl(), a.getEnvironment()));
        if (req.enabledMethods() != null || req.limits() != null) merchant = wallet.getMerchant(a.getMerchantId());
        if (req.enabledMethods() != null) a.setEnabledMethods(narrowedMethods(merchant, a.getEnvironment(), req.enabledMethods()));
        if (req.limits() != null) a.setLimits(narrowedLimits(merchant, a.getEnvironment(), req.limits()));
        a.touch(clock.instant());
        apps.save(a);
        sync.push(a);
        return a;
    }

    public DeveloperApp suspend(Developer developer, String appId, String reason) {
        DeveloperApp a = get(developer, appId);
        if (a.getStatus() == AppStatus.SUSPENDED) return a;
        return setStatus(a, AppStatus.SUSPENDED, SuspendedBy.DEVELOPER, blankToNull(reason));
    }

    public DeveloperApp reactivate(Developer developer, String appId) {
        DeveloperApp a = get(developer, appId);
        if (a.getStatus() == AppStatus.ACTIVE) return a;
        if (a.getSuspendedBy() == SuspendedBy.ADMIN) {
            throw new DeveloperException(ErrorCode.FORBIDDEN, "Premisave suspended this app, contact support to have it lifted");
        }
        return setStatus(a, AppStatus.ACTIVE, null, null);
    }

    /** Premisave staff: suspend or reactivate any app. */
    public DeveloperApp adminSetStatus(String appId, AppStatus status, String reason) {
        DeveloperApp a = apps.findById(appId).orElseThrow(() -> new DeveloperException(ErrorCode.APP_NOT_FOUND, "App not found"));
        if (status == AppStatus.SUSPENDED) {
            return setStatus(a, AppStatus.SUSPENDED, SuspendedBy.ADMIN, blankToNull(reason));
        }
        return setStatus(a, AppStatus.ACTIVE, null, null);
    }

    public DeveloperApp adminGet(String appId) {
        return apps.findById(appId).orElseThrow(() -> new DeveloperException(ErrorCode.APP_NOT_FOUND, "App not found"));
    }

    private DeveloperApp setStatus(DeveloperApp a, AppStatus status, SuspendedBy by, String reason) {
        a.setStatus(status);
        a.setSuspendedBy(by);
        a.setSuspensionReason(reason);
        a.touch(clock.instant());
        apps.save(a);
        sync.push(a);
        return a;
    }

    // Rules

    private WalletInfo resolveWallet(String reference) {
        if (reference == null || reference.isBlank()) {
            throw DeveloperException.validation("premisaveWalletAccount",
                    "is required for a production app. Give a wallet account number, or link one on the business application");
        }
        WalletInfo w = wallet.resolveWallet(reference.trim());
        if (w.frozen()) {
            throw new DeveloperException(ErrorCode.WALLET_NOT_USABLE, "That Premisave wallet is frozen and cannot be linked");
        }
        return w;
    }

    private static DeveloperException walletTaken() {
        return new DeveloperException(ErrorCode.WALLET_ALREADY_LINKED,
                "A production app has already gone live with this Premisave wallet");
    }

    static Environment parseEnvironment(String value) {
        return Environment.parse(value)
                .orElseThrow(() -> DeveloperException.validation("environment", "must be production, live or sandbox"));
    }

    static String normaliseNumber(String n) {
        return n == null || n.isBlank() ? null : n.trim().toUpperCase(Locale.ROOT);
    }

    static Set<MethodType> narrowedMethods(MerchantInfo m, Environment env, Set<MethodType> requested) {
        if (requested == null) return null;
        Set<MethodType> set = requested.isEmpty() ? EnumSet.noneOf(MethodType.class) : EnumSet.copyOf(requested);
        if (env == Environment.LIVE && !m.enabledMethods().containsAll(set)) {
            throw DeveloperException.validation("enabledMethods", "an app cannot enable a method your business does not have");
        }
        return set;
    }

    static Limits narrowedLimits(MerchantInfo m, Environment env, LimitsDto dto) {
        if (dto == null) return null;
        Limits l = new Limits();
        l.setSingleTransactionUsd(positive("limits.singleTransactionUsd", dto.singleTransactionUsd()));
        l.setDailyVolumeUsd(positive("limits.dailyVolumeUsd", dto.dailyVolumeUsd()));
        l.setMonthlyVolumeUsd(positive("limits.monthlyVolumeUsd", dto.monthlyVolumeUsd()));
        if (dto.dailyPayoutCount() != null && dto.dailyPayoutCount() < 1) {
            throw DeveloperException.validation("limits.dailyPayoutCount", "must be at least 1");
        }
        l.setDailyPayoutCount(dto.dailyPayoutCount());
        if (!l.fitsWithin(m.limitsFor(env))) {
            throw DeveloperException.validation("limits", "an app cannot exceed your business's limits");
        }
        return l;
    }

    private static BigDecimal positive(String field, BigDecimal v) {
        if (v != null && v.signum() <= 0) throw DeveloperException.validation(field, "must be greater than zero");
        return v;
    }

    /** https only, except that a sandbox app may use plain http. No credentials in the address. */
    static String callbackUrl(String url, Environment env) {
        if (url == null || url.isBlank()) return null;
        URI u;
        try {
            u = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw DeveloperException.validation("callbackUrl", "is not a valid URL");
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        boolean ok = scheme.equals("https") || (scheme.equals("http") && env == Environment.SANDBOX);
        if (!ok || u.getHost() == null || u.getUserInfo() != null || u.getFragment() != null) {
            throw DeveloperException.validation("callbackUrl",
                    env == Environment.LIVE ? "must be an https address" : "must be an http or https address");
        }
        return u.toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** For the view of a newly created app. */
    public static Map<String, Object> withKey(DeveloperApp app, ApiKeyService.Issued key) {
        Map<String, Object> m = new LinkedHashMap<>(AppViews.app(app));
        m.put("apiKey", com.premisave.developer.view.KeyViews.issued(key));
        return m;
    }
}
