package com.premisave.developer.service;

import com.premisave.developer.dto.AdminCreateDeveloperRequest;
import com.premisave.developer.dto.UpdateProfileRequest;
import com.premisave.developer.enums.DeveloperRole;
import com.premisave.developer.enums.DeveloperStatus;
import com.premisave.developer.enums.Environment;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.Developer;
import com.premisave.developer.model.OAuthIdentity;
import com.premisave.developer.view.PageViews;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Staff control of developer accounts, and the rules that stop staff from locking themselves out. */
class AdminDeveloperServiceTest {

    private final AdminFixture f = new AdminFixture();
    private final Developer boss = f.admin("adm_1", "boss@premisave.test");
    private final AdminActor bossActor = AdminActor.of(boss);

    private static AdminCreateDeveloperRequest create(String email, String role, Boolean invite) {
        return new AdminCreateDeveloperRequest(email, "Jane", null, "Doe", "ke", null, "Acme", role, invite);
    }

    private static DeveloperException fails(Runnable r) {
        return assertThrows(DeveloperException.class, r::run);
    }

    // Create

    @Test
    void anAccountMadeByStaffIsActiveConfirmedAndAdeveloperByDefault() {
        Map<String, Object> out = f.adminDevelopers.create(bossActor, create("Jane@Acme.test", null, null));
        Developer d = f.devs.store.get((String) out.get("developerId"));
        assertEquals("jane@acme.test", d.getEmail());
        assertEquals(DeveloperStatus.ACTIVE, d.getStatus());
        assertTrue(d.isEmailVerified());
        assertEquals(DeveloperRole.DEVELOPER, d.getRole());
        assertNull(d.getPasswordHash());
        assertEquals("Jane Doe", d.getFullName());
        assertEquals("KE", d.getCountry());
        assertNotNull(d.getUsername());
    }

    @Test
    void theNewPersonIsInvitedToChooseAPassword() {
        Map<String, Object> out = f.adminDevelopers.create(bossActor, create("jane@acme.test", null, null));
        assertEquals(true, out.get("inviteSent"));
        assertEquals(1, f.mailer.sent.size());
        assertEquals("jane@acme.test", f.mailer.sent.get(0).to());
        assertTrue(f.mailer.sent.get(0).html().contains("/reset-password?token="));
    }

    @Test
    void theInvitationCanBeLeftOut() {
        Map<String, Object> out = f.adminDevelopers.create(bossActor, create("jane@acme.test", null, false));
        assertEquals(false, out.get("inviteSent"));
        assertTrue(f.mailer.sent.isEmpty());
    }

    @Test
    void anAdministratorCanBeCreated() {
        Map<String, Object> out = f.adminDevelopers.create(bossActor, create("ops@premisave.test", "admin", false));
        assertEquals("ADMIN", out.get("role"));
    }

    @Test
    void anAddressInUseOrAnUnknownRoleIsRefused() {
        f.developer("dev_1", "jane@acme.test");
        assertEquals(ErrorCode.EMAIL_TAKEN, fails(() -> f.adminDevelopers.create(bossActor, create("JANE@acme.test", null, null))).code());
        assertEquals(ErrorCode.VALIDATION_FAILED, fails(() -> f.adminDevelopers.create(bossActor, create("x@acme.test", "owner", null))).code());
        assertEquals(ErrorCode.VALIDATION_FAILED, fails(() -> f.adminDevelopers.create(bossActor, create("not-an-email", null, null))).code());
    }

    @Test
    void creatingIsRecordedInTheAuditTrail() {
        Map<String, Object> out = f.adminDevelopers.create(bossActor, create("jane@acme.test", null, false));
        assertEquals(List.of("DEVELOPER_CREATED " + out.get("developerId")), f.actions.summary());
        assertEquals("adm_1", f.actions.store.get(0).getActorId());
    }

    // Read

    @Test
    void searchFindsAccountsAndAdministratorsAreListedApart() {
        f.developer("dev_1", "jane@acme.test");
        f.developer("dev_2", "john@example.com");
        assertEquals(List.of("dev_1"), ids(f.adminDevelopers.search("acme", null, PageViews.pageable(0, 20)).getContent()));
        assertEquals(List.of("adm_1"), ids(f.adminDevelopers.administrators(PageViews.pageable(0, 20)).getContent()));
        assertEquals(ErrorCode.NOT_FOUND, fails(() -> f.adminDevelopers.get("dev_missing")).code());
    }

