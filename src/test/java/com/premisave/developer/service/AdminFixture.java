package com.premisave.developer.service;

import com.premisave.developer.TestProps;
import com.premisave.developer.TestRepos;
import com.premisave.developer.client.ImageStorage;
import com.premisave.developer.client.WalletApi;
import com.premisave.developer.dto.MerchantInfo;
import com.premisave.developer.enums.AppStatus;
import com.premisave.developer.enums.DeveloperRole;
import com.premisave.developer.enums.DeveloperStatus;
import com.premisave.developer.enums.Environment;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.enums.TransferKind;
import com.premisave.developer.enums.TransferStatus;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.Developer;
import com.premisave.developer.model.DeveloperApp;
import com.premisave.developer.model.Limits;
import com.premisave.developer.model.TransferRequest;
import com.premisave.developer.security.JwtService;
import com.premisave.developer.security.PasswordHasher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything the admin services need, wired with in memory stand ins, and helpers to put accounts, apps and transfers in. */
final class AdminFixture {

    static final Instant T0 = Instant.parse("2026-10-18T08:00:00Z");

    /** A wallet service with a few businesses; can be made to fail when told about an app. */
    static final class Wallet extends WalletApi {
        final Map<String, Map<String, Object>> businesses = new LinkedHashMap<>();
        final List<DeveloperApp> pushed = new ArrayList<>();
        boolean pushFails;

        Wallet() {
            super(null, null);
        }

        void business(String id, String name) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("merchantId", id);
            m.put("merchantNumber", "PRM-" + id);
            m.put("businessName", name);
            m.put("country", "KE");
            m.put("sandbox", Map.of("status", "ACTIVE"));
            m.put("live", Map.of("status", "ACTIVE"));
            m.put("onboarding", Map.of("applicationId", "onb_" + id, "status", "APPROVED", "readyToSubmit", false, "missing", List.of()));
            businesses.put(id, m);
        }

        @Override
        public Map<String, Object> getMerchantView(String merchantId) {
            Map<String, Object> m = businesses.get(merchantId);
            if (m == null) throw new DeveloperException(ErrorCode.MERCHANT_NOT_FOUND, "Merchant not found");
            return m;
        }

        @Override
        public Map<String, Object> listMerchants(String developerAccountId) {
            return Map.of("businesses", List.of());
        }

        @Override
        public MerchantInfo getMerchant(String merchantId) {
            getMerchantView(merchantId);
            return new MerchantInfo(merchantId, "PRM-" + merchantId, "ACTIVE", "ACTIVE",
                    EnumSet.allOf(com.premisave.developer.enums.MethodType.class), new Limits(), new Limits(), Map.of());
        }

