package com.premisave.developer.service;

import com.premisave.developer.FakeTransport;
import com.premisave.developer.TestProps;
import com.premisave.developer.TestRepos;
import com.premisave.developer.client.OAuthClient;
import com.premisave.developer.client.OAuthClientTest;
import com.premisave.developer.dto.ChangePasswordRequest;
import com.premisave.developer.dto.LoginRequest;
import com.premisave.developer.enums.DeveloperStatus;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.enums.OAuthProvider;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.Developer;
import com.premisave.developer.security.JwtService;
import com.premisave.developer.security.OAuthState;
import com.premisave.developer.security.PasswordHasher;

import org.junit.jupiter.api.Test;

import java.time.Clock;
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

class OAuthServiceTest {

    private static final String URI = "https://portal.example.com/auth/callback";

    private final Instant now = Instant.parse("2026-10-16T08:00:00Z");
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };
    private final TestRepos.Developers devs = new TestRepos.Developers();
    private final TestRepos.Identities ids = new TestRepos.Identities();
    private final TestRepos.Revoked revoked = new TestRepos.Revoked();
    private final TestRepos.EmailTokens tokenRepo = new TestRepos.EmailTokens();
    private final TestRepos.RecordingMailer mailer = new TestRepos.RecordingMailer();
    private final PasswordHasher hasher = new PasswordHasher(10_000);
    private final JwtService jwt = new JwtService(TestProps.JWT_SECRET, "iss", 60, clock);
    private final AccountEmailService accountEmail = new AccountEmailService(devs.repo,
            new EmailTokenService(tokenRepo.repo, TestProps.mail(), TestProps.PEPPER, clock),
            new MailService(mailer, Runnable::run, false), hasher, TestProps.mail(), 10, clock);
    private final DeveloperService accounts = new DeveloperService(devs.repo, hasher, jwt, TestProps.fastSecurity(),
            revoked.repo, accountEmail, clock);
    private final FakeTransport http = new FakeTransport();
    private final OAuthState states = new OAuthState(TestProps.JWT_SECRET, 10, clock);
    private final OAuthService service = new OAuthService(new OAuthClient(OAuthClientTest.config(), http), states,
            ids.repo, devs.repo, accounts, accountEmail, OAuthClientTest.config(), clock);

    private void google(String sub, String email, boolean verified, String name) {
        http.on("https://oauth2.googleapis.com/token", 200, "{\"access_token\":\"t\"}")
            .on("https://openidconnect.googleapis.com/", 200, "{\"sub\":\"" + sub + "\",\"name\":" + (name == null ? "null" : "\"" + name + "\"")
                    + ",\"email\":" + (email == null ? "null" : "\"" + email + "\"") + ",\"email_verified\":" + verified + "}");
    }

    private void facebook(String id, String email) {
        http.on("https://graph.facebook.com/v21.0/oauth/access_token", 200, "{\"access_token\":\"t\"}")
            .on("https://graph.facebook.com/v21.0/me", 200, "{\"id\":\"" + id + "\",\"name\":\"Fb User\""
                    + (email == null ? "" : ",\"email\":\"" + email + "\"") + "}");
    }

    private Map<String, Object> signIn(String provider) {
        String state = states.create(OAuthProvider.parse(provider), URI);
        return service.callback(provider, "code-1", state, URI);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> developerOf(Map<String, Object> s) {
        return (Map<String, Object>) s.get("developer");
    }

    private Developer localAccount(String email, boolean verified) {
        Developer d = new Developer();
        d.setDeveloperId("dev_local");
        d.setEmail(email);
        d.setFullName("Local Person");
        d.setPasswordHash(hasher.hash("a long password 1"));
        d.setStatus(DeveloperStatus.ACTIVE);
        d.setEmailVerified(verified);
        d.setCreatedAt(now);
        devs.store.put(d.getDeveloperId(), d);
        return d;
    }

    // ---- the way in

    @Test
    void theProviderListShowsOnlyWhatIsSwitchedOn() {
        assertEquals(java.util.List.of("google", "facebook", "github"), service.providers().get("providers"));
    }

    @Test
    void authorizeGivesAnAddressAndAStateBoundToTheReturnAddress() {
        Map<String, Object> out = service.authorize("google", URI);
        assertEquals("google", out.get("provider"));
        String url = (String) out.get("authorizationUrl");
        String state = (String) out.get("state");
        assertTrue(url.contains("state=" + java.net.URLEncoder.encode(state, java.nio.charset.StandardCharsets.UTF_8)));
        states.verify(state, OAuthProvider.GOOGLE, URI);
    }

    @Test
    void aReturnAddressThatIsNotListedIsRefused() {
        for (String bad : new String[] {"https://evil.example.com/cb", URI + "/", "http://portal.example.com/auth/callback", "", null}) {
            DeveloperException e = assertThrows(DeveloperException.class, () -> service.authorize("google", bad));
            assertEquals(ErrorCode.VALIDATION_FAILED, e.code());
        }
        String state = states.create(OAuthProvider.GOOGLE, "https://evil.example.com/cb");
        assertEquals(ErrorCode.VALIDATION_FAILED,
                assertThrows(DeveloperException.class, () -> service.callback("google", "c", state, "https://evil.example.com/cb")).code());
        assertTrue(http.calls.isEmpty());
    }

    @Test
    void anUnknownOrSwitchedOffProviderIsRefused() {
        assertEquals(ErrorCode.OAUTH_NOT_CONFIGURED, assertThrows(DeveloperException.class, () -> service.authorize("myspace", URI)).code());
        OAuthService off = new OAuthService(new OAuthClient(new com.premisave.developer.config.DeveloperProperties.OAuth(null, null, null,
                java.util.List.of(URI), null), http), states, ids.repo, devs.repo, accounts, accountEmail,
                new com.premisave.developer.config.DeveloperProperties.OAuth(null, null, null, java.util.List.of(URI), null), clock);
        assertEquals(ErrorCode.OAUTH_NOT_CONFIGURED, assertThrows(DeveloperException.class, () -> off.authorize("google", URI)).code());
        assertEquals(java.util.List.of(), off.providers().get("providers"));
    }

    @Test
    void aBadStateIsRefusedBeforeTheProviderIsContacted() {
        String other = states.create(OAuthProvider.GITHUB, URI);
        assertEquals(ErrorCode.OAUTH_FAILED, assertThrows(DeveloperException.class, () -> service.callback("google", "c", other, URI)).code());
        assertEquals(ErrorCode.OAUTH_FAILED, assertThrows(DeveloperException.class, () -> service.callback("google", "c", "forged", URI)).code());
        assertTrue(http.calls.isEmpty());
    }

    // ---- a new person

    @Test
    void googleCreatesAnAccountThatIsAlreadyConfirmed() {
        google("g-1", "Jane@Gmail.com", true, "Jane Doe");
        Map<String, Object> s = signIn("google");
        assertEquals(true, s.get("newAccount"));
        assertEquals("google", s.get("provider"));
        assertNotNull(jwt.verify((String) s.get("accessToken")).orElseThrow());
        Map<String, Object> dev = developerOf(s);
        assertEquals("jane@gmail.com", dev.get("email"));
        assertEquals("Jane Doe", dev.get("fullName"));
        assertEquals(true, dev.get("emailVerified"));
        assertEquals("ACTIVE", dev.get("status"));
        assertEquals(false, dev.get("hasPassword"));
        assertEquals(java.util.List.of("google"), dev.get("providers"));
        assertTrue(ids.store.containsKey("google:g-1"));
        assertEquals("dev_", ((String) dev.get("developerId")).substring(0, 4));
        // A confirmed address gets the welcome email at once, and no confirmation link.
        assertEquals(1, mailer.sent.size());
        assertEquals("Welcome to Premisave", mailer.sent.get(0).subject());
        assertEquals("jane@gmail.com", mailer.sent.get(0).to());
        assertFalse(mailer.sent.get(0).html().contains("verify-email"));
        assertEquals(false, dev.get("profileComplete"));
        assertEquals("Jane", dev.get("firstName"));
        assertNull(dev.get("middleName"));
        assertEquals("Doe", dev.get("lastName"));
        assertEquals("jane.doe", dev.get("username"));
    }

    @Test
    void aProviderNameIsSplitIntoFirstMiddleAndLastAndTheUsernameIsUnique() {
        google("g-1", "jane@gmail.com", true, "Jane Mary Doe");
        Map<String, Object> first = developerOf(signIn("google"));
        assertEquals("Jane", first.get("firstName"));
        assertEquals("Mary", first.get("middleName"));
        assertEquals("Doe", first.get("lastName"));
        assertEquals("jane.doe", first.get("username"));
        google("g-2", "jane.two@gmail.com", true, "Jane Doe");
        String second = (String) developerOf(signIn("google")).get("username");
        assertNotEquals("jane.doe", second);
        assertTrue(second.startsWith("jane.doe"), second);
    }

    @Test
    void aProviderThatGivesOneWordOrNoNameStillGetsAFirstNameAndUsername() {
        google("g-1", "someone@gmail.com", true, null);
        Map<String, Object> dev = developerOf(signIn("google"));
        assertEquals("someone", dev.get("firstName"));
        assertNull(dev.get("lastName"));
        assertEquals("someone", dev.get("username"));
        assertEquals(false, dev.get("profileComplete"));
    }

    @Test
    void anOlderAccountJoinedThroughAProviderGetsAUsername() {
        Developer d = localAccount("jane@example.com", true);
        google("g-9", "jane@example.com", true, "Jane");
        Map<String, Object> s = signIn("google");
        assertEquals("Local", d.getFirstName());
        assertEquals("Person", d.getLastName());
        assertEquals("local.person", developerOf(s).get("username"));
    }

    @Test
    void comingBackThroughAProviderSendsASignInNoticeNamingTheProvider() {
        google("g-1", "jane@gmail.com", true, "Jane Doe");
        signIn("google");
        mailer.sent.clear();
        Map<String, Object> again = signIn("google");
        assertEquals(false, again.get("newAccount"));
        assertEquals(1, mailer.sent.size());
        assertEquals("New sign in to your Premisave developer account", mailer.sent.get(0).subject());
        assertTrue(mailer.sent.get(0).html().contains("Google"));
    }

    @Test
    void facebookCreatesAnUnconfirmedAccountAndSendsAConfirmationLink() {
        facebook("f-1", "fb.user@example.com");
        Map<String, Object> s = signIn("facebook");
        assertEquals(true, s.get("newAccount"));
        assertEquals(false, developerOf(s).get("emailVerified"));
        assertEquals("PENDING", developerOf(s).get("status"));
        assertEquals(1, mailer.sent.size());
        assertEquals("fb.user@example.com", mailer.sent.get(0).to());
    }

    @Test
    void theSamePersonSigningInAgainGetsTheSameAccount() {
        google("g-1", "jane@gmail.com", true, "Jane Doe");
        String id = (String) developerOf(signIn("google")).get("developerId");
        Map<String, Object> again = signIn("google");
        assertEquals(false, again.get("newAccount"));
        assertEquals(id, developerOf(again).get("developerId"));
        assertEquals(1, devs.store.size());
        assertEquals(now, devs.store.get(id).getLastLoginAt());
    }

    @Test
    void aReturningPersonIsFoundByTheirProviderIdEvenIfTheAddressChanged() {
        google("g-1", "jane@gmail.com", true, "Jane Doe");
        String id = (String) developerOf(signIn("google")).get("developerId");
        google("g-1", "jane.new@gmail.com", true, "Jane Doe");
        assertEquals(id, developerOf(signIn("google")).get("developerId"));
        assertEquals("jane@gmail.com", devs.store.get(id).getEmail());
    }

    @Test
    void aProviderWithoutAnAddressCannotCreateAnAccount() {
        facebook("f-2", null);
        assertEquals(ErrorCode.OAUTH_EMAIL_REQUIRED, assertThrows(DeveloperException.class, () -> signIn("facebook")).code());
        google("g-2", "not-an-email", true, "X");
        assertEquals(ErrorCode.OAUTH_EMAIL_REQUIRED, assertThrows(DeveloperException.class, () -> signIn("google")).code());
        assertTrue(devs.store.isEmpty());
        assertTrue(ids.store.isEmpty());
    }

    @Test
    void aMissingNameFallsBackToTheStartOfTheAddress() {
        google("g-3", "someone@gmail.com", true, null);
        assertEquals("someone", developerOf(signIn("google")).get("fullName"));
    }

    @Test
    void aSubjectMissingFromTheProfileIsRefused() {
        http.on("https://oauth2.googleapis.com/token", 200, "{\"access_token\":\"t\"}")
            .on("https://openidconnect.googleapis.com/", 200, "{\"email\":\"a@b.com\",\"email_verified\":true}");
        assertEquals(ErrorCode.OAUTH_FAILED, assertThrows(DeveloperException.class, () -> signIn("google")).code());
    }

    // ---- someone who already has an account

    @Test
    void aProviderThatVouchesForTheAddressJoinsAConfirmedAccountAndKeepsItsPassword() {
        Developer d = localAccount("jane@example.com", true);
        int version = d.getTokenVersion();
        google("g-9", "jane@example.com", true, "Jane");
        Map<String, Object> s = signIn("google");
        assertEquals(false, s.get("newAccount"));
        assertEquals("dev_local", developerOf(s).get("developerId"));
        assertEquals(java.util.List.of("google"), developerOf(s).get("providers"));
        assertNotNull(d.getPasswordHash());
        assertEquals(version, d.getTokenVersion());
        assertNotNull(accounts.login(new LoginRequest("jane@example.com", "a long password 1")));
    }

    @Test
    void joiningAnUnconfirmedAccountRemovesThePasswordSomeoneTypedInWithoutProof() {
        Developer d = localAccount("jane@example.com", false);
        d.setStatus(DeveloperStatus.PENDING);
        int version = d.getTokenVersion();
        google("g-9", "jane@example.com", true, "Jane");
        Map<String, Object> s = signIn("google");
        assertEquals("dev_local", developerOf(s).get("developerId"));
        assertNull(d.getPasswordHash());
        assertTrue(d.isEmailVerified());
        assertEquals(DeveloperStatus.ACTIVE, d.getStatus());
        assertEquals(version + 1, d.getTokenVersion());
        assertEquals(ErrorCode.INVALID_CREDENTIALS,
                assertThrows(DeveloperException.class, () -> accounts.login(new LoginRequest("jane@example.com", "a long password 1"))).code());
        // The token issued by the sign in itself is the new one and still works.
        assertNotNull(accounts.authenticate((String) s.get("accessToken")));
    }

    @Test
    void aProviderThatDoesNotVouchForTheAddressNeverJoinsAnExistingAccount() {
        Developer d = localAccount("jane@example.com", true);
        facebook("f-7", "jane@example.com");
        DeveloperException e = assertThrows(DeveloperException.class, () -> signIn("facebook"));
        assertEquals(ErrorCode.ACCOUNT_EXISTS, e.code());
        assertTrue(ids.store.isEmpty());
        assertNotNull(d.getPasswordHash());
        assertEquals(1, devs.store.size());
    }

    @Test
    void googleSayingTheAddressIsNotVerifiedDoesNotJoinEither() {
        localAccount("jane@example.com", true);
        google("g-9", "jane@example.com", false, "Jane");
        assertEquals(ErrorCode.ACCOUNT_EXISTS, assertThrows(DeveloperException.class, () -> signIn("google")).code());
    }

    @Test
    void aDisabledAccountCannotSignInThroughAProvider() {
        Developer d = localAccount("jane@example.com", true);
        google("g-9", "jane@example.com", true, "Jane");
        signIn("google");
        d.setStatus(DeveloperStatus.DISABLED);
        assertEquals(ErrorCode.ACCOUNT_DISABLED, assertThrows(DeveloperException.class, () -> signIn("google")).code());
    }

    @Test
    void aDisabledAccountCannotBeJoinedEither() {
        Developer d = localAccount("jane@example.com", true);
        d.setStatus(DeveloperStatus.DISABLED);
        google("g-9", "jane@example.com", true, "Jane");
        assertEquals(ErrorCode.ACCOUNT_DISABLED, assertThrows(DeveloperException.class, () -> signIn("google")).code());
        assertTrue(ids.store.isEmpty());
    }

    @Test
    void aProviderAccountCanLaterSetAPasswordWithoutKnowingAnOldOne() {
        google("g-1", "jane@gmail.com", true, "Jane Doe");
        Map<String, Object> s = signIn("google");
        Developer d = accounts.authenticate((String) s.get("accessToken"));
        accounts.changePassword(d, new ChangePasswordRequest(null, "a brand new password 2"));
        assertNotNull(accounts.login(new LoginRequest("jane@gmail.com", "a brand new password 2")));
    }

    @Test
    void aFailingProviderLeavesNothingBehind() {
        http.down = true;
        assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE, assertThrows(DeveloperException.class, () -> signIn("github")).code());
        assertTrue(devs.store.isEmpty());
        assertFalse(ids.store.containsKey("github:1"));
    }
}