    private static List<String> ids(List<Developer> list) {
        return list.stream().map(Developer::getDeveloperId).toList();
    }

    // Update

    @Test
    void theProfileCanBeChangedAndTheChangeIsRecorded() {
        f.developer("dev_1", "jane@acme.test");
        UpdateProfileRequest req = new UpdateProfileRequest(null, null, "Mwangi", null, "UG", null, "Mwangi Co",
                null, null, null, null, null, null, null);
        Developer d = f.adminDevelopers.updateProfile(bossActor, "dev_1", req);
        assertEquals("Mwangi", d.getLastName());
        assertEquals("Mwangi Co", d.getCompanyName());
        assertEquals(List.of("DEVELOPER_UPDATED dev_1"), f.actions.summary());
    }

    @Test
    void theEmailAddressCanBeChangedAndEverySessionEnds() {
        Developer d = f.developer("dev_1", "jane@acme.test");
        int before = d.getTokenVersion();
        Developer changed = f.adminDevelopers.setEmail(bossActor, "dev_1", "Jane.New@Acme.test", null);
        assertEquals("jane.new@acme.test", changed.getEmail());
        assertTrue(changed.isEmailVerified());
        assertEquals(before + 1, changed.getTokenVersion());
        assertTrue(f.mailer.sent.isEmpty());
    }

    @Test
    void anUnconfirmedNewAddressGetsAConfirmationLink() {
        f.developer("dev_1", "jane@acme.test");
        Developer changed = f.adminDevelopers.setEmail(bossActor, "dev_1", "new@acme.test", false);
        assertFalse(changed.isEmailVerified());
        assertEquals(1, f.mailer.sent.size());
        assertEquals("new@acme.test", f.mailer.sent.get(0).to());
        assertTrue(f.mailer.sent.get(0).html().contains("/verify-email?token="));
    }

    @Test
    void anAddressAnotherAccountUsesIsRefused() {
        f.developer("dev_1", "jane@acme.test");
        f.developer("dev_2", "john@acme.test");
        assertEquals(ErrorCode.EMAIL_TAKEN, fails(() -> f.adminDevelopers.setEmail(bossActor, "dev_1", "john@acme.test", null)).code());
        assertEquals("jane@acme.test", f.devs.store.get("dev_1").getEmail());
        f.adminDevelopers.setEmail(bossActor, "dev_1", "jane@acme.test", null);
    }

    // Role

    @Test
    void anActiveAccountCanBeMadeAnAdministratorAndBack() {
        f.developer("dev_1", "jane@acme.test");
        assertEquals(DeveloperRole.ADMIN, f.adminDevelopers.setRole(bossActor, "dev_1", "admin").getRole());
        assertEquals(DeveloperRole.DEVELOPER, f.adminDevelopers.setRole(bossActor, "dev_1", " Developer ").getRole());
        assertEquals(List.of("DEVELOPER_ROLE_CHANGED dev_1", "DEVELOPER_ROLE_CHANGED dev_1"), f.actions.summary());
    }

    @Test
    void anAdministratorCannotChangeTheirOwnRole() {
        f.admin("adm_2", "second@premisave.test");
        DeveloperException e = fails(() -> f.adminDevelopers.setRole(bossActor, "adm_1", "developer"));
        assertEquals(ErrorCode.FORBIDDEN, e.code());
        assertTrue(boss.isAdmin());
    }

    @Test
    void thePendingOrDisabledCannotBeMadeAdministrators() {
        Developer d = f.developer("dev_1", "jane@acme.test");
        d.setStatus(DeveloperStatus.PENDING);
        assertEquals(ErrorCode.INVALID_STATE, fails(() -> f.adminDevelopers.setRole(bossActor, "dev_1", "admin")).code());
    }

    @Test
    void theLastActiveAdministratorCannotBeDemotedByTheInternalKeyEither() {
        DeveloperException e = fails(() -> f.adminDevelopers.setRole(AdminActor.INTERNAL, "adm_1", "developer"));
        assertEquals(ErrorCode.INVALID_STATE, e.code());
        f.admin("adm_2", "second@premisave.test");
        assertEquals(DeveloperRole.DEVELOPER, f.adminDevelopers.setRole(AdminActor.INTERNAL, "adm_1", "developer").getRole());
    }

    @Test
    void anUnknownRoleIsRefused() {
        f.developer("dev_1", "jane@acme.test");
        assertEquals(ErrorCode.VALIDATION_FAILED, fails(() -> f.adminDevelopers.setRole(bossActor, "dev_1", "owner")).code());
    }

