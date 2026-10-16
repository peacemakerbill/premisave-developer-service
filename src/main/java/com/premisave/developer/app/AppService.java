package com.premisave.developer.app;

import com.premisave.developer.common.DeveloperException;
import com.premisave.developer.common.Ids;
import com.premisave.developer.config.DeveloperProperties;
import com.premisave.developer.developer.Developer;
import com.premisave.developer.developer.MerchantLinkService;
import com.premisave.developer.enums.AppStatus;
import com.premisave.developer.enums.Environment;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.enums.MethodType;
import com.premisave.developer.enums.SuspendedBy;
import com.premisave.developer.key.ApiKeyService;
import com.premisave.developer.model.Limits;
import com.premisave.developer.wallet.MerchantInfo;
import com.premisave.developer.wallet.WalletApi;
import com.premisave.developer.wallet.WalletInfo;
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
    private final MerchantLinkService merchants;
    private final WalletApi wallet;
    private final ApiKeyService keys;
    private final AppContextSync sync;
    private final DeveloperProperties.Apps config;
    private final Clock clock;

    @Autowired
    public AppService(DeveloperAppRepository apps, MerchantLinkService merchants, WalletApi wallet, ApiKeyService keys,
                      AppContextSync sync, DeveloperProperties props) {
        this(apps, merchants, wallet, keys, sync, props.apps(), Clock.systemUTC());
    }

    public AppService(DeveloperAppRepository apps, MerchantLinkService merchants, WalletApi wallet, ApiKeyService keys,
                      AppContextSync sync, DeveloperProperties.Apps config, Clock clock) {
        this.apps = apps;
        this.merchants = merchants;
        this.wallet = wallet;
        this.keys = keys;
        this.sync = sync;
        this.config = config;
        this.clock = clock;
    }

    public Created create(Developer developer, CreateAppRequest req) {
        MerchantInfo merchant = merchants.info(developer);
        Environment env = parseEnvironment(req.environment());
        String status = merchant.statusFor(env);
        if (env == Environment.LIVE && "PENDING_APPROVAL".equals(status)) {
            throw new DeveloperException(ErrorCode.LIVE_NOT_ENABLED, "Production access has not been approved for your business yet");
        }
        if (!"ACTIVE".equals(status)) {
            throw new DeveloperException(ErrorCode.MERCHANT_SUSPENDED,
                    "Your business is " + status.toLowerCase(Locale.ROOT) + " in this environment");
        }
        if (apps.countByDeveloperIdAndEnvironment(developer.getDeveloperId(), env) >= config.maxPerEnvironment()) {
            throw new DeveloperException(ErrorCode.LIMIT_REACHED,
                    "You can have " + config.maxPerEnvironment() + " " + env.headerValue() + " apps");
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
            if (number == null) throw DeveloperException.validation("merchantNumber", "is required for a production app");
            if (!number.equals(merchant.merchantNumber())) {
                throw DeveloperException.validation("merchantNumber", "does not match your business");
            }
            WalletInfo w = resolveWallet(req.premisaveWalletAccount());
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

    public Page<DeveloperApp> list(Developer developer, String environment, AppStatus status, Pageable pageable) {
        Environment env = environment == null || environment.isBlank() ? null : parseEnvironment(environment);
        String id = developer.getDeveloperId();
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
        DeveloperApp a = get(developer, appId);
        MerchantInfo merchant = null;
        if (req.name() != null && !req.name().isBlank()) a.setName(req.name().trim());
        if (req.description() != null) a.setDescription(blankToNull(req.description()));
        if (req.callbackUrl() != null) a.setCallbackUrl(callbackUrl(req.callbackUrl(), a.getEnvironment()));
        if (req.enabledMethods() != null || req.limits() != null) merchant = merchants.info(developer);
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
            throw DeveloperException.validation("premisaveWalletAccount", "is required for a production app");
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
        m.put("apiKey", com.premisave.developer.key.KeyViews.issued(key));
        return m;
    }
}
