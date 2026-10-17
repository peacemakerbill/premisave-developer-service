package com.premisave.developer.service;

import com.premisave.developer.TestProps;
import com.premisave.developer.TestRepos;
import com.premisave.developer.config.DeveloperProperties.Security;
import com.premisave.developer.dto.LoginRequest;
import com.premisave.developer.dto.SignupRequest;
import com.premisave.developer.enums.DeveloperRole;
import com.premisave.developer.model.Developer;
import com.premisave.developer.security.JwtService;
import com.premisave.developer.security.PasswordHasher;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The first administrator: an account on the configured list becomes one when it signs in with a confirmed address. */
class DeveloperAdminBootstrapTest {

    private static final Instant T0 = Instant.parse("2026-10-18T08:00:00Z");

    private final TestRepos.Developers repo = new TestRepos.Developers();
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return T0; }
    };
    private final PasswordHasher hasher = new PasswordHasher(10_000);
    private final JwtService jwt = new JwtService(TestProps.JWT_SECRET, "iss", 60, clock);
    private final TestRepos.EmailTokens tokenRepo = new TestRepos.EmailTokens();
    private final TestRepos.RecordingMailer mailer = new TestRepos.RecordingMailer();
    private final MailService mail = new MailService(mailer, Runnable::run, false);
    private final EmailTokenService tokens = new EmailTokenService(tokenRepo.repo, TestProps.mail(), TestProps.PEPPER, clock);
    private final AccountEmailService accountEmail = new AccountEmailService(repo.repo, tokens, mail, hasher, TestProps.mail(), 10, clock);
    private final DeveloperService service = new DeveloperService(repo.repo, hasher, jwt,
            new Security(3, 10, 10, 10_000, List.of("Boss@Premisave.test")), new TestRepos.Revoked().repo, accountEmail, clock);

    private static final String PASSWORD = "a long password 1";

    private Developer signedUp(String email, boolean confirmed) {
        service.signup(new SignupRequest(email, "Jane", null, "Doe", PASSWORD, "ke", null, null, true, null));
        Developer d = repo.store.values().stream().filter(x -> x.getEmail().equals(email)).findFirst().orElseThrow();
        if (confirmed) d.confirmEmail(T0);
        return d;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> accountOf(Map<String, Object> session) {
        return (Map<String, Object>) session.get("developer");
    }

    @Test
    void aListedAndConfirmedAccountBecomesAnAdministratorAtSignIn() {
        Developer d = signedUp("boss@premisave.test", true);
        assertFalse(d.isAdmin());
        Map<String, Object> session = service.login(new LoginRequest("boss@premisave.test", PASSWORD));
        assertEquals("ADMIN", accountOf(session).get("role"));
        assertTrue(repo.store.get(d.getDeveloperId()).isAdmin());
    }

    @Test
    void anAddressIsMatchedWithoutRegardToCase() {
        Developer d = signedUp("boss@premisave.test", true);
        d.setEmail("BOSS@premisave.test");
        service.session(d);
        assertEquals(DeveloperRole.ADMIN, d.getRole());
    }

    @Test
    void anUnlistedAccountStaysAnOrdinaryDeveloper() {
        signedUp("jane@premisave.test", true);
        Map<String, Object> session = service.login(new LoginRequest("jane@premisave.test", PASSWORD));
        assertEquals("DEVELOPER", accountOf(session).get("role"));
    }

    @Test
    void aListedAddressThatWasNeverConfirmedIsNotPromoted() {
        Developer d = signedUp("boss@premisave.test", false);
        service.session(d);
        assertEquals(DeveloperRole.DEVELOPER, d.getRole());
        assertFalse(repo.store.get(d.getDeveloperId()).isAdmin());
    }

    @Test
    void anAdministratorIsLeftAsItIsAndNotSavedAgain() {
        Developer d = signedUp("boss@premisave.test", true);
        d.setRole(DeveloperRole.ADMIN);
        d.setUpdatedAt(null);
        service.session(d);
        assertEquals(null, d.getUpdatedAt());
    }

    @Test
    void takingTheRoleAwayIsNotUndoneForAnAddressNotOnTheList() {
        Developer d = signedUp("jane@premisave.test", true);
        d.setRole(DeveloperRole.ADMIN);
        service.session(d);
        assertTrue(d.isAdmin());
    }
}