    // Status

    @Test
    void anAccountCanBeDisabledAndEnabled() {
        f.developer("dev_1", "jane@acme.test");
        assertEquals(DeveloperStatus.DISABLED, f.adminDevelopers.setStatus(bossActor, "dev_1", DeveloperStatus.DISABLED).getStatus());
        assertEquals(DeveloperStatus.ACTIVE, f.adminDevelopers.setStatus(bossActor, "dev_1", DeveloperStatus.ACTIVE).getStatus());
        assertEquals(List.of("DEVELOPER_DISABLED dev_1", "DEVELOPER_ENABLED dev_1"), f.actions.summary());
    }

    @Test
    void pendingCannotBeSetByHand() {
        f.developer("dev_1", "jane@acme.test");
        assertEquals(ErrorCode.VALIDATION_FAILED, fails(() -> f.adminDevelopers.setStatus(bossActor, "dev_1", DeveloperStatus.PENDING)).code());
    }

    @Test
    void anAdministratorCannotDisableThemselvesOrTheLastAdministrator() {
        assertEquals(ErrorCode.FORBIDDEN, fails(() -> f.adminDevelopers.setStatus(bossActor, "adm_1", DeveloperStatus.DISABLED)).code());
        assertEquals(ErrorCode.INVALID_STATE,
                fails(() -> f.adminDevelopers.setStatus(AdminActor.INTERNAL, "adm_1", DeveloperStatus.DISABLED)).code());
        assertEquals(DeveloperStatus.ACTIVE, boss.getStatus());
        f.admin("adm_2", "second@premisave.test");
        assertEquals(DeveloperStatus.DISABLED, f.adminDevelopers.setStatus(bossActor, "adm_2", DeveloperStatus.DISABLED).getStatus());
    }

    // Account care

    @Test
    void confirmingTheEmailActivatesAPendingAccount() {
        Developer d = f.developer("dev_1", "jane@acme.test");
        d.setStatus(DeveloperStatus.PENDING);
        d.setEmailVerified(false);
        Developer done = f.adminDevelopers.verifyEmail(bossActor, "dev_1");
        assertTrue(done.isEmailVerified());
        assertEquals(DeveloperStatus.ACTIVE, done.getStatus());
    }

    @Test
    void confirmingTheEmailDoesNotLiftADisable() {
        Developer d = f.developer("dev_1", "jane@acme.test");
        d.setStatus(DeveloperStatus.DISABLED);
        assertEquals(DeveloperStatus.DISABLED, f.adminDevelopers.verifyEmail(bossActor, "dev_1").getStatus());
    }

    @Test
    void unlockingClearsTheLockoutAndTheFailedAttempts() {
        Developer d = f.developer("dev_1", "jane@acme.test");
        d.setFailedLogins(2);
        d.setLockedUntil(AdminFixture.T0.plusSeconds(600));
        Developer done = f.adminDevelopers.unlock(bossActor, "dev_1");
        assertEquals(0, done.getFailedLogins());
        assertNull(done.getLockedUntil());
    }

    @Test
    void signingOutEndsEverySession() {
        Developer d = f.developer("dev_1", "jane@acme.test");
        int before = d.getTokenVersion();
        assertEquals(before + 1, f.adminDevelopers.signOut(bossActor, "dev_1").getTokenVersion());
    }

    @Test
    void aPasswordResetLinkIsSentToAnActiveAccountOnly() {
        f.developer("dev_1", "jane@acme.test");
        Map<String, Object> out = f.adminDevelopers.sendPasswordReset(bossActor, "dev_1");
        assertEquals(true, out.get("requested"));
        assertEquals(1, f.mailer.sent.size());
        assertTrue(f.mailer.sent.get(0).html().contains("/reset-password?token="));

        f.mailer.sent.clear();
        Developer off = f.developer("dev_2", "off@acme.test");
        off.setStatus(DeveloperStatus.DISABLED);
        assertEquals(false, f.adminDevelopers.sendPasswordReset(bossActor, "dev_2").get("requested"));
        assertTrue(f.mailer.sent.isEmpty());
    }

    // Delete

