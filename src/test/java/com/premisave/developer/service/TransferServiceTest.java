package com.premisave.developer.service;

import com.premisave.developer.TestProps;
import com.premisave.developer.TestRepos;
import com.premisave.developer.client.WalletApi;
import com.premisave.developer.config.DeveloperProperties;
import com.premisave.developer.enums.DeveloperStatus;
import com.premisave.developer.enums.Environment;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.enums.KeyStatus;
import com.premisave.developer.enums.TransferStatus;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.Developer;
import com.premisave.developer.model.DeveloperApp;
import com.premisave.developer.model.TransferRequest;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Moving a production app to another account and taking up a business by its merchant number: where the code goes,
 * what a wrong or late code does, what moves and what stays, and the throttles.
 */
class TransferServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-17T08:00:00Z");

    /** A clock the test can move forward. */
    private static final class TestClock extends Clock {
        Instant now = T0;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    /** A wallet service holding businesses by id. */
    private static final class FakeWallet extends WalletApi {
        final Map<String, Map<String, Object>> byId = new HashMap<>();
        final List<DeveloperApp> pushed = new ArrayList<>();

        FakeWallet() {
            super(null, null);
        }

        Map<String, Object> business(String id, String number, String live, String walletAccount, String contactEmail) {
            Map<String, Object> m = new HashMap<>();
            m.put("merchantId", id);
            m.put("merchantNumber", number);
            m.put("businessName", "Acme Ltd");
            m.put("sandbox", Map.of("status", "ACTIVE"));
            m.put("live", Map.of("status", live));
            Map<String, Object> onboarding = new HashMap<>();
            if (walletAccount != null) onboarding.put("walletAccount", walletAccount);
            if (contactEmail != null) onboarding.put("contactEmail", contactEmail);
            m.put("onboarding", onboarding);
            byId.put(id, m);
            return m;
        }

        @Override
        public Map<String, Object> getMerchantView(String merchantId) {
            Map<String, Object> m = byId.get(merchantId);
            if (m == null) throw new DeveloperException(ErrorCode.MERCHANT_NOT_FOUND, "Merchant not found");
            return m;
        }

        @Override
        public Map<String, Object> getMerchantViewByNumber(String merchantNumber) {
            return byId.values().stream().filter(m -> merchantNumber.equals(m.get("merchantNumber"))).findFirst()
                    .orElseThrow(() -> new DeveloperException(ErrorCode.MERCHANT_NOT_FOUND, "Merchant not found"));
        }

        @Override
        public void pushAppContext(DeveloperApp app) {
            pushed.add(app);
        }
    }

    private final TestRepos.Developers devs = new TestRepos.Developers();
    private final TestRepos.Apps apps = new TestRepos.Apps();
    private final TestRepos.Keys keys = new TestRepos.Keys();
    private final TestRepos.Transfers rows = new TestRepos.Transfers();
    private final TestRepos.RecordingMailer mailer = new TestRepos.RecordingMailer();
    private final FakeWallet wallet = new FakeWallet();
    private final TestClock clock = new TestClock();
    private final List<String> issued = new ArrayList<>(List.of("111111", "222222", "333333", "444444", "555555", "666666", "777777", "888888"));
    private final Supplier<String> codes = () -> issued.remove(0);

    private final Developer owner = developer("dev_1", "owner@dev.test", "Olivia", DeveloperStatus.ACTIVE);
    private final Developer guest = developer("dev_2", "new@dev.test", "Nia", DeveloperStatus.ACTIVE);
    private DeveloperApp app;
    private ApiKeyService apiKeys;

    TransferServiceTest() {
        wallet.business("mer_1", "PRM-0000000001", "ACTIVE", "owner@acme.test", "contact@acme.test");
        owner.addBusiness("mer_1");
        app = liveApp("app_1", "dev_1");
        apiKeys = new ApiKeyService(keys.repo, TestProps.keys(), clock);
        apiKeys.issue(app, "Default");
        apiKeys.issue(app, "Second");
    }

    private static Developer developer(String id, String email, String first, DeveloperStatus status) {
        Developer d = new Developer();
        d.setDeveloperId(id);
        d.setEmail(email);
        d.setFirstName(first);
        d.setFullName(first + " Tester");
        d.setStatus(status);
        d.setEmailVerified(true);
        return d;
    }

    private DeveloperApp liveApp(String id, String developerId) {
        DeveloperApp a = new DeveloperApp();
        a.setAppId(id);
        a.setDeveloperId(developerId);
        a.setMerchantId("mer_1");
        a.setEnvironment(Environment.LIVE);
        a.setName("Shop");
        a.setMerchantNumber("PRM-0000000001");
        a.setPremisaveWalletId("w1");
        a.setPremisaveWalletAccount("owner@acme.test");
        a.setCreatedAt(T0);
        apps.store.put(id, a);
        return a;
    }

    private TransferService service() {
        return service(TransferService.Rules.DEFAULT, 10);
    }

    private TransferService service(TransferService.Rules rules, int maxBusinesses) {
        devs.store.put(owner.getDeveloperId(), owner);
        devs.store.put(guest.getDeveloperId(), guest);
        BusinessService businesses = new BusinessService(devs.repo, wallet, false, maxBusinesses);
        AppContextSync sync = new AppContextSync(wallet, apps.repo);
        AppService appService = new AppService(apps.repo, businesses, wallet, apiKeys, sync,
                new DeveloperProperties.Apps(null, null), clock);
        MailService mail = new MailService(mailer, Runnable::run, false);
        return new TransferService(rows.repo, devs.repo, apps.repo, appService, businesses, wallet, apiKeys, sync, mail,
                TestProps.PEPPER, "https://portal.example.com", rules, codes, clock);
    }

    private static DeveloperException fails(Runnable r) {
        return assertThrows(DeveloperException.class, r::run);
    }

    private static Object field(Map<String, Object> view, String name) {
        return view.get(name);
    }

    // Starting an app transfer

    @Test
    void theCodeGoesToTheWalletAccountOnTheApplicationAndNeverToTheRequester() {
        Map<String, Object> view = service().startAppTransfer(owner, "app_1", "NEW@dev.test");
        assertEquals(1, mailer.sent.size());
        TestRepos.RecordingMailer.Sent mail = mailer.sent.get(0);
        assertEquals("owner@acme.test", mail.to());
        assertTrue(mail.subject().contains("111111"));
        assertTrue(mail.html().contains("111111"));
        assertTrue(mail.html().contains("Acme Ltd"));
        assertTrue(mail.html().contains("PRM-0000000001"));
        assertTrue(mail.html().contains("Olivia Tester"));
        assertFalse(view.toString().contains("111111"));
        assertEquals("PENDING", field(view, "status"));
        assertEquals("APP_TRANSFER", field(view, "type"));
        assertEquals("OUTGOING", field(view, "direction"));
        assertEquals("o****@acme.test".length(), String.valueOf(field(view, "codeSentTo")).length());
        assertFalse(String.valueOf(field(view, "codeSentTo")).contains("owner@"));
        assertEquals(5, field(view, "attemptsLeft"));
    }

    @Test
    void onlyAHashOfTheCodeIsStored() {
        service().startAppTransfer(owner, "app_1", "new@dev.test");
        TransferRequest t = rows.store.values().iterator().next();
        assertNotEquals("111111", t.getOtpHash());
        assertEquals(64, t.getOtpHash().length());
        assertFalse(t.getOtpHash().contains("111111"));
        assertEquals(T0.plus(Duration.ofMinutes(15)), t.getExpiresAt());
    }

    @Test
    void withNoWalletOnTheApplicationTheBusinessContactAddressIsUsed() {
        wallet.business("mer_1", "PRM-0000000001", "ACTIVE", null, "contact@acme.test");
        service().startAppTransfer(owner, "app_1", "new@dev.test");
        assertEquals("contact@acme.test", mailer.sent.get(0).to());
    }

    @Test
    void withNoAddressAnywhereTheTransferIsRefusedAndNothingIsStored() {
        wallet.business("mer_1", "PRM-0000000001", "ACTIVE", null, null);
        app.setPremisaveWalletAccount(null);
        DeveloperException e = fails(() -> service().startAppTransfer(owner, "app_1", "new@dev.test"));
        assertEquals(ErrorCode.INVALID_STATE, e.code());
        assertTrue(rows.store.isEmpty());
        assertTrue(mailer.sent.isEmpty());
    }

    @Test
    void onlyAProductionAppCanBeTransferred() {
        app.setEnvironment(Environment.SANDBOX);
        assertEquals(ErrorCode.INVALID_STATE, fails(() -> service().startAppTransfer(owner, "app_1", "new@dev.test")).code());
    }

    @Test
    void anAppOfAnotherAccountLooksLikeNoApp() {
        assertEquals(ErrorCode.APP_NOT_FOUND, fails(() -> service().startAppTransfer(guest, "app_1", "owner@dev.test")).code());
        assertEquals(ErrorCode.APP_NOT_FOUND, fails(() -> service().startAppTransfer(owner, "app_9", "new@dev.test")).code());
    }

    @Test
    void theRecipientMustBeAnotherActiveAccount() {
        TransferService s = service();
        assertEquals(ErrorCode.VALIDATION_FAILED, fails(() -> s.startAppTransfer(owner, "app_1", "owner@dev.test")).code());
        assertEquals(ErrorCode.VALIDATION_FAILED, fails(() -> s.startAppTransfer(owner, "app_1", "nobody@dev.test")).code());
        guest.setStatus(DeveloperStatus.PENDING);
        assertEquals(ErrorCode.VALIDATION_FAILED, fails(() -> s.startAppTransfer(owner, "app_1", "new@dev.test")).code());
        guest.setStatus(DeveloperStatus.DISABLED);
        assertEquals(ErrorCode.VALIDATION_FAILED, fails(() -> s.startAppTransfer(owner, "app_1", "new@dev.test")).code());
        assertTrue(rows.store.isEmpty());
        assertTrue(mailer.sent.isEmpty());
    }

    @Test
    void theRecipientMustHaveRoomForTheBusiness() {
        guest.addBusiness("mer_5");
        DeveloperException e = fails(() -> service(TransferService.Rules.DEFAULT, 1).startAppTransfer(owner, "app_1", "new@dev.test"));
        assertEquals(ErrorCode.LIMIT_REACHED, e.code());
        assertTrue(rows.store.isEmpty());
    }

    // Confirming an app transfer

    @Test
    void theRightCodeMovesTheAppSharesTheBusinessAndRevokesTheKeys() {
        TransferService s = service();
        String id = String.valueOf(s.startAppTransfer(owner, "app_1", "new@dev.test").get("transferId"));
        Map<String, Object> done = s.confirm(owner, id, "111111");

        assertEquals("COMPLETED", done.get("status"));
        assertEquals("dev_2", app.getDeveloperId());
        assertEquals("w1", app.getPremisaveWalletId());
        assertEquals("PRM-0000000001", app.getMerchantNumber());
        assertTrue(guest.ownsBusiness("mer_1"));
        assertFalse(guest.appliedFor("mer_1"));
        assertEquals(List.of("mer_1"), guest.getSharedMerchantIds());
        assertTrue(owner.appliedFor("mer_1"));
        assertTrue(keys.store.values().stream().allMatch(k -> k.getStatus() == KeyStatus.REVOKED));
        assertEquals(2, keys.store.size());
        assertTrue(wallet.pushed.contains(app));
        assertEquals(TransferStatus.COMPLETED, rows.store.get(id).getStatus());
    }

    @Test
    void bothAccountsAreToldWhenTheAppHasMoved() {
        TransferService s = service();
        String id = String.valueOf(s.startAppTransfer(owner, "app_1", "new@dev.test").get("transferId"));
        s.confirm(owner, id, "111111");
        assertEquals(3, mailer.sent.size());
        TestRepos.RecordingMailer.Sent received = mailer.sent.get(1);
        TestRepos.RecordingMailer.Sent handed = mailer.sent.get(2);
        assertEquals("new@dev.test", received.to());
        assertTrue(received.html().contains("Hello Nia"));
        assertTrue(received.html().contains("Olivia"));
        assertEquals("owner@dev.test", handed.to());
        assertTrue(handed.html().contains("Hello Olivia"));
        assertTrue(handed.html().contains("n***"));
        assertFalse(handed.html().contains("new@dev.test"));
    }

    @Test
    void aWrongCodeIsCountedAndFiveCloseTheRequest() {
        TransferService s = service();
        String id = String.valueOf(s.startAppTransfer(owner, "app_1", "new@dev.test").get("transferId"));
        DeveloperException first = fails(() -> s.confirm(owner, id, "000000"));
        assertEquals(ErrorCode.OTP_INVALID, first.code());
        assertEquals(4, first.extra().get("attemptsLeft"));
        for (int i = 0; i < 3; i++) fails(() -> s.confirm(owner, id, "000000"));
        DeveloperException last = fails(() -> s.confirm(owner, id, "000000"));
        assertEquals(ErrorCode.OTP_INVALID, last.code());
        assertEquals(0, last.extra().get("attemptsLeft"));
        assertTrue(last.getMessage().contains("start it again"));
        assertEquals(TransferStatus.CANCELLED, rows.store.get(id).getStatus());

        // The right code is no use after that.
        assertEquals(ErrorCode.INVALID_STATE, fails(() -> s.confirm(owner, id, "111111")).code());
        assertEquals("dev_1", app.getDeveloperId());
        assertFalse(guest.ownsBusiness("mer_1"));
        assertTrue(keys.store.values().stream().allMatch(k -> k.getStatus() != KeyStatus.REVOKED));
    }

    @Test
    void aCodeAfterItsTimeIsRefusedAndShownAsExpired() {
        TransferService s = service();
        String id = String.valueOf(s.startAppTransfer(owner, "app_1", "new@dev.test").get("transferId"));
        clock.advance(Duration.ofMinutes(14));
        assertEquals("PENDING", s.get(owner, id).get("status"));
        clock.advance(Duration.ofMinutes(1));
        assertEquals("EXPIRED", s.get(owner, id).get("status"));
        assertEquals(ErrorCode.OTP_EXPIRED, fails(() -> s.confirm(owner, id, "111111")).code());
        assertEquals(410, ErrorCode.OTP_EXPIRED.httpStatus());
        assertEquals("dev_1", app.getDeveloperId());
    }

    @Test
    void aCodeWorksOnce() {
        TransferService s = service();
        String id = String.valueOf(s.startAppTransfer(owner, "app_1", "new@dev.test").get("transferId"));
        s.confirm(owner, id, "111111");
        assertEquals(ErrorCode.INVALID_STATE, fails(() -> s.confirm(owner, id, "111111")).code());
    }

    @Test
    void onlyTheAccountThatAskedCanConfirmOrCancel() {
        TransferService s = service();
        String id = String.valueOf(s.startAppTransfer(owner, "app_1", "new@dev.test").get("transferId"));
        assertEquals(ErrorCode.TRANSFER_NOT_FOUND, fails(() -> s.confirm(guest, id, "111111")).code());
        assertEquals(ErrorCode.TRANSFER_NOT_FOUND, fails(() -> s.cancel(guest, id)).code());
        assertEquals(ErrorCode.TRANSFER_NOT_FOUND, fails(() -> s.resend(guest, id)).code());
        assertEquals(ErrorCode.TRANSFER_NOT_FOUND, fails(() -> s.confirm(owner, "trf_missing", "111111")).code());
        assertEquals("dev_1", app.getDeveloperId());
    }

    @Test
    void theRecipientSeesTheAppOnItsWayWithoutTheCodeDetails() {
        TransferService s = service();
        String id = String.valueOf(s.startAppTransfer(owner, "app_1", "new@dev.test").get("transferId"));
        Map<String, Object> seen = s.get(guest, id);
        assertEquals("INCOMING", seen.get("direction"));
        assertEquals("PENDING", seen.get("status"));
        assertFalse(seen.containsKey("codeSentTo"));
        assertFalse(seen.containsKey("attemptsLeft"));
        assertFalse(seen.containsKey("to"));
        assertTrue(String.valueOf(seen.get("from")).startsWith("o"));
        assertFalse(String.valueOf(seen.get("from")).contains("owner@"));
        assertEquals(1, s.list(guest).get("count"));
        assertEquals(1, s.list(owner).get("count"));
    }

    @Test
    void anotherAccountCannotSeeATransfer() {
        TransferService s = service();
        String id = String.valueOf(s.startAppTransfer(owner, "app_1", "new@dev.test").get("transferId"));
        Developer stranger = developer("dev_3", "x@dev.test", "Xena", DeveloperStatus.ACTIVE);
        assertEquals(ErrorCode.TRANSFER_NOT_FOUND, fails(() -> s.get(stranger, id)).code());
        assertEquals(0, s.list(stranger).get("count"));
    }

    @Test
    void ifTheNewAccountHasNoRoomAnymoreNothingMovesAndTheKeysStayValid() {
        TransferService s = service(TransferService.Rules.DEFAULT, 2);
        String id = String.valueOf(s.startAppTransfer(owner, "app_1", "new@dev.test").get("transferId"));
        guest.addBusiness("mer_5");
        guest.addBusiness("mer_6");
        DeveloperException e = fails(() -> s.confirm(owner, id, "111111"));
        assertEquals(ErrorCode.LIMIT_REACHED, e.code());
        assertEquals("dev_1", app.getDeveloperId());
        assertTrue(keys.store.values().stream().allMatch(k -> k.getStatus() != KeyStatus.REVOKED));
        assertEquals(TransferStatus.PENDING, rows.store.get(id).getStatus());
    }

    @Test
    void ifTheAppIsGoneByConfirmationTheRequestFailsAndNothingIsShared() {
        TransferService s = service();
        String id = String.valueOf(s.startAppTransfer(owner, "app_1", "new@dev.test").get("transferId"));
        app.setDeveloperId("dev_9");
        assertEquals(ErrorCode.APP_NOT_FOUND, fails(() -> s.confirm(owner, id, "111111")).code());
        assertFalse(guest.ownsBusiness("mer_1"));
    }

    // Cancelling, asking again, and the throttle

    @Test
    void cancellingClosesTheRequestForGood() {
        TransferService s = service();
        String id = String.valueOf(s.startAppTransfer(owner, "app_1", "new@dev.test").get("transferId"));
        assertEquals("CANCELLED", s.cancel(owner, id).get("status"));
        assertEquals("CANCELLED", s.cancel(owner, id).get("status"));
        assertEquals(ErrorCode.INVALID_STATE, fails(() -> s.confirm(owner, id, "111111")).code());
    }

    @Test
    void aCompletedTransferCannotBeCancelled() {
        TransferService s = service();
        String id = String.valueOf(s.startAppTransfer(owner, "app_1", "new@dev.test").get("transferId"));
        s.confirm(owner, id, "111111");
        assertEquals(ErrorCode.INVALID_STATE, fails(() -> s.cancel(owner, id)).code());
    }

    @Test
    void aNewRequestForTheSameAppReplacesTheOldOne() {
        TransferService s = service();
        String first = String.valueOf(s.startAppTransfer(owner, "app_1", "new@dev.test").get("transferId"));
        clock.advance(Duration.ofSeconds(61));
        String second = String.valueOf(s.startAppTransfer(owner, "app_1", "new@dev.test").get("transferId"));
        assertEquals(TransferStatus.CANCELLED, rows.store.get(first).getStatus());
        assertEquals(ErrorCode.INVALID_STATE, fails(() -> s.confirm(owner, first, "111111")).code());
        assertEquals("COMPLETED", s.confirm(owner, second, "222222").get("status"));
    }

    @Test
    void codesAreSpacedByTheCooldown() {
        TransferService s = service();
        s.startAppTransfer(owner, "app_1", "new@dev.test");
        DeveloperException e = fails(() -> s.startAppTransfer(owner, "app_1", "new@dev.test"));
        assertEquals(ErrorCode.RATE_LIMITED, e.code());
        assertEquals(60L, e.extra().get("retryAfterSeconds"));
        assertEquals(1, mailer.sent.size());
        clock.advance(Duration.ofSeconds(59));
        assertEquals(1L, fails(() -> s.startAppTransfer(owner, "app_1", "new@dev.test")).extra().get("retryAfterSeconds"));
        clock.advance(Duration.ofSeconds(1));
        s.startAppTransfer(owner, "app_1", "new@dev.test");
        assertEquals(2, mailer.sent.size());
    }

    @Test
    void thereIsACapOnRequestsInAnHour() {
        TransferService s = service(new TransferService.Rules(15, 1, 3, 7), 10);
        for (int i = 0; i < 3; i++) {
            s.startAppTransfer(owner, "app_1", "new@dev.test");
            clock.advance(Duration.ofSeconds(2));
        }
        DeveloperException e = fails(() -> s.startAppTransfer(owner, "app_1", "new@dev.test"));
        assertEquals(ErrorCode.RATE_LIMITED, e.code());
        assertEquals(429, e.code().httpStatus());
        clock.advance(Duration.ofHours(1));
        s.startAppTransfer(owner, "app_1", "new@dev.test");
    }

    @Test
    void aNewCodeReplacesTheOldOneAndGivesFreshTries() {
        TransferService s = service();
        String id = String.valueOf(s.startAppTransfer(owner, "app_1", "new@dev.test").get("transferId"));
        fails(() -> s.confirm(owner, id, "000000"));
        clock.advance(Duration.ofSeconds(61));
        Map<String, Object> view = s.resend(owner, id);
        assertEquals(5, view.get("attemptsLeft"));
        assertEquals(2, mailer.sent.size());
        assertTrue(mailer.sent.get(1).html().contains("222222"));
        assertEquals(ErrorCode.OTP_INVALID, fails(() -> s.confirm(owner, id, "111111")).code());
        assertEquals("COMPLETED", s.confirm(owner, id, "222222").get("status"));
    }

    @Test
    void aClosedRequestCannotBeSentAgain() {
        TransferService s = service();
        String id = String.valueOf(s.startAppTransfer(owner, "app_1", "new@dev.test").get("transferId"));
        s.cancel(owner, id);
        clock.advance(Duration.ofSeconds(61));
        assertEquals(ErrorCode.INVALID_STATE, fails(() -> s.resend(owner, id)).code());
    }

    // Claiming a business by its number

    @Test
    void aClaimNeedsTheOwnersCodeAndMovesNoApp() {
        TransferService s = service();
        Map<String, Object> view = s.startClaim(guest, " prm-0000000001 ");
        assertEquals("BUSINESS_CLAIM", view.get("type"));
        assertEquals("owner@acme.test", mailer.sent.get(0).to());
        assertTrue(mailer.sent.get(0).html().contains("111111"));
        assertFalse(guest.ownsBusiness("mer_1"));

        String id = String.valueOf(view.get("transferId"));
        assertEquals(ErrorCode.OTP_INVALID, fails(() -> s.confirm(guest, id, "999999")).code());
        assertFalse(guest.ownsBusiness("mer_1"));

        assertEquals("COMPLETED", s.confirm(guest, id, "111111").get("status"));
        assertTrue(guest.ownsBusiness("mer_1"));
        assertFalse(guest.appliedFor("mer_1"));
        assertEquals("dev_1", app.getDeveloperId());
        assertTrue(keys.store.values().stream().allMatch(k -> k.getStatus() != KeyStatus.REVOKED));
        assertEquals(1, mailer.sent.size());
    }

    @Test
    void anUnknownNumberAndABusinessNotLiveGetTheSameAnswer() {
        wallet.business("mer_2", "PRM-0000000002", "PENDING_APPROVAL", "x@acme.test", null);
        TransferService s = service();
        DeveloperException unknown = fails(() -> s.startClaim(guest, "PRM-0000000099"));
        DeveloperException pending = fails(() -> s.startClaim(guest, "PRM-0000000002"));
        assertEquals(ErrorCode.BUSINESS_NOT_FOUND, unknown.code());
        assertEquals(ErrorCode.BUSINESS_NOT_FOUND, pending.code());
        assertEquals(unknown.getMessage(), pending.getMessage());
        assertTrue(mailer.sent.isEmpty());
    }

    @Test
    void guessingNumbersRunsIntoTheHourlyCapAndLeavesNoTrailInTheList() {
        TransferService s = service(new TransferService.Rules(15, 60, 3, 7), 10);
        for (int i = 0; i < 3; i++) {
            assertEquals(ErrorCode.BUSINESS_NOT_FOUND, fails(() -> s.startClaim(guest, "PRM-00000000" + (10 + issued.size()))).code());
        }
        assertEquals(ErrorCode.RATE_LIMITED, fails(() -> s.startClaim(guest, "PRM-0000000001")).code());
        assertEquals(0, s.list(guest).get("count"));
        assertTrue(rows.store.values().stream().allMatch(t -> t.getStatus() == TransferStatus.REJECTED));
        assertTrue(rows.store.values().stream().allMatch(t -> t.getPurgeAt() != null));
    }

    @Test
    void aBusinessAlreadyOnTheAccountIsNotClaimedAgain() {
        TransferService s = service();
        DeveloperException own = fails(() -> s.startClaim(owner, "PRM-0000000001"));
        assertEquals(ErrorCode.BUSINESS_EXISTS, own.code());
        assertEquals("mer_1", own.extra().get("businessId"));
        guest.addSharedBusiness("mer_1");
        assertEquals(ErrorCode.BUSINESS_EXISTS, fails(() -> s.startClaim(guest, "PRM-0000000001")).code());
        assertTrue(mailer.sent.isEmpty());
    }

    @Test
    void aBlankNumberIsAMissLikeAnyOther() {
        TransferService s = service();
        assertEquals(ErrorCode.BUSINESS_NOT_FOUND, fails(() -> s.startClaim(guest, "   ")).code());
        assertEquals(ErrorCode.BUSINESS_NOT_FOUND, fails(() -> s.startClaim(guest, null)).code());
    }

    @Test
    void aClaimNeedsRoomForAnotherBusiness() {
        guest.addBusiness("mer_5");
        assertEquals(ErrorCode.LIMIT_REACHED, fails(() -> service(TransferService.Rules.DEFAULT, 1).startClaim(guest, "PRM-0000000001")).code());
    }

    @Test
    void aClaimIsNotShownToTheOwnerOfTheBusiness() {
        TransferService s = service();
        s.startClaim(guest, "PRM-0000000001");
        assertEquals(0, s.list(owner).get("count"));
    }

    // The address rules

    @Test
    void theApplicationWalletComesFirstThenContactThenTheAppsWallet() {
        Map<String, Object> both = wallet.business("m", "n", "ACTIVE", "w@acme.test", "c@acme.test");
        assertEquals("w@acme.test", TransferService.confirmationAddress(both, "app@acme.test"));
        Map<String, Object> contactOnly = wallet.business("m", "n", "ACTIVE", null, "c@acme.test");
        assertEquals("c@acme.test", TransferService.confirmationAddress(contactOnly, "app@acme.test"));
        Map<String, Object> neither = wallet.business("m", "n", "ACTIVE", null, null);
        assertEquals("app@acme.test", TransferService.confirmationAddress(neither, "app@acme.test"));
        assertEquals(ErrorCode.INVALID_STATE, fails(() -> TransferService.confirmationAddress(neither, null)).code());
        assertEquals(ErrorCode.INVALID_STATE, fails(() -> TransferService.confirmationAddress(neither, "not-an-address")).code());
        assertNull(neither.get("walletAccount"));
    }
}
