package com.premisave.developer.service;

import com.premisave.developer.TestProps;
import com.premisave.developer.TestRepos;
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

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rules for creating and changing apps: merchant number, test numbers, the Premisave wallet link, narrowing, suspension. */
class AppServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-16T08:00:00Z");

    /** A wallet service that answers from maps instead of the network. */
    static final class FakeWallet extends WalletApi {
        final Map<String, WalletInfo> wallets = new HashMap<>();
        final List<DeveloperApp> pushed = new ArrayList<>();
        boolean pushFails;
        String sandboxStatus = "ACTIVE";
        String liveStatus = "ACTIVE";
        Set<MethodType> merchantMethods = EnumSet.of(MethodType.CARD, MethodType.MOBILE_MONEY);
        Limits liveLimits = new Limits(new BigDecimal("500"), null, null, null);
        /** Merchant numbers by business id. A business not listed has PRM-0000000001. */
        final Map<String, String> numbers = new HashMap<>();
        /** The wallet account named on a business's application, by business id. */
        final Map<String, String> linked = new HashMap<>();

        FakeWallet() {
            super(null, null);
        }

        @Override
        public MerchantInfo getMerchant(String merchantId) {
            return new MerchantInfo(merchantId, numbers.getOrDefault(merchantId, "PRM-0000000001"), sandboxStatus, liveStatus, merchantMethods,
                    new Limits(), liveLimits, Map.of());
        }

        @Override
        public Map<String, Object> getMerchantView(String merchantId) {
            Map<String, Object> view = new HashMap<>();
            view.put("merchantId", merchantId);
            Map<String, Object> onboarding = new HashMap<>();
            onboarding.put("walletAccount", linked.get(merchantId));
            view.put("onboarding", onboarding);
            return view;
        }

        @Override
        public WalletInfo resolveWallet(String reference) {
            WalletInfo w = wallets.get(reference.toLowerCase());
            if (w == null) throw new DeveloperException(ErrorCode.WALLET_NOT_FOUND, "No Premisave wallet matches that account number or id");
            return w;
        }

        @Override
        public void pushAppContext(DeveloperApp app) {
            if (pushFails) throw new DeveloperException(ErrorCode.UPSTREAM_UNAVAILABLE, "down");
            pushed.add(app);
        }
    }

    private final TestRepos.Apps appRepo = new TestRepos.Apps();
    private final TestRepos.Keys keyRepo = new TestRepos.Keys();
    private final TestRepos.Developers devRepo = new TestRepos.Developers();
    private final FakeWallet wallet = new FakeWallet();
    private final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
    private final AppService service;
    private final Developer dev = new Developer();

    AppServiceTest() {
        dev.setDeveloperId("dev_1");
        dev.setEmail("owner@acme.test");
        dev.addBusiness("mer_1");
        wallet.wallets.put("owner@acme.test", new WalletInfo("w1", "owner@acme.test", false));
        wallet.wallets.put("w1", new WalletInfo("w1", "owner@acme.test", false));
        wallet.wallets.put("frozen@acme.test", new WalletInfo("w2", "frozen@acme.test", true));
        wallet.wallets.put("other@acme.test", new WalletInfo("w3", "other@acme.test", false));
        ApiKeyService keys = new ApiKeyService(keyRepo.repo, TestProps.keys(), clock);
        AppContextSync sync = new AppContextSync(wallet, appRepo.repo);
        service = new AppService(appRepo.repo, new BusinessService(devRepo.repo, wallet, false, 10), wallet, keys, sync,
                new DeveloperProperties.Apps(null, null), clock);
    }

    private static CreateAppRequest sandbox(String number) {
        return new CreateAppRequest("Shop", null, "sandbox", number, null, null, null, null);
    }

    private static CreateAppRequest live(String number, String walletRef) {
        return new CreateAppRequest("Shop", null, "production", number, walletRef, null, null, null);
    }

    private static CreateAppRequest withLimits(String env, LimitsDto limits) {
        return new CreateAppRequest("Shop", null, env, null, env.equals("sandbox") ? null : "other@acme.test", null, limits, null);
    }

    private static org.springframework.data.domain.Page<DeveloperApp> marker(String label) {
        DeveloperApp a = new DeveloperApp();
        a.setName(label);
        return new org.springframework.data.domain.PageImpl<>(List.of(a), org.springframework.data.domain.PageRequest.of(0, 1), 1);
    }

    private static String label(org.springframework.data.domain.Page<DeveloperApp> page) {
        return page.getContent().get(0).getName();
    }

    private static DeveloperException fails(Runnable r) {
        return assertThrows(DeveloperException.class, r::run);
    }

    @Test
    void aSandboxAppGetsTheDefaultTestNumberAndAFirstKey() {
        AppService.Created c = service.create(dev, sandbox(null));
        assertEquals("PRM-TEST-0001", c.app().getMerchantNumber());
        assertEquals(Environment.SANDBOX, c.app().getEnvironment());
        assertEquals("mer_1", c.app().getMerchantId());
        assertTrue(c.app().getAppId().startsWith("app_"));
        assertNull(c.app().getPremisaveWalletId());
        assertTrue(c.key().key().getKey().startsWith("pk_sandbox_"));
        assertEquals(c.app().getAppId(), c.key().key().getAppId());
        assertTrue(AppService.withKey(c.app(), c.key()).toString().contains(c.key().secret()));
    }

    @Test
    void aSandboxAppMayPickAnyConfiguredTestNumberAndNothingElse() {
        assertEquals("PRM-TEST-0004", service.create(dev, sandbox("prm-test-0004")).app().getMerchantNumber());
        assertEquals(ErrorCode.VALIDATION_FAILED, fails(() -> service.create(dev, sandbox("PRM-TEST-0099"))).code());
        assertEquals(ErrorCode.VALIDATION_FAILED, fails(() -> service.create(dev, sandbox("PRM-0000000001"))).code());
    }

    @Test
    void aSandboxAppCannotLinkAWallet() {
        assertEquals(ErrorCode.VALIDATION_FAILED,
                fails(() -> service.create(dev, new CreateAppRequest("Shop", null, "sandbox", null, "owner@acme.test", null, null, null))).code());
    }

    @Test
    void aProductionAppNeedsTheMerchantNumberAndAWalletAndRecordsBoth() {
        AppService.Created c = service.create(dev, live(" prm-0000000001 ", "Owner@Acme.test"));
        assertEquals("PRM-0000000001", c.app().getMerchantNumber());
        assertEquals("w1", c.app().getPremisaveWalletId());
        assertEquals("owner@acme.test", c.app().getPremisaveWalletAccount());
        assertTrue(c.key().key().getKey().startsWith("pk_live_"));
    }

    @Test
    void productionRefusesAWrongMerchantNumberOrWallet() {
        assertEquals(ErrorCode.VALIDATION_FAILED, fails(() -> service.create(dev, live("PRM-TEST-0001", "owner@acme.test"))).code());
        assertEquals(ErrorCode.VALIDATION_FAILED, fails(() -> service.create(dev, live("PRM-0000000002", "owner@acme.test"))).code());
        assertEquals(ErrorCode.VALIDATION_FAILED, fails(() -> service.create(dev, live("PRM-0000000001", null))).code());
        assertEquals(ErrorCode.WALLET_NOT_FOUND, fails(() -> service.create(dev, live("PRM-0000000001", "nobody@acme.test"))).code());
        assertEquals(ErrorCode.WALLET_NOT_USABLE, fails(() -> service.create(dev, live("PRM-0000000001", "frozen@acme.test"))).code());
        assertTrue(appRepo.store.isEmpty());
    }

    @Test
    void aProductionAppUsesTheWalletOnTheApplicationWhenNoneIsGiven() {
        wallet.linked.put("mer_1", "owner@acme.test");
        AppService.Created c = service.create(dev, live(null, null));
        assertEquals("owner@acme.test", c.app().getPremisaveWalletAccount());
        assertEquals("w1", c.app().getPremisaveWalletId());
    }

    @Test
    void aGivenWalletWinsOverTheOneOnTheApplication() {
        wallet.linked.put("mer_1", "owner@acme.test");
        assertEquals("other@acme.test", service.create(dev, live(null, "other@acme.test")).app().getPremisaveWalletAccount());
    }

    @Test
    void aSharedBusinessNeverBorrowsTheWalletOfTheApplicant() {
        wallet.linked.put("mer_9", "owner@acme.test");
        Developer guest = new Developer();
        guest.setDeveloperId("dev_2");
        guest.addSharedBusiness("mer_9");
        DeveloperException e = fails(() -> service.create(guest, new CreateAppRequest("mer_9", "Shop", null, "production", null, null, null, null, null)));
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code());
        assertTrue(appRepo.store.isEmpty());
    }

    @Test
    void aWalletGoesLiveWithOneProductionAppOnlyWhetherGivenByAccountOrId() {
        service.create(dev, live("PRM-0000000001", "owner@acme.test"));
        assertEquals(ErrorCode.WALLET_ALREADY_LINKED, fails(() -> service.create(dev, live("PRM-0000000001", "w1"))).code());
        assertEquals(ErrorCode.WALLET_ALREADY_LINKED, fails(() -> service.create(dev, live("PRM-0000000001", "OWNER@acme.test"))).code());
        assertNotNull(service.create(dev, live("PRM-0000000001", "other@acme.test")));
        assertNotNull(service.create(dev, sandbox(null)));
        assertNotNull(service.create(dev, sandbox(null)));
    }

    @Test
    void productionWaitsForApprovalAndStopsWhenTheMerchantIsSuspended() {
        wallet.liveStatus = "PENDING_APPROVAL";
        assertEquals(ErrorCode.LIVE_NOT_ENABLED, fails(() -> service.create(dev, live("PRM-0000000001", "owner@acme.test"))).code());
        assertNotNull(service.create(dev, sandbox(null)));
        wallet.liveStatus = "SUSPENDED";
        assertEquals(ErrorCode.MERCHANT_SUSPENDED, fails(() -> service.create(dev, live("PRM-0000000001", "owner@acme.test"))).code());
        wallet.sandboxStatus = "SUSPENDED";
        assertEquals(ErrorCode.MERCHANT_SUSPENDED, fails(() -> service.create(dev, sandbox(null))).code());
    }

    @Test
    void aDeveloperWithoutABusinessCannotCreateApps() {
        Developer none = new Developer();
        none.setDeveloperId("dev_2");
        assertEquals(ErrorCode.BUSINESS_REQUIRED, fails(() -> service.create(none, sandbox(null))).code());
    }

    @Test
    void anAppOnlyNarrowsTheMerchantsMethodsAndLimitsInProduction() {
        CreateAppRequest ok = new CreateAppRequest("Shop", null, "production", "PRM-0000000001", "owner@acme.test",
                Set.of(MethodType.CARD), new LimitsDto(new BigDecimal("100"), null, null, null), null);
        AppService.Created c = service.create(dev, ok);
        assertEquals(EnumSet.of(MethodType.CARD), c.app().getEnabledMethods());
        assertEquals(new BigDecimal("100"), c.app().getLimits().getSingleTransactionUsd());

        CreateAppRequest wideMethods = new CreateAppRequest("Shop", null, "production", "PRM-0000000001", "other@acme.test",
                Set.of(MethodType.CRYPTO), null, null);
        assertEquals(ErrorCode.VALIDATION_FAILED, fails(() -> service.create(dev, wideMethods)).code());
        assertEquals(ErrorCode.VALIDATION_FAILED, fails(() -> service.create(dev,
                withLimits("production", new LimitsDto(new BigDecimal("501"), null, null, null)))).code());
        assertEquals(ErrorCode.VALIDATION_FAILED, fails(() -> service.create(dev,
                withLimits("production", new LimitsDto(new BigDecimal("-1"), null, null, null)))).code());
        assertEquals(ErrorCode.VALIDATION_FAILED, fails(() -> service.create(dev,
                withLimits("sandbox", new LimitsDto(null, null, null, 0)))).code());
    }

    @Test
    void sandboxAppsCanEnableAnyMethodBecauseSandboxAllowsEverything() {
        CreateAppRequest r = new CreateAppRequest("Shop", null, "sandbox", null, null, Set.of(MethodType.CRYPTO), null, null);
        assertEquals(EnumSet.of(MethodType.CRYPTO), service.create(dev, r).app().getEnabledMethods());
    }

    @Test
    void callbackUrlsMustBeHttpsExceptInSandbox() {
        assertEquals("https://shop.test/hooks", AppService.callbackUrl(" https://shop.test/hooks ", Environment.LIVE));
        assertEquals("http://localhost:3000/hook", AppService.callbackUrl("http://localhost:3000/hook", Environment.SANDBOX));
        assertNull(AppService.callbackUrl("  ", Environment.LIVE));
        for (String bad : new String[] {"http://shop.test/h", "ftp://x.test/h", "https://user:pw@shop.test/h", "https:///nohost", "not a url", "https://shop.test/h#frag"}) {
            assertEquals(ErrorCode.VALIDATION_FAILED,
                    assertThrows(DeveloperException.class, () -> AppService.callbackUrl(bad, Environment.LIVE)).code(), bad);
        }
        assertEquals(ErrorCode.VALIDATION_FAILED,
                assertThrows(DeveloperException.class, () -> AppService.callbackUrl("ftp://x.test", Environment.SANDBOX)).code());
    }

    @Test
    void anEnvironmentHasAMaximumNumberOfApps() {
        AppService small = new AppService(appRepo.repo, new BusinessService(devRepo.repo, wallet, false, 10), wallet,
                new ApiKeyService(keyRepo.repo, TestProps.keys(), clock), new AppContextSync(wallet, appRepo.repo),
                new DeveloperProperties.Apps(2, null), clock);
        small.create(dev, sandbox(null));
        small.create(dev, sandbox(null));
        assertEquals(ErrorCode.LIMIT_REACHED, fails(() -> small.create(dev, sandbox(null))).code());
        assertNotNull(small.create(dev, live("PRM-0000000001", "owner@acme.test")));
    }

    @Test
    void appsBelongToTheirDeveloper() {
        AppService.Created c = service.create(dev, sandbox(null));
        Developer other = new Developer();
        other.setDeveloperId("dev_9");
        other.addBusiness("mer_9");
        assertEquals(ErrorCode.APP_NOT_FOUND, fails(() -> service.get(other, c.app().getAppId())).code());
        assertEquals(ErrorCode.APP_NOT_FOUND, fails(() -> service.suspend(other, c.app().getAppId(), null)).code());
        assertEquals(ErrorCode.APP_NOT_FOUND, fails(() -> service.update(other, c.app().getAppId(), new UpdateAppRequest("x", null, null, null, null))).code());
        assertEquals(c.app().getAppId(), service.get(dev, c.app().getAppId()).getAppId());
    }

    @Test
    void everyChangeBumpsTheVersionAndTellsTheWalletService() {
        AppService.Created c = service.create(dev, sandbox(null));
        DeveloperApp a = c.app();
        long v1 = a.getVersion();
        assertEquals(1, wallet.pushed.size());
        assertFalse(a.isContextSyncPending());

        service.update(dev, a.getAppId(), new UpdateAppRequest("Renamed", "desc", null, null, null));
        assertEquals("Renamed", a.getName());
        assertTrue(a.getVersion() > v1);
        assertEquals(2, wallet.pushed.size());
        assertFalse(a.isContextSyncPending());
    }

    @Test
    void aFailedPushLeavesTheAppPendingAndTheRetryClearsIt() {
        wallet.pushFails = true;
        AppService.Created c = service.create(dev, sandbox(null));
        assertTrue(c.app().isContextSyncPending());
        wallet.pushFails = false;
        new AppContextSync(wallet, appRepo.repo).retryPending();
        assertFalse(c.app().isContextSyncPending());
        assertEquals(1, wallet.pushed.size());
    }

    @Test
    void aDeveloperCanSuspendAndReactivateTheirOwnApp() {
        DeveloperApp a = service.create(dev, sandbox(null)).app();
        service.suspend(dev, a.getAppId(), " maintenance ");
        assertEquals(AppStatus.SUSPENDED, a.getStatus());
        assertEquals(SuspendedBy.DEVELOPER, a.getSuspendedBy());
        assertEquals("maintenance", a.getSuspensionReason());
        assertEquals(AppStatus.SUSPENDED, wallet.pushed.get(wallet.pushed.size() - 1).getStatus());

        service.reactivate(dev, a.getAppId());
        assertEquals(AppStatus.ACTIVE, a.getStatus());
        assertNull(a.getSuspendedBy());
        assertNull(a.getSuspensionReason());
    }

    @Test
    void aSuspensionByPremisaveCannotBeLiftedByTheDeveloper() {
        DeveloperApp a = service.create(dev, sandbox(null)).app();
        service.adminSetStatus(a.getAppId(), AppStatus.SUSPENDED, "fraud review");
        assertEquals(SuspendedBy.ADMIN, a.getSuspendedBy());
        assertEquals(ErrorCode.FORBIDDEN, fails(() -> service.reactivate(dev, a.getAppId())).code());
        service.adminSetStatus(a.getAppId(), AppStatus.ACTIVE, null);
        assertEquals(AppStatus.ACTIVE, a.getStatus());
    }

    @Test
    void listFiltersByEnvironmentAndStatus() {
        // The repository methods are exercised through the fake only for the ones this test names.
        DeveloperAppRepository repo = com.premisave.developer.Fake.of(DeveloperAppRepository.class, Map.of(
                "findByDeveloperIdAndEnvironmentAndStatus", a -> marker("both"),
                "findByDeveloperIdAndEnvironment", a -> marker("env"),
                "findByDeveloperIdAndStatus", a -> marker("status"),
                "findByDeveloperId", a -> marker("none")));
        AppService s = new AppService(repo, null, wallet, null, null, new DeveloperProperties.Apps(null, null), clock);
        assertEquals("both", label(s.list(dev, "live", AppStatus.ACTIVE, null, null)));
        assertEquals("env", label(s.list(dev, "sandbox", null, null, null)));
        assertEquals("status", label(s.list(dev, null, AppStatus.SUSPENDED, null, null)));
        assertEquals("none", label(s.list(dev, " ", null, null, null)));
        assertEquals(ErrorCode.VALIDATION_FAILED, fails(() -> s.list(dev, "mars", null, null, null)).code());
    }

    private static CreateAppRequest sandboxFor(String businessId) {
        return new CreateAppRequest(businessId, "Shop", null, "sandbox", null, null, null, null, null);
    }

    private static CreateAppRequest liveFor(String businessId, String number) {
        return new CreateAppRequest(businessId, "Shop", null, "production", number, "owner@acme.test", null, null, null);
    }

    private Developer twoBusinesses() {
        Developer d = new Developer();
        d.setDeveloperId("dev_2");
        d.addBusiness("mer_1");
        d.addBusiness("mer_2");
        wallet.numbers.put("mer_2", "PRM-0000000002");
        return d;
    }

    @Test
    void aProductionAppMayLeaveTheMerchantNumberOutAndGetTheBusinessesOwn() {
        AppService.Created c = service.create(dev, live(null, "owner@acme.test"));
        assertEquals("PRM-0000000001", c.app().getMerchantNumber());
    }

    @Test
    void aProductionAppIsRefusedWhenTheBusinessHasNoMerchantNumberYet() {
        wallet.numbers.put("mer_1", null);
        assertEquals(ErrorCode.INVALID_STATE, fails(() -> service.create(dev, live(null, "owner@acme.test"))).code());
    }

    @Test
    void anAccountWithSeveralBusinessesMustSayWhichOne() {
        Developer d = twoBusinesses();
        DeveloperException e = fails(() -> service.create(d, sandbox(null)));
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code());
        assertEquals(List.of("mer_1", "mer_2"), e.extra().get("businessIds"));
        assertTrue(appRepo.store.isEmpty());
    }

    @Test
    void anAppIsCreatedForTheBusinessNamedAndUsesItsNumber() {
        Developer d = twoBusinesses();
        assertEquals("mer_2", service.create(d, sandboxFor("mer_2")).app().getMerchantId());
        AppService.Created live = service.create(d, liveFor("mer_2", null));
        assertEquals("mer_2", live.app().getMerchantId());
        assertEquals("PRM-0000000002", live.app().getMerchantNumber());
        assertEquals(ErrorCode.VALIDATION_FAILED, fails(() -> service.create(d, liveFor("mer_2", "PRM-0000000001"))).code());
    }

    @Test
    void aBusinessThatIsNotTheCallersLooksLikeOneThatDoesNotExist() {
        assertEquals(ErrorCode.BUSINESS_NOT_FOUND, fails(() -> service.create(dev, sandboxFor("mer_9"))).code());
        assertTrue(appRepo.store.isEmpty());
    }

    @Test
    void theAppLimitIsPerBusinessNotPerAccount() {
        Developer d = twoBusinesses();
        AppService small = new AppService(appRepo.repo, new BusinessService(devRepo.repo, wallet, false, 10), wallet,
                new ApiKeyService(keyRepo.repo, TestProps.keys(), clock), new AppContextSync(wallet, appRepo.repo),
                new DeveloperProperties.Apps(1, null), clock);
        small.create(d, sandboxFor("mer_1"));
        assertEquals(ErrorCode.LIMIT_REACHED, fails(() -> small.create(d, sandboxFor("mer_1"))).code());
        assertNotNull(small.create(d, sandboxFor("mer_2")));
    }

    @Test
    void listCanBeNarrowedToOneOfTheCallersBusinesses() {
        DeveloperAppRepository repo = com.premisave.developer.Fake.of(DeveloperAppRepository.class, Map.of(
                "findByDeveloperIdAndMerchantIdAndEnvironmentAndStatus", a -> marker("all " + a[1]),
                "findByDeveloperIdAndMerchantIdAndEnvironment", a -> marker("env " + a[1]),
                "findByDeveloperIdAndMerchantIdAndStatus", a -> marker("status " + a[1]),
                "findByDeveloperIdAndMerchantId", a -> marker("business " + a[1])));
        AppService s = new AppService(repo, new BusinessService(devRepo.repo, wallet, false, 10), wallet, null, null,
                new DeveloperProperties.Apps(null, null), clock);
        assertEquals("all mer_1", label(s.list(dev, "live", AppStatus.ACTIVE, "mer_1", null)));
        assertEquals("env mer_1", label(s.list(dev, "sandbox", null, " mer_1 ", null)));
        assertEquals("status mer_1", label(s.list(dev, null, AppStatus.SUSPENDED, "mer_1", null)));
        assertEquals("business mer_1", label(s.list(dev, null, null, "mer_1", null)));
        assertEquals(ErrorCode.BUSINESS_NOT_FOUND, fails(() -> s.list(dev, null, null, "mer_9", null)).code());
    }
}
