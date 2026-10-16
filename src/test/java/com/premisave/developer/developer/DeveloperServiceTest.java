package com.premisave.developer.developer;

import com.premisave.developer.TestProps;
import com.premisave.developer.TestRepos;
import com.premisave.developer.auth.AccountEmailService;
import com.premisave.developer.auth.EmailTokenService;
import com.premisave.developer.auth.MailService;
import com.premisave.developer.common.DeveloperException;
import com.premisave.developer.enums.DeveloperStatus;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.security.JwtService;
import com.premisave.developer.security.PasswordHasher;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeveloperServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-16T08:00:00Z");

    private final TestRepos.Developers repo = new TestRepos.Developers();
    private Instant now = T0;
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };
    private final PasswordHasher hasher = new PasswordHasher(10_000);
    private final JwtService jwt = new JwtService(TestProps.JWT_SECRET, "iss", 60, clock);
    private final TestRepos.EmailTokens tokenRepo = new TestRepos.EmailTokens();
    private final TestRepos.Revoked revoked = new TestRepos.Revoked();
    private final TestRepos.RecordingMailer mailer = new TestRepos.RecordingMailer();
    private final MailService mail = new MailService(mailer, Runnable::run, false);
    private final EmailTokenService tokens = new EmailTokenService(tokenRepo.repo, TestProps.mail(), TestProps.PEPPER, clock);
    private final AccountEmailService accountEmail = new AccountEmailService(repo.repo, tokens, mail, hasher, TestProps.mail(), 10, clock);
    private final DeveloperService service = new DeveloperService(repo.repo, hasher, jwt, TestProps.fastSecurity(), revoked.repo, accountEmail, clock);

    private static SignupRequest signup(String email, String password) {
        return new SignupRequest(email, "Jane Doe", password);
    }

    private Map<String, Object> signedUp() {
        return service.signup(signup("Jane@Example.com ", "a long password 1"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> developerOf(Map<String, Object> session) {
        return (Map<String, Object>) session.get("developer");
    }

    @Test
    void signupStoresALowercaseEmailAndAHashedPasswordAndReturnsASession() {
        Map<String, Object> s = signedUp();
        assertEquals("jane@example.com", developerOf(s).get("email"));
        assertEquals("Bearer", s.get("tokenType"));
        assertEquals(3600L, s.get("expiresIn"));
        assertNotNull(s.get("accessToken"));
        Developer d = repo.store.values().iterator().next();
        assertTrue(d.getPasswordHash().startsWith("pbkdf2-sha256$"));
        assertFalse(d.getPasswordHash().contains("a long password"));
        assertTrue(d.getDeveloperId().startsWith("dev_"));
        assertNull(d.getMerchantId());
        assertFalse(developerOf(s).containsKey("passwordHash"));
    }

    @Test
    void theSameEmailCannotSignUpTwiceInAnyCase() {
        signedUp();
        DeveloperException e = assertThrows(DeveloperException.class, () -> service.signup(signup("JANE@example.com", "another long one 2")));
        assertEquals(ErrorCode.EMAIL_TAKEN, e.code());
    }

    @Test
    void weakOrOddInputIsRefused() {
        for (String[] c : new String[][] {{"not-an-email", "a long password 1"}, {"a@b", "a long password 1"}, {"a b@c.com", "a long password 1"},
                {"x@example.com", "short"}, {"x@example.com", "          "}, {"x@example.com", "x@example.com"}}) {
            DeveloperException e = assertThrows(DeveloperException.class, () -> service.signup(signup(c[0], c[1])));
            assertEquals(ErrorCode.VALIDATION_FAILED, e.code(), c[0] + " / " + c[1]);
        }
    }

    @Test
    void loginWithTheRightPasswordWorksAndRecordsTheTime() {
        signedUp();
        now = T0.plusSeconds(60);
        Map<String, Object> s = service.login(new LoginRequest(" JANE@example.com", "a long password 1"));
        assertNotNull(s.get("accessToken"));
        assertEquals(now, repo.store.values().iterator().next().getLastLoginAt());
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveTheSameAnswer() {
        signedUp();
        DeveloperException wrong = assertThrows(DeveloperException.class, () -> service.login(new LoginRequest("jane@example.com", "nope nope nope")));
        DeveloperException unknown = assertThrows(DeveloperException.class, () -> service.login(new LoginRequest("who@example.com", "nope nope nope")));
        assertEquals(ErrorCode.INVALID_CREDENTIALS, wrong.code());
        assertEquals(wrong.code(), unknown.code());
        assertEquals(wrong.getMessage(), unknown.getMessage());
    }

    @Test
    void repeatedFailuresLockTheAccountEvenAgainstTheRightPassword() {
        signedUp();
        LoginRequest bad = new LoginRequest("jane@example.com", "wrong wrong wrong");
        for (int i = 0; i < 3; i++) assertThrows(DeveloperException.class, () -> service.login(bad));
        DeveloperException locked = assertThrows(DeveloperException.class,
                () -> service.login(new LoginRequest("jane@example.com", "a long password 1")));
        assertEquals(ErrorCode.ACCOUNT_LOCKED, locked.code());

        now = T0.plus(Duration.ofMinutes(11));
        assertNotNull(service.login(new LoginRequest("jane@example.com", "a long password 1")).get("accessToken"));
        assertEquals(0, repo.store.values().iterator().next().getFailedLogins());
    }

    @Test
    void aSuccessfulLoginClearsEarlierFailures() {
        signedUp();
        LoginRequest bad = new LoginRequest("jane@example.com", "wrong wrong wrong");
        assertThrows(DeveloperException.class, () -> service.login(bad));
        assertThrows(DeveloperException.class, () -> service.login(bad));
        service.login(new LoginRequest("jane@example.com", "a long password 1"));
        assertThrows(DeveloperException.class, () -> service.login(bad));
        assertThrows(DeveloperException.class, () -> service.login(bad));
        assertNotNull(service.login(new LoginRequest("jane@example.com", "a long password 1")));
    }

    @Test
    void aDisabledAccountCannotSignIn() {
        signedUp();
        String id = repo.store.keySet().iterator().next();
        service.setStatus(id, DeveloperStatus.DISABLED);
        DeveloperException e = assertThrows(DeveloperException.class,
                () -> service.login(new LoginRequest("jane@example.com", "a long password 1")));
        assertEquals(ErrorCode.ACCOUNT_DISABLED, e.code());
    }

    @Test
    void aTokenIdentifiesTheDeveloperUntilTheAccountIsDisabled() {
        String token = (String) signedUp().get("accessToken");
        Developer d = service.authenticate(token);
        assertEquals("jane@example.com", d.getEmail());
        service.setStatus(d.getDeveloperId(), DeveloperStatus.DISABLED);
        assertEquals(ErrorCode.ACCOUNT_DISABLED, assertThrows(DeveloperException.class, () -> service.authenticate(token)).code());
    }

    @Test
    void garbageAndUnknownDevelopersAreUnauthenticated() {
        assertEquals(ErrorCode.UNAUTHENTICATED, assertThrows(DeveloperException.class, () -> service.authenticate("garbage")).code());
        String orphan = jwt.issue("dev_gone", 0);
        assertEquals(ErrorCode.UNAUTHENTICATED, assertThrows(DeveloperException.class, () -> service.authenticate(orphan)).code());
    }

    @Test
    void changingThePasswordEndsEveryOlderToken() {
        String oldToken = (String) signedUp().get("accessToken");
        Developer d = service.authenticate(oldToken);
        Map<String, Object> fresh = service.changePassword(d, new ChangePasswordRequest("a long password 1", "a brand new password 3"));
        assertEquals(ErrorCode.UNAUTHENTICATED, assertThrows(DeveloperException.class, () -> service.authenticate(oldToken)).code());
        assertNotNull(service.authenticate((String) fresh.get("accessToken")));
        assertNotNull(service.login(new LoginRequest("jane@example.com", "a brand new password 3")));
        assertThrows(DeveloperException.class, () -> service.login(new LoginRequest("jane@example.com", "a long password 1")));
    }

    @Test
    void changingThePasswordNeedsTheCurrentOne() {
        Developer d = service.authenticate((String) signedUp().get("accessToken"));
        DeveloperException e = assertThrows(DeveloperException.class,
                () -> service.changePassword(d, new ChangePasswordRequest("not my password", "a brand new password 3")));
        assertEquals(ErrorCode.INVALID_CREDENTIALS, e.code());
        assertNotEquals(1, d.getTokenVersion());
    }

    @Test
    void anOlderHashIsUpgradedOnLogin() {
        signedUp();
        Developer d = repo.store.values().iterator().next();
        d.setPasswordHash(new PasswordHasher(1_000).hash("a long password 1"));
        service.login(new LoginRequest("jane@example.com", "a long password 1"));
        assertTrue(d.getPasswordHash().startsWith("pbkdf2-sha256$10000$"));
    }

    // ---- sign out, sign in providers and passwords

    private String tokenOf(Map<String, Object> session) {
        return (String) session.get("accessToken");
    }

    @Test
    void signupSendsAConfirmationLinkAndStartsUnconfirmed() {
        Map<String, Object> s = signedUp();
        assertEquals(false, developerOf(s).get("emailVerified"));
        assertEquals(true, developerOf(s).get("hasPassword"));
        assertEquals(1, mailer.sent.size());
        assertEquals("jane@example.com", mailer.sent.get(0).to());
        assertTrue(mailer.lastLink("https://portal.example.com/verify-email?token=").length() > 60);
    }

    @Test
    void aMailServerThatIsDownDoesNotStopSignUp() {
        mailer.fail = true;
        Map<String, Object> s = signedUp();
        assertNotNull(s.get("accessToken"));
        assertEquals(1, repo.store.size());
    }

    @Test
    void everyTokenCarriesItsOwnId() {
        Map<String, Object> a = signedUp();
        Map<String, Object> b = service.login(new LoginRequest("jane@example.com", "a long password 1"));
        JwtService.Claims ca = jwt.verify(tokenOf(a)).orElseThrow();
        JwtService.Claims cb = jwt.verify(tokenOf(b)).orElseThrow();
        assertFalse(ca.tokenId().isEmpty());
        assertNotEquals(ca.tokenId(), cb.tokenId());
    }

    @Test
    void logoutEndsOnlyTheTokenUsed() {
        Map<String, Object> first = signedUp();
        Map<String, Object> second = service.login(new LoginRequest("jane@example.com", "a long password 1"));
        DeveloperService.Authenticated who = service.authenticateToken(tokenOf(first));
        service.logout(who.developer(), who.claims());
        DeveloperException e = assertThrows(DeveloperException.class, () -> service.authenticate(tokenOf(first)));
        assertEquals(ErrorCode.UNAUTHENTICATED, e.code());
        assertNotNull(service.authenticate(tokenOf(second)));
        assertEquals(1, revoked.store.size());
        assertEquals(Instant.ofEpochSecond(who.claims().expiresAt()), revoked.store.values().iterator().next().getExpiresAt());
    }

    @Test
    void logoutEverywhereEndsEveryToken() {
        Map<String, Object> first = signedUp();
        Map<String, Object> second = service.login(new LoginRequest("jane@example.com", "a long password 1"));
        service.logoutEverywhere(service.authenticate(tokenOf(first)));
        assertThrows(DeveloperException.class, () -> service.authenticate(tokenOf(first)));
        assertThrows(DeveloperException.class, () -> service.authenticate(tokenOf(second)));
        Map<String, Object> again = service.login(new LoginRequest("jane@example.com", "a long password 1"));
        assertNotNull(service.authenticate(tokenOf(again)));
    }

    @Test
    void anAccountWithoutAPasswordCannotSignInWithOneAndCanSetOne() {
        signedUp();
        Developer d = repo.store.values().iterator().next();
        d.setPasswordHash(null);
        DeveloperException e = assertThrows(DeveloperException.class,
                () -> service.login(new LoginRequest("jane@example.com", "a long password 1")));
        assertEquals(ErrorCode.INVALID_CREDENTIALS, e.code());
        assertEquals(1, d.getFailedLogins());

        Map<String, Object> s = service.changePassword(d, new ChangePasswordRequest(null, "a brand new password 2"));
        assertNotNull(s.get("accessToken"));
        assertNotNull(d.getPasswordHash());
        assertNotNull(service.login(new LoginRequest("jane@example.com", "a brand new password 2")));
    }

    @Test
    void anAccountWithAPasswordStillNeedsTheCurrentOneToChangeIt() {
        signedUp();
        Developer d = repo.store.values().iterator().next();
        DeveloperException e = assertThrows(DeveloperException.class,
                () -> service.changePassword(d, new ChangePasswordRequest(null, "a brand new password 2")));
        assertEquals(ErrorCode.INVALID_CREDENTIALS, e.code());
    }

    @Test
    void changingThePasswordTellsTheOwnerByEmail() {
        signedUp();
        Developer d = repo.store.values().iterator().next();
        mailer.sent.clear();
        service.changePassword(d, new ChangePasswordRequest("a long password 1", "a brand new password 2"));
        assertEquals(1, mailer.sent.size());
        assertTrue(mailer.sent.get(0).subject().contains("password was changed"));
    }
}