        @Override
        public void pushAppContext(DeveloperApp app) {
            if (pushFails) throw new DeveloperException(ErrorCode.UPSTREAM_UNAVAILABLE, "down");
            pushed.add(app);
        }
    }

    final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
    final TestRepos.Developers devs = new TestRepos.Developers();
    final TestRepos.Apps apps = new TestRepos.Apps();
    final TestRepos.Keys keyRepo = new TestRepos.Keys();
    final TestRepos.Transfers transfers = new TestRepos.Transfers();
    final TestRepos.AdminActions actions = new TestRepos.AdminActions();
    final TestRepos.EmailTokens emailTokens = new TestRepos.EmailTokens();
    final TestRepos.Identities identities = new TestRepos.Identities();
    final TestRepos.RecordingMailer mailer = new TestRepos.RecordingMailer();
    final Wallet wallet = new Wallet();

    final PasswordHasher hasher = new PasswordHasher(10_000);
    final AccountEmailService accountEmail;
    final DeveloperService accounts;
    final BusinessService businesses;
    final ApiKeyService keys;
    final AppContextSync sync;
    final AppService appService;
    final AdminAudit audit;
    final AdminDeveloperService adminDevelopers;
    final AdminAppService adminApps;
    final AdminOverviewService overview;

    /** Pictures are not set up, so removing one does nothing. */
    private static final ImageStorage NO_PICTURES = new ImageStorage() {
        @Override public boolean configured() { return false; }
        @Override public String idFor(String developerId) { return developerId; }
        @Override public Stored upload(String developerId, byte[] image, String contentType) { throw new IllegalStateException(); }
        @Override public void delete(String publicId) { throw new IllegalStateException(); }
    };

    AdminFixture() {
        JwtService jwt = new JwtService(TestProps.JWT_SECRET, "iss", 60, clock);
        EmailTokenService tokens = new EmailTokenService(emailTokens.repo, TestProps.mail(), TestProps.PEPPER, clock);
        MailService mail = new MailService(mailer, Runnable::run, false);
        accountEmail = new AccountEmailService(devs.repo, tokens, mail, hasher, TestProps.mail(), 10, clock);
        accounts = new DeveloperService(devs.repo, hasher, jwt, TestProps.fastSecurity(), new TestRepos.Revoked().repo, accountEmail, clock);
        businesses = new BusinessService(devs.repo, wallet, false, 10);
        keys = new ApiKeyService(keyRepo.repo, TestProps.keys(), clock);
        sync = new AppContextSync(wallet, apps.repo);
        appService = new AppService(apps.repo, businesses, wallet, keys, sync,
                new com.premisave.developer.config.DeveloperProperties.Apps(null, null), clock);
        audit = new AdminAudit(actions.repo, clock);
        ProfilePictureService pictures = new ProfilePictureService(devs.repo, NO_PICTURES, TestProps.with("http://localhost:8084", null));
        adminDevelopers = new AdminDeveloperService(devs.repo, accounts, businesses, apps.repo, wallet, accountEmail, pictures,
                emailTokens.repo, identities.repo, audit, clock);
        adminApps = new AdminAppService(apps.repo, devs.repo, appService, keys, businesses, sync, audit, clock);
        overview = new AdminOverviewService(devs.repo, apps.repo, transfers.repo, audit, clock);
    }

    /** An active account created {@code minute} minutes after T0. */
    Developer developer(String id, String email, DeveloperRole role, int minute) {
        Developer d = new Developer();
        d.setDeveloperId(id);
        d.setEmail(email);
        d.setUsername(id.replace("_", ""));
        d.setFirstName("First");
        d.setLastName(id);
        d.setFullName("First " + id);
        d.setCountry("KE");
        d.setRole(role);
        d.setStatus(DeveloperStatus.ACTIVE);
        d.setEmailVerified(true);
        d.setCreatedAt(T0.plusSeconds(minute * 60L));
        d.setUpdatedAt(d.getCreatedAt());
        devs.store.put(id, d);
        return d;
    }

    Developer developer(String id, String email) {
        return developer(id, email, DeveloperRole.DEVELOPER, 1);
    }

    Developer admin(String id, String email) {
        return developer(id, email, DeveloperRole.ADMIN, 0);
    }

    DeveloperApp app(String id, String developerId, String businessId, Environment env, String name, int minute) {
        DeveloperApp a = new DeveloperApp();
        a.setAppId(id);
        a.setDeveloperId(developerId);
        a.setMerchantId(businessId);
        a.setEnvironment(env);
        a.setName(name);
        a.setStatus(AppStatus.ACTIVE);
        a.setMerchantNumber("PRM-" + businessId);
        a.setCreatedAt(T0.plusSeconds(minute * 60L));
        a.setUpdatedAt(a.getCreatedAt());
        apps.store.put(id, a);
        return a;
    }

    TransferRequest transfer(String id, TransferKind kind, TransferStatus status, String requesterId, String toDeveloperId, int minute) {
        TransferRequest t = new TransferRequest();
        t.setId(id);
        t.setKind(kind);
        t.setStatus(status);
        t.setRequesterId(requesterId);
        t.setToDeveloperId(toDeveloperId);
        t.setFromEmail("from@acme.test");
        t.setToEmail("to@acme.test");
        t.setMerchantId("mer_1");
        t.setMerchantNumber("PRM-mer_1");
        t.setBusinessName("Acme");
        t.setCreatedAt(T0.plusSeconds(minute * 60L));
        t.setExpiresAt(T0.plusSeconds(minute * 60L + 900));
        transfers.store.put(id, t);
        return t;
    }

    static AdminActor actor(Developer d) {
        return AdminActor.of(d);
    }
}
