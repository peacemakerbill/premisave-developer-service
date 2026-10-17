package com.premisave.developer.service;

import com.premisave.developer.dto.UpdateAppRequest;
import com.premisave.developer.enums.AppStatus;
import com.premisave.developer.enums.DeveloperStatus;
import com.premisave.developer.enums.Environment;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.enums.KeyStatus;
import com.premisave.developer.enums.SuspendedBy;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.ApiKey;
import com.premisave.developer.model.Developer;
import com.premisave.developer.model.DeveloperApp;
import com.premisave.developer.view.PageViews;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Staff control of apps and keys: search, change, suspend, hand over, delete, and the key lifecycle. */
class AdminAppServiceTest {

    private final AdminFixture f = new AdminFixture();
    private final Developer boss = f.admin("adm_1", "boss@premisave.test");
    private final AdminActor bossActor = AdminActor.of(boss);

    private static DeveloperException fails(Runnable r) {
        return assertThrows(DeveloperException.class, r::run);
    }

    private List<String> found(String developerId, String businessId, String environment, AppStatus status, String name) {
        return f.adminApps.search(developerId, businessId, environment, status, name, PageViews.pageable(0, 20)).getContent().stream()
                .map(DeveloperApp::getAppId).toList();
    }

    private void seed() {
        f.developer("dev_1", "jane@acme.test");
        f.developer("dev_2", "john@acme.test");
        f.wallet.business("mer_1", "Acme");
        f.wallet.business("mer_2", "Beta");
        f.app("app_1", "dev_1", "mer_1", Environment.SANDBOX, "Acme Shop", 1);
        f.app("app_2", "dev_1", "mer_1", Environment.LIVE, "Acme Checkout", 2);
        f.app("app_3", "dev_2", "mer_2", Environment.LIVE, "Beta Pay", 3).setStatus(AppStatus.SUSPENDED);
    }

    // Search

    @Test
    void withNoFiltersEveryAppIsListedNewestFirst() {
        seed();
        assertEquals(List.of("app_3", "app_2", "app_1"), found(null, null, null, null, null));
    }

    @Test
    void eachFilterNarrowsTheList() {
        seed();
        assertEquals(List.of("app_2", "app_1"), found("dev_1", null, null, null, null));
        assertEquals(List.of("app_3"), found(null, "mer_2", null, null, null));
        assertEquals(List.of("app_3", "app_2"), found(null, null, "production", null, null));
        assertEquals(List.of("app_1"), found(null, null, "sandbox", null, null));
        assertEquals(List.of("app_3"), found(null, null, null, AppStatus.SUSPENDED, null));
        assertEquals(List.of("app_2", "app_1"), found(null, null, null, null, "acme"));
    }

    @Test
    void filtersCombine() {
        seed();
        assertEquals(List.of("app_2"), found("dev_1", "mer_1", "live", AppStatus.ACTIVE, "checkout"));
        assertEquals(List.of(), found("dev_2", "mer_1", null, null, null));
    }

    @Test
    void anIdIsMatchedWholeAndANameCannotInjectAPattern() {
        seed();
        assertEquals(List.of(), found("dev_", null, null, null, null));
        assertEquals(List.of(), found(null, null, null, null, ".*"));
        assertEquals(ErrorCode.VALIDATION_FAILED, fails(() -> found(null, null, "staging", null, null)).code());
    }

    @Test
    void anUnknownAppIsNotFound() {
        assertEquals(ErrorCode.APP_NOT_FOUND, fails(() -> f.adminApps.get("app_missing")).code());
        assertEquals(ErrorCode.APP_NOT_FOUND, fails(() -> f.adminApps.delete(bossActor, "app_missing")).code());
        assertEquals(ErrorCode.APP_NOT_FOUND, fails(() -> f.adminApps.keys("app_missing")).code());
    }

    // Change

    @Test
    void anyAppCanBeChangedAndTheWalletIsTold() {
        seed();
        DeveloperApp a = f.adminApps.update(bossActor, "app_1", new UpdateAppRequest("Renamed", "New text", null, null, "http://localhost/hook"));
        assertEquals("Renamed", a.getName());
        assertEquals("New text", a.getDescription());
        assertEquals("http://localhost/hook", a.getCallbackUrl());
        assertFalse(f.wallet.pushed.isEmpty());
        assertEquals(List.of("APP_UPDATED app_1"), f.actions.summary());
    }