    @Test
    void anAccountThatOwnsNothingIsDeletedWithItsLeftovers() {
        f.developer("dev_1", "jane@acme.test");
        f.accountEmail.sendVerification(f.devs.store.get("dev_1"));
        OAuthIdentity link = new OAuthIdentity();
        link.setId("google:1");
        link.setDeveloperId("dev_1");
        f.identities.store.put("google:1", link);
        f.adminDevelopers.delete(bossActor, "dev_1");
        assertFalse(f.devs.store.containsKey("dev_1"));
        assertTrue(f.identities.store.isEmpty());
        assertTrue(f.emailTokens.store.isEmpty());
        assertEquals(List.of("DEVELOPER_DELETED dev_1"), f.actions.summary());
    }

    @Test
    void anAccountWithAppsOrAnAppliedBusinessIsNotDeleted() {
        f.developer("dev_1", "jane@acme.test");
        f.app("app_1", "dev_1", "mer_1", Environment.SANDBOX, "Shop", 1);
        DeveloperException e = fails(() -> f.adminDevelopers.delete(bossActor, "dev_1"));
        assertEquals(ErrorCode.INVALID_STATE, e.code());
        assertTrue(f.devs.store.containsKey("dev_1"));

        f.apps.store.clear();
        f.devs.store.get("dev_1").addBusiness("mer_1");
        assertEquals(ErrorCode.INVALID_STATE, fails(() -> f.adminDevelopers.delete(bossActor, "dev_1")).code());
        assertTrue(f.devs.store.containsKey("dev_1"));
    }

    @Test
    void anAdministratorCannotDeleteThemselvesOrTheLastAdministrator() {
        assertEquals(ErrorCode.FORBIDDEN, fails(() -> f.adminDevelopers.delete(bossActor, "adm_1")).code());
        assertEquals(ErrorCode.INVALID_STATE, fails(() -> f.adminDevelopers.delete(AdminActor.INTERNAL, "adm_1")).code());
        assertTrue(f.devs.store.containsKey("adm_1"));
    }

    // Businesses

    @Test
    void aBusinessCanBeSharedAndTakenBack() {
        f.wallet.business("mer_1", "Acme");
        f.developer("dev_1", "jane@acme.test");
        Map<String, Object> view = f.adminDevelopers.shareBusiness(bossActor, "dev_1", "mer_1");
        assertEquals("mer_1", view.get("businessId"));
        assertTrue(f.devs.store.get("dev_1").ownsBusiness("mer_1"));

        f.adminDevelopers.unshareBusiness(bossActor, "dev_1", "mer_1");
        assertFalse(f.devs.store.get("dev_1").ownsBusiness("mer_1"));
        assertEquals(List.of("BUSINESS_SHARED mer_1", "BUSINESS_UNSHARED mer_1"), f.actions.summary());
    }

    @Test
    void aBusinessTheWalletDoesNotKnowCannotBeShared() {
        f.developer("dev_1", "jane@acme.test");
        assertEquals(ErrorCode.MERCHANT_NOT_FOUND, fails(() -> f.adminDevelopers.shareBusiness(bossActor, "dev_1", "mer_none")).code());
        assertFalse(f.devs.store.get("dev_1").ownsBusiness("mer_none"));
    }

    @Test
    void aBusinessTheAccountAppliedForOrUsesCannotBeTakenBack() {
        f.wallet.business("mer_1", "Acme");
        Developer d = f.developer("dev_1", "jane@acme.test");
        d.addBusiness("mer_1");
        assertEquals(ErrorCode.INVALID_STATE, fails(() -> f.adminDevelopers.unshareBusiness(bossActor, "dev_1", "mer_1")).code());

        d.removeSharedBusiness("mer_1");
        f.devs.store.get("dev_1").setMerchantIds(List.of());
        f.adminDevelopers.shareBusiness(bossActor, "dev_1", "mer_1");
        f.app("app_1", "dev_1", "mer_1", Environment.LIVE, "Shop", 1);
        assertEquals(ErrorCode.INVALID_STATE, fails(() -> f.adminDevelopers.unshareBusiness(bossActor, "dev_1", "mer_1")).code());
        assertEquals(ErrorCode.BUSINESS_NOT_FOUND, fails(() -> f.adminDevelopers.unshareBusiness(bossActor, "dev_1", "mer_other")).code());
    }

    @Test
    void theBusinessesOfAnAccountAreListedFromTheWallet() {
        f.wallet.business("mer_1", "Acme");
        Developer d = f.developer("dev_1", "jane@acme.test");
        d.addSharedBusiness("mer_1");
        Map<String, Object> out = f.adminDevelopers.businessesOf("dev_1");
        assertEquals(1, out.get("count"));
    }
}
