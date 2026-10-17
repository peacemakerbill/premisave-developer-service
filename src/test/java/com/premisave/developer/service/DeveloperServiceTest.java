package com.premisave.developer.service;

import com.premisave.developer.TestProps;
import com.premisave.developer.TestRepos;
import com.premisave.developer.config.DeveloperProperties;
import com.premisave.developer.dto.ChangePasswordRequest;
import com.premisave.developer.dto.ClientInfo;
import com.premisave.developer.dto.LoginRequest;
import com.premisave.developer.dto.SignupRequest;
import com.premisave.developer.dto.UpdateProfileRequest;
import com.premisave.developer.enums.DeveloperStatus;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.Developer;
import com.premisave.developer.security.JwtService;
import com.premisave.developer.security.PasswordHasher;
import com.premisave.developer.util.Usernames;
import com.premisave.developer.view.DeveloperViews;

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
        return new SignupRequest(email, "Jane", null, "Doe", password, "ke", null, null, true, null);
    }

    private Map<String, Object> signedUp() {
        return service.signup(signup("Jane@Example.com ", "a long password 1"));
    }

    /** Signs up and then signs in, because sign up itself issues no token. */
    private String signedInToken() {
        signedUp();
        return (String) service.login(new LoginRequest("jane@example.com", "a long password 1")).get("accessToken");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> developerOf(Map<String, Object> session) {
        return (Map<String, Object>) session.get("developer");
    }

    /** The stored account for an email address, as the portal sees it. Sign up itself returns no account data. */
    private Map<String, Object> viewOf(String email) {
        return DeveloperViews.developer(repo.store.values().stream().filter(x -> x.getEmail().equals(email)).findFirst().orElseThrow());
    }

    /** Signs up and returns the stored account as the portal sees it. */
    private Map<String, Object> signedView(SignupRequest req) {
        service.signup(req);
        return viewOf(req.email().trim().toLowerCase());
    }

    @Test
    void signupStoresALowercaseEmailAndAHashedPasswordAndIssuesNoToken() {
        Map<String, Object> s = signedUp();
        assertEquals("jane@example.com", only().getEmail());
        assertFalse(s.containsKey("accessToken"));
        assertFalse(s.containsKey("tokenType"));
        assertFalse(s.containsKey("expiresIn"));
        Developer d = repo.store.values().iterator().next();
        assertTrue(d.getPasswordHash().startsWith("pbkdf2-sha256$"));
        assertFalse(d.getPasswordHash().contains("a long password"));
        assertTrue(d.getDeveloperId().startsWith("dev_"));
        assertNull(d.getMerchantId());
        assertFalse(DeveloperViews.developer(d).containsKey("passwordHash"));
    }

    private static SignupRequest sign(String email, String first, String middle, String last, String country,
                                      String phone, String company, Boolean terms, Boolean marketing) {
        return new SignupRequest(email, first, middle, last, "a long password 1", country, phone, company, terms, marketing);
    }

    /** An update that sets only the named fields. Keys are the request field names. */
    private static UpdateProfileRequest update(String... keyValues) {
        Map<String, String> m = new java.util.HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) m.put(keyValues[i], keyValues[i + 1]);
        return new UpdateProfileRequest(m.get("firstName"), m.get("middleName"), m.get("lastName"), m.get("username"),
                m.get("country"), m.get("phoneNumber"), m.get("companyName"), m.get("addressLine1"), m.get("addressLine2"),
                m.get("city"), m.get("stateOrRegion"), m.get("postalCode"), m.get("timezone"),
                m.containsKey("marketingOptIn") ? Boolean.valueOf(m.get("marketingOptIn")) : null);
    }

    private Developer only() {
        return repo.store.values().iterator().next();
    }

    private void assertInvalid(String field, Runnable call) {
        DeveloperException e = assertThrows(DeveloperException.class, call::run);
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code(), field);
        assertEquals(field, e.details().get(0).get("field"));
    }

    @Test
    void signupKeepsTheExtraDetailsTidied() {
        service.signup(sign("jane@example.com", " Jane ", "  Mary   Ann ", " Doe ",
                " ke ", "+254 (700) 123-456", "  Acme Ltd ", true, true));
        Developer d = only();
        assertEquals("Jane", d.getFirstName());
        assertEquals("Mary Ann", d.getMiddleName());
        assertEquals("Doe", d.getLastName());
        assertEquals("Jane Mary Ann Doe", d.getFullName());
        assertEquals("KE", d.getCountry());
        assertEquals("+254700123456", d.getPhoneNumber());
        assertEquals("Acme Ltd", d.getCompanyName());
        assertEquals(T0, d.getTermsAcceptedAt());
        assertTrue(d.isMarketingOptIn());
        Map<String, Object> view = DeveloperViews.developer(d);
        assertEquals("Jane", view.get("firstName"));
        assertEquals("Mary Ann", view.get("middleName"));
        assertEquals("Doe", view.get("lastName"));
        assertEquals("Jane Mary Ann Doe", view.get("fullName"));
        assertEquals("KE", view.get("country"));
        assertEquals("+254700123456", view.get("phoneNumber"));
        assertEquals("Acme Ltd", view.get("companyName"));
        assertEquals(true, view.get("marketingOptIn"));
        assertEquals(true, view.get("profileComplete"));
        assertEquals(T0.toString(), view.get("termsAcceptedAt"));
    }

    @Test
    void middleNamePhoneAndCompanyAreOptionalAndMarketingDefaultsToOff() {
        signedUp();
        Developer d = only();
        assertNull(d.getMiddleName());
        assertNull(d.getPhoneNumber());
        assertNull(d.getCompanyName());
        assertFalse(d.isMarketingOptIn());
        Map<String, Object> blank = signedView(sign("b@example.com", "Bob", "  ", "Doe", "UG", "   ", "   ", true, false));
        assertNull(blank.get("middleName"));
        assertNull(blank.get("phoneNumber"));
        assertNull(blank.get("companyName"));
        assertEquals("Bob Doe", blank.get("fullName"));
    }

    @Test
    void signupNeedsTheTermsAcceptedAndValidNamesCountryAndPhone() {
        Object[][] bad = {
                {"acceptTerms", sign("x@example.com", "Jane", null, "Doe", "KE", null, null, false, null)},
                {"acceptTerms", sign("x@example.com", "Jane", null, "Doe", "KE", null, null, null, null)},
                {"country", sign("x@example.com", "Jane", null, "Doe", "K3", null, null, true, null)},
                {"country", sign("x@example.com", "Jane", null, "Doe", "KEN", null, null, true, null)},
                {"phoneNumber", sign("x@example.com", "Jane", null, "Doe", "KE", "12345", null, true, null)},
                {"phoneNumber", sign("x@example.com", "Jane", null, "Doe", "KE", "+2547abc12345", null, true, null)},
                {"companyName", sign("x@example.com", "Jane", null, "Doe", "KE", null, "x".repeat(161), true, null)},
                {"companyName", sign("x@example.com", "Jane", null, "Doe", "KE", null, "<b>Acme</b>", true, null)},
                {"firstName", sign("x@example.com", "  ", null, "Doe", "KE", null, null, true, null)},
                {"firstName", sign("x@example.com", "J4ne", null, "Doe", "KE", null, null, true, null)},
                {"firstName", sign("x@example.com", "<script>", null, "Doe", "KE", null, null, true, null)},
                {"lastName", sign("x@example.com", "Jane", null, null, "KE", null, null, true, null)},
                {"lastName", sign("x@example.com", "Jane", null, "Doe_1", "KE", null, null, true, null)},
                {"middleName", sign("x@example.com", "Jane", "M@ry", "Doe", "KE", null, null, true, null)},
                {"firstName", sign("x@example.com", "x".repeat(61), null, "Doe", "KE", null, null, true, null)},
        };
        for (Object[] c : bad) {
            assertInvalid((String) c[0], () -> service.signup((SignupRequest) c[1]));
        }
        assertTrue(repo.store.isEmpty());
    }

    @Test
    void namesFromManyAlphabetsAndWithPunctuationAreAccepted() {
        for (String[] n : new String[][] {{"Anne-Marie", "O'Brien"}, {"Jos\u00e9", "M\u00fcller"}, {"Wanjiku", "van der Merwe"}, {"Mary", "Smith Jr."},
                {"\u0416\u0430\u043d\u043d\u0430", "\u0418\u0432\u0430\u043d\u043e\u0432\u0430"}, {"Li", "\u738b"}}) {
            service.signup(sign(n[0].hashCode() + "@example.com", n[0], null, n[1], "KE", null, null, true, null));
        }
        assertEquals(6, repo.store.size());
    }

    // ---- usernames

    @Test
    void signupGeneratesAUsernameFromTheNames() {
        signedUp();
        assertEquals("jane.doe", viewOf("jane@example.com").get("username"));
        assertEquals("jane.doe", only().getUsername());
        assertNull(only().getUsernameChangedAt());
    }

    @Test
    void aSecondPersonWithTheSameNamesGetsADifferentUniqueUsername() {
        signedUp();
        Map<String, Object> second = signedView(signup("other@example.com", "a long password 1"));
        String u = (String) second.get("username");
        assertTrue(u.matches("jane\\.doe[0-9]{4}"), u);
        assertTrue(Usernames.isValid(u));
        Map<String, Object> third = signedView(signup("third@example.com", "a long password 1"));
        assertNotEquals(u, third.get("username"));
        assertNotEquals("jane.doe", third.get("username"));
    }

    @Test
    void generatedUsernamesDropAccentsAndFallBackToTheEmailForOtherAlphabets() {
        assertEquals("jose.muller", signedView(sign("a@example.com", "Jos\u00e9", null, "M\u00fcller", "KE", null, null, true, null)).get("username"));
        assertEquals("zhanna", signedView(sign("zhanna@example.com", "\u0416\u0430\u043d\u043d\u0430", null, "\u0418\u0432\u0430\u043d\u043e\u0432\u0430", "KE", null, null, true, null)).get("username"));
        assertEquals("annemarie.obrien", signedView(sign("b@example.com", "Anne-Marie", null, "O'Brien", "KE", null, null, true, null)).get("username"));
    }

    @Test
    void aGeneratedUsernameNeverIsOrContainsAReservedWord() {
        String u = (String) signedView(sign("c@example.com", "Pre", null, "Misave", "KE", null, null, true, null)).get("username");
        assertTrue(u.startsWith("dev"), u);
        assertFalse(Usernames.isReserved(u), u);
        String v = (String) signedView(sign("d@example.com", "Ad", null, "Min", "KE", null, null, true, null)).get("username");
        assertTrue(Usernames.isValid(v), v);
        assertFalse(Usernames.isReserved(v), v);
    }

    @Test
    void aSignUpThatLosesARaceForTheUsernameTriesAgain() {
        repo.duplicateOnInsert = 1;
        Map<String, Object> s = signedUp();
        assertEquals("PENDING", s.get("status"));
        assertEquals(1, repo.store.size());
    }

    @Test
    void aDuplicateThatIsTheEmailStillSaysEmailTaken() {
        repo.duplicateOnInsert = 1;
        // Another sign up with the same email lands between the check and the insert.
        repo.onDuplicate = () -> {
            Developer rival = new Developer();
            rival.setDeveloperId("dev_rival");
            rival.setEmail("jane@example.com");
            rival.setUsername("rival.name");
            repo.store.put(rival.getDeveloperId(), rival);
        };
        DeveloperException e = assertThrows(DeveloperException.class, () -> service.signup(signup("jane@example.com", "a long password 1")));
        assertEquals(ErrorCode.EMAIL_TAKEN, e.code());
        assertEquals(1, repo.store.size());
    }

    @Test
    void theUsernameCanBeChangedAndIsStoredInLowerCase() {
        signedUp();
        Developer d = only();
        now = T0.plusSeconds(60);
        Map<String, Object> out = service.updateProfile(d, update("username", "  Jane_D.99 "));
        assertEquals("jane_d.99", out.get("username"));
        assertEquals("jane_d.99", d.getUsername());
        assertEquals(now, d.getUsernameChangedAt());
    }

    @Test
    void sendingTheCurrentUsernameAgainChangesNothing() {
        signedUp();
        Developer d = only();
        service.updateProfile(d, update("username", "JANE.DOE"));
        assertEquals("jane.doe", d.getUsername());
        assertNull(d.getUsernameChangedAt());
    }

    @Test
    void badUsernamesAreRefused() {
        signedUp();
        Developer d = only();
        for (String bad : new String[] {"ab", "a".repeat(31), "1jane", "_jane", "jane.", "jane-", "ja..ne", "ja__ne", "jane doe", "jane@doe",
                "jan\u00e9", "\u0436\u0430\u043d\u043d\u0430", "jane/doe", "jane<script>", "..", "ja\nne", "UPPER CASE", "j", "-jane"}) {
            assertInvalid("username", () -> service.updateProfile(d, update("username", bad)));
        }
        assertEquals("jane.doe", d.getUsername());
    }

    @Test
    void reservedAndBrandUsernamesAreRefusedEvenWhenDisguised() {
        signedUp();
        Developer d = only();
        for (String bad : new String[] {"admin", "Admin", "root", "support", "ad.min", "a-d-m-i-n", "su_pport", "premisave", "premisave.support",
                "the.premisave.team", "pre.misave", "mpesa.official", "safaricom", "dev", "api", "me"}) {
            assertInvalid("username", () -> service.updateProfile(d, update("username", bad)));
        }
        assertEquals("jane.doe", d.getUsername());
    }

    @Test
    void aUsernameThatIsTakenIsRefusedWhateverTheCase() {
        signedUp();
        service.signup(sign("bob@example.com", "Bob", null, "Smith", "KE", null, null, true, null));
        Developer bob = repo.store.values().stream().filter(x -> x.getEmail().equals("bob@example.com")).findFirst().orElseThrow();
        for (String taken : new String[] {"jane.doe", "JANE.DOE", " Jane.Doe "}) {
            DeveloperException e = assertThrows(DeveloperException.class, () -> service.updateProfile(bob, update("username", taken)));
            assertEquals(ErrorCode.USERNAME_TAKEN, e.code());
        }
        assertEquals("bob.smith", bob.getUsername());
    }

    @Test
    void theDatabaseDecidingARaceForAUsernameAlsoSaysTaken() {
        signedUp();
        Developer d = only();
        repo.duplicateOnSave = true;
        DeveloperException e = assertThrows(DeveloperException.class, () -> service.updateProfile(d, update("username", "brand.new")));
        assertEquals(ErrorCode.USERNAME_TAKEN, e.code());
    }

    @Test
    void theUsernameCanOnlyBeChangedOnceAWeek() {
        signedUp();
        Developer d = only();
        service.updateProfile(d, update("username", "first.pick"));
        now = T0.plus(Duration.ofDays(3));
        DeveloperException e = assertThrows(DeveloperException.class, () -> service.updateProfile(d, update("username", "second.pick")));
        assertEquals(ErrorCode.RATE_LIMITED, e.code());
        assertTrue((Long) e.extra().get("retryAfterSeconds") > 3 * 24 * 3600);
        assertEquals("first.pick", d.getUsername());
        now = T0.plus(Duration.ofDays(7)).plusSeconds(1);
        assertEquals("second.pick", service.updateProfile(d, update("username", "second.pick")).get("username"));
    }

    @Test
    void checkingAUsernameSaysWhetherItIsFreeAndWhyNot() {
        signedUp();
        Developer d = only();
        service.signup(sign("bob@example.com", "Bob", null, "Smith", "KE", null, null, true, null));
        assertEquals(Map.of("username", "jane.doe", "available", true, "reason", "CURRENT"), service.checkUsername(d, "Jane.Doe"));
        Map<String, Object> free = service.checkUsername(d, "Fresh.Name");
        assertEquals("fresh.name", free.get("username"));
        assertEquals(true, free.get("available"));
        assertEquals("OK", free.get("reason"));
        assertEquals("TAKEN", service.checkUsername(d, "bob.smith").get("reason"));
        assertEquals(false, service.checkUsername(d, "bob.smith").get("available"));
        assertEquals("RESERVED", service.checkUsername(d, "support").get("reason"));
        Map<String, Object> bad = service.checkUsername(d, "no spaces");
        assertEquals("INVALID", bad.get("reason"));
        assertEquals(false, bad.get("available"));
        assertNotNull(bad.get("message"));
        assertEquals("INVALID", service.checkUsername(d, "").get("reason"));
    }

    @Test
    void anOlderAccountGetsNamesAndAUsernameWhenItSignsIn() {
        Developer old = new Developer();
        old.setDeveloperId("dev_old");
        old.setEmail("mary@example.com");
        old.setFullName("Mary Jane Watson");
        old.setPasswordHash(hasher.hash("a long password 1"));
        old.setStatus(DeveloperStatus.ACTIVE);
        old.setCreatedAt(T0);
        repo.store.put(old.getDeveloperId(), old);
        Map<String, Object> s = service.login(new LoginRequest("mary@example.com", "a long password 1"));
        assertEquals("Mary", old.getFirstName());
        assertEquals("Jane", old.getMiddleName());
        assertEquals("Watson", old.getLastName());
        assertEquals("mary.watson", old.getUsername());
        assertEquals("mary.watson", developerOf(s).get("username"));
        assertEquals("Mary Jane Watson", old.getFullName());
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
        String token = signedInToken();
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
        String oldToken = signedInToken();
        Developer d = service.authenticate(oldToken);
        Map<String, Object> fresh = service.changePassword(d, new ChangePasswordRequest("a long password 1", "a brand new password 3"));
        assertEquals(ErrorCode.UNAUTHENTICATED, assertThrows(DeveloperException.class, () -> service.authenticate(oldToken)).code());
        assertNotNull(service.authenticate((String) fresh.get("accessToken")));
        assertNotNull(service.login(new LoginRequest("jane@example.com", "a brand new password 3")));
        assertThrows(DeveloperException.class, () -> service.login(new LoginRequest("jane@example.com", "a long password 1")));
    }

    @Test
    void changingThePasswordNeedsTheCurrentOne() {
        Developer d = service.authenticate(signedInToken());
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
        signedUp();
        assertEquals(false, viewOf("jane@example.com").get("emailVerified"));
        assertEquals(true, viewOf("jane@example.com").get("hasPassword"));
        assertEquals(1, mailer.sent.size());
        assertEquals("jane@example.com", mailer.sent.get(0).to());
        assertTrue(mailer.lastLink("https://portal.example.com/verify-email?token=").length() > 60);
    }

    @Test
    void signingInSendsANoticeWithTheDeviceAndAddress() {
        signedUp();
        mailer.sent.clear();
        now = T0.plusSeconds(300);
        service.login(new LoginRequest("jane@example.com", "a long password 1"),
                new ClientInfo("203.0.113.9", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0 Safari/537.36"));
        assertEquals(1, mailer.sent.size());
        TestRepos.RecordingMailer.Sent m = mailer.sent.get(0);
        assertEquals("jane@example.com", m.to());
        assertEquals("New sign in to your Premisave developer account", m.subject());
        assertTrue(m.html().contains("Chrome on Windows"));
        assertTrue(m.html().contains("203.0.113.9"));
        assertTrue(m.html().contains("16 Oct 2026, 08:05 UTC"));
    }

    @Test
    void aFailedSignInSendsNoNotice() {
        signedUp();
        mailer.sent.clear();
        assertThrows(DeveloperException.class, () -> service.login(new LoginRequest("jane@example.com", "wrong password 9")));
        assertThrows(DeveloperException.class, () -> service.login(new LoginRequest("nobody@example.com", "wrong password 9")));
        assertTrue(mailer.sent.isEmpty());
    }

    @Test
    void signInNoticesCanBeSwitchedOff() {
        DeveloperProperties.Mail quiet = new DeveloperProperties.Mail(null, "https://portal.example.com", null, null, 60, 5, true, false, false);
        AccountEmailService quietEmail = new AccountEmailService(repo.repo, tokens, mail, hasher, quiet, 10, clock);
        DeveloperService quietService = new DeveloperService(repo.repo, hasher, jwt, TestProps.fastSecurity(), revoked.repo, quietEmail, clock);
        quietService.signup(signup("jane@example.com", "a long password 1"));
        mailer.sent.clear();
        quietService.login(new LoginRequest("jane@example.com", "a long password 1"));
        assertTrue(mailer.sent.isEmpty());
    }

    @Test
    void profileUpdatesChangeOnlyWhatIsSent() {
        Map<String, Object> s = service.signup(sign("jane@example.com", "Jane", null, "Doe", "KE", "+254700123456", "Acme Ltd", true, false));
        Developer d = only();
        now = T0.plusSeconds(10);
        Map<String, Object> out = service.updateProfile(d, update("country", "tz", "marketingOptIn", "true"));
        assertEquals("TZ", out.get("country"));
        assertEquals("Jane Doe", out.get("fullName"));
        assertEquals("jane.doe", out.get("username"));
        assertEquals("+254700123456", out.get("phoneNumber"));
        assertEquals("Acme Ltd", out.get("companyName"));
        assertEquals(true, out.get("marketingOptIn"));
        assertEquals(T0.plusSeconds(10), d.getUpdatedAt());
        assertNotNull(s.get("message"));
    }

    @Test
    void changingANameUpdatesTheFullNameButNotTheUsername() {
        signedUp();
        Developer d = only();
        Map<String, Object> out = service.updateProfile(d, update("firstName", " Janet ", "middleName", "Rose", "lastName", "Doe-Smith"));
        assertEquals("Janet Rose Doe-Smith", out.get("fullName"));
        assertEquals("Janet", out.get("firstName"));
        assertEquals("Rose", out.get("middleName"));
        assertEquals("Doe-Smith", out.get("lastName"));
        assertEquals("jane.doe", out.get("username"));
        service.updateProfile(d, update("middleName", " "));
        assertNull(d.getMiddleName());
        assertEquals("Janet Doe-Smith", d.getFullName());
    }

    @Test
    void theAddressAndTimeZoneCanBeSetTidiedAndCleared() {
        signedUp();
        Developer d = only();
        Map<String, Object> out = service.updateProfile(d, update("addressLine1", "  12  Riverside Drive ", "addressLine2", "Apt 4B, Block C",
                "city", "Nairobi", "stateOrRegion", "Nairobi County", "postalCode", " 00100 ", "timezone", "Africa/Nairobi"));
        assertEquals("12 Riverside Drive", out.get("addressLine1"));
        assertEquals("Apt 4B, Block C", out.get("addressLine2"));
        assertEquals("Nairobi", out.get("city"));
        assertEquals("Nairobi County", out.get("stateOrRegion"));
        assertEquals("00100", out.get("postalCode"));
        assertEquals("Africa/Nairobi", out.get("timezone"));
        service.updateProfile(d, update("postalCode", "sw1a 1aa"));
        assertEquals("SW1A 1AA", d.getPostalCode());
        service.updateProfile(d, update("addressLine2", "", "city", " ", "stateOrRegion", "", "postalCode", "", "timezone", ""));
        assertNull(d.getAddressLine2());
        assertNull(d.getCity());
        assertNull(d.getStateOrRegion());
        assertNull(d.getPostalCode());
        assertNull(d.getTimezone());
        assertEquals("12 Riverside Drive", d.getAddressLine1());
    }

    @Test
    void badAddressInputIsRefused() {
        signedUp();
        Developer d = only();
        Object[][] bad = {
                {"timezone", "+03:00"}, {"timezone", "Mars/Phobos"}, {"timezone", "EAT"}, {"timezone", "Africa"},
                {"postalCode", "!!"}, {"postalCode", "1"}, {"postalCode", "123456789012345"},
                {"addressLine1", "<script>"}, {"addressLine1", "x".repeat(121)}, {"addressLine2", "line\u0000break"},
                {"city", "a>b"}, {"stateOrRegion", "x".repeat(81)}};
        for (Object[] c : bad) {
            assertInvalid((String) c[0], () -> service.updateProfile(d, update((String) c[0], (String) c[1])));
        }
        assertNull(d.getTimezone());
        assertNull(d.getPostalCode());
    }

    @Test
    void profileFieldsCanBeClearedWithAnEmptyString() {
        signedUp();
        Developer d = only();
        service.updateProfile(d, update("phoneNumber", "0700123456", "companyName", "Acme"));
        assertEquals("0700123456", d.getPhoneNumber());
        service.updateProfile(d, update("phoneNumber", "", "companyName", " "));
        assertNull(d.getPhoneNumber());
        assertNull(d.getCompanyName());
    }

    @Test
    void requiredProfileFieldsCannotBeCleared() {
        signedUp();
        Developer d = only();
        for (String f : new String[] {"firstName", "lastName", "country"}) {
            assertInvalid(f, () -> service.updateProfile(d, update(f, "  ")));
        }
        assertEquals("Jane", d.getFirstName());
        assertEquals("Doe", d.getLastName());
        assertEquals("KE", d.getCountry());
    }

    @Test
    void badProfileInputIsRefusedAndNothingChanges() {
        signedUp();
        Developer d = only();
        assertInvalid("firstName", () -> service.updateProfile(d, update("firstName", "J4ne", "city", "Nairobi", "username", "valid.name")));
        assertInvalid("country", () -> service.updateProfile(d, update("lastName", "Smith", "country", "1X")));
        assertInvalid("phoneNumber", () -> service.updateProfile(d, update("phoneNumber", "abc", "city", "Nairobi")));
        assertInvalid("username", () -> service.updateProfile(d, update("firstName", "Janet", "username", "admin")));
        assertEquals("Jane Doe", d.getFullName());
        assertEquals("Jane", d.getFirstName());
        assertEquals("Doe", d.getLastName());
        assertEquals("KE", d.getCountry());
        assertEquals("jane.doe", d.getUsername());
        assertNull(d.getCity());
        assertNull(d.getUsernameChangedAt());
    }

    @Test
    void aMailServerThatIsDownDoesNotStopSignUp() {
        mailer.fail = true;
        Map<String, Object> s = signedUp();
        assertNotNull(s.get("message"));
        assertEquals(1, repo.store.size());
    }

    @Test
    void everyTokenCarriesItsOwnId() {
        signedUp();
        Map<String, Object> a = service.login(new LoginRequest("jane@example.com", "a long password 1"));
        Map<String, Object> b = service.login(new LoginRequest("jane@example.com", "a long password 1"));
        JwtService.Claims ca = jwt.verify(tokenOf(a)).orElseThrow();
        JwtService.Claims cb = jwt.verify(tokenOf(b)).orElseThrow();
        assertFalse(ca.tokenId().isEmpty());
        assertNotEquals(ca.tokenId(), cb.tokenId());
    }

    @Test
    void logoutEndsOnlyTheTokenUsed() {
        signedUp();
        Map<String, Object> first = service.login(new LoginRequest("jane@example.com", "a long password 1"));
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
        signedUp();
        Map<String, Object> first = service.login(new LoginRequest("jane@example.com", "a long password 1"));
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

    // ---- sign up answers with a message, not a token

    @Test
    void signupAnswersWithAMessageAndTheNextStep() {
        Map<String, Object> s = signedUp();
        assertEquals("SIGN_IN", s.get("next"));
        assertEquals(true, s.get("confirmationEmailSent"));
        String message = (String) s.get("message");
        assertTrue(message.startsWith("Your account has been created and is pending until you confirm your email address."));
        assertTrue(message.endsWith("Sign in to continue."));
        assertTrue(message.contains("jane@example.com"));
        assertEquals("PENDING", s.get("status"));
    }

    @Test
    void signupReturnsNoAccountDataAndLeavesTheAccountPending() {
        Map<String, Object> s = signedUp();
        assertEquals(java.util.Set.of("message", "next", "status", "confirmationEmailSent"), s.keySet());
        assertFalse(s.containsKey("developer"));
        assertEquals(DeveloperStatus.PENDING, only().getStatus());
        assertFalse(only().isEmailVerified());
    }

    @Test
    void aPendingAccountCanStillSignInButStaysPendingUntilTheEmailIsConfirmed() {
        signedUp();
        Map<String, Object> in = service.login(new LoginRequest("jane@example.com", "a long password 1"));
        assertNotNull(in.get("accessToken"));
        assertEquals("PENDING", developerOf(in).get("status"));
        assertEquals(DeveloperStatus.PENDING, only().getStatus());
    }

    @Test
    void confirmingTheEmailActivatesThePendingAccount() {
        signedUp();
        String link = mailer.lastLink("https://portal.example.com/verify-email?token=");
        accountEmail.verifyEmail(link.substring("https://portal.example.com/verify-email?token=".length()));
        assertEquals(DeveloperStatus.ACTIVE, only().getStatus());
        assertTrue(only().isEmailVerified());
        assertEquals("ACTIVE", viewOf("jane@example.com").get("status"));
    }

    @Test
    void signupWithoutAMailServerSaysNothingAboutAnEmail() {
        MailService noMail = new MailService(null, Runnable::run, false);
        AccountEmailService quietEmail = new AccountEmailService(repo.repo, tokens, noMail, hasher, TestProps.mail(), 10, clock);
        DeveloperService quietService = new DeveloperService(repo.repo, hasher, jwt, TestProps.fastSecurity(), revoked.repo, quietEmail, clock);
        Map<String, Object> s = quietService.signup(signup("jane@example.com", "a long password 1"));
        assertEquals("Your account has been created and is pending until your email address is confirmed. Sign in to continue.", s.get("message"));
        assertEquals(false, s.get("confirmationEmailSent"));
        assertEquals("SIGN_IN", s.get("next"));
        assertTrue(mailer.sent.isEmpty());
    }

    @Test
    void signingInWorksStraightAfterSignupBeforeTheEmailIsConfirmed() {
        signedUp();
        Map<String, Object> in = service.login(new LoginRequest("jane@example.com", "a long password 1"));
        assertNotNull(in.get("accessToken"));
        assertEquals(false, developerOf(in).get("emailVerified"));
        assertEquals(now, repo.store.values().iterator().next().getLastLoginAt());
    }
}