    @Test
    void theRulesForTheOwnerStillApplyToStaff() {
        seed();
        DeveloperException e = fails(() -> f.adminApps.update(bossActor, "app_2", new UpdateAppRequest(null, null, null, null, "http://insecure.example.com")));
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code());
    }

    @Test
    void suspendingNeedsNoOwnerAndTheOwnerCannotLiftIt() {
        seed();
        DeveloperApp a = f.adminApps.setStatus(bossActor, "app_2", AppStatus.SUSPENDED, "Chargeback review");
        assertEquals(AppStatus.SUSPENDED, a.getStatus());
        assertEquals(SuspendedBy.ADMIN, a.getSuspendedBy());
        assertEquals("Chargeback review", a.getSuspensionReason());
        DeveloperApp back = f.adminApps.setStatus(bossActor, "app_2", AppStatus.ACTIVE, null);
        assertEquals(AppStatus.ACTIVE, back.getStatus());
        assertEquals(List.of("APP_SUSPENDED app_2", "APP_REACTIVATED app_2"), f.actions.summary());
    }

    // Owner

    @Test
    void anAppCanBeHandedToAnotherAccountWhichGetsTheBusinessAndTheKeysEnd() {
        seed();
        ApiKeyService.Issued key = f.keys.issue(f.apps.store.get("app_2"), "Default");
        DeveloperApp moved = f.adminApps.changeOwner(bossActor, "app_2", "dev_2");
        assertEquals("dev_2", moved.getDeveloperId());
        assertTrue(f.devs.store.get("dev_2").ownsBusiness("mer_1"));
        assertFalse(f.devs.store.get("dev_2").appliedFor("mer_1"));
        assertEquals(KeyStatus.REVOKED, f.keyRepo.store.get(key.key().getKey()).getStatus());
        assertEquals(List.of("APP_OWNER_CHANGED app_2"), f.actions.summary());
        assertFalse(f.wallet.pushed.isEmpty());
    }

    @Test
    void anAppCannotGoToAnUnknownInactiveOrCurrentOwner() {
        seed();
        assertEquals(ErrorCode.NOT_FOUND, fails(() -> f.adminApps.changeOwner(bossActor, "app_2", "dev_none")).code());
        assertEquals(ErrorCode.INVALID_STATE, fails(() -> f.adminApps.changeOwner(bossActor, "app_2", "dev_1")).code());
        f.devs.store.get("dev_2").setStatus(DeveloperStatus.DISABLED);
        assertEquals(ErrorCode.INVALID_STATE, fails(() -> f.adminApps.changeOwner(bossActor, "app_2", "dev_2")).code());
        assertEquals("dev_1", f.apps.store.get("app_2").getDeveloperId());
    }

    // Delete

    @Test
    void deletingTellsTheWalletThenRemovesTheAppAndItsKeys() {
        seed();
        f.keys.issue(f.apps.store.get("app_2"), "Default");
        f.adminApps.delete(bossActor, "app_2");
        assertFalse(f.apps.store.containsKey("app_2"));
        assertTrue(f.keyRepo.store.isEmpty());
        assertTrue(f.wallet.pushed.stream().anyMatch(a -> a.getAppId().equals("app_2") && a.getStatus() == AppStatus.SUSPENDED));
        assertEquals(List.of("APP_DELETED app_2"), f.actions.summary());
    }

    @Test
    void ifTheWalletCannotBeToldTheAppIsSuspendedAndKept() {
        seed();
        f.keys.issue(f.apps.store.get("app_2"), "Default");
        f.wallet.pushFails = true;
        DeveloperException e = fails(() -> f.adminApps.delete(bossActor, "app_2"));
        assertEquals(ErrorCode.INVALID_STATE, e.code());
        assertTrue(f.apps.store.containsKey("app_2"));
        assertEquals(AppStatus.SUSPENDED, f.apps.store.get("app_2").getStatus());
        assertEquals(1, f.keyRepo.store.size());
        assertTrue(f.actions.store.isEmpty());
    }

    // Keys

    @Test
    void keysCanBeIssuedListedRotatedAndRevoked() {
        seed();
        ApiKeyService.Issued first = f.adminApps.issueKey(bossActor, "app_1", "Staff test");
        assertTrue(first.secret().startsWith("sk_sandbox_"));
        assertEquals("Staff test", first.key().getLabel());
        assertEquals(1, f.adminApps.keys("app_1").size());

        ApiKeyService.Issued second = f.adminApps.rotateKey(bossActor, "app_1", first.key().getKey());
        assertNotNull(second.secret());
        assertEquals(2, f.adminApps.keys("app_1").size());

        ApiKey revoked = f.adminApps.revokeKey(bossActor, "app_1", second.key().getKey());
        assertEquals(KeyStatus.REVOKED, revoked.getStatus());
        assertEquals(List.of("KEY_ISSUED " + first.key().getKey(), "KEY_ROTATED " + first.key().getKey(),
                "KEY_REVOKED " + second.key().getKey()), f.actions.summary());
    }

    @Test
    void everyKeyOfAnAppCanBeRevokedAtOnceAndTheCountIsReported() {
        seed();
        f.adminApps.issueKey(bossActor, "app_1", null);
        f.adminApps.issueKey(bossActor, "app_1", null);
        assertEquals(2, f.adminApps.revokeAllKeys(bossActor, "app_1"));
        assertEquals(0, f.adminApps.revokeAllKeys(bossActor, "app_1"));
        assertTrue(f.adminApps.keys("app_1").stream().allMatch(k -> k.getStatus() == KeyStatus.REVOKED));
    }

    @Test
    void aKeyOfAnotherAppIsNotFound() {
        seed();
        ApiKeyService.Issued other = f.adminApps.issueKey(bossActor, "app_2", null);
        assertEquals(ErrorCode.KEY_NOT_FOUND, fails(() -> f.adminApps.revokeKey(bossActor, "app_1", other.key().getKey())).code());
    }
}
