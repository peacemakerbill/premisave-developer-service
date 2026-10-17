package com.premisave.developer.client;

import com.premisave.developer.FakeTransport;
import com.premisave.developer.config.DeveloperProperties;
import com.premisave.developer.dto.OAuthProfile;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.enums.OAuthProvider;
import com.premisave.developer.exception.DeveloperException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OAuthClientTest {

    private static DeveloperProperties.Provider creds(String id) {
        return new DeveloperProperties.Provider(id, id + "-secret", null, null, null, null);
    }

    public static DeveloperProperties.OAuth config() {
        return new DeveloperProperties.OAuth(creds("gid"), creds("fid"), creds("hid"));
    }

    private final FakeTransport http = new FakeTransport();
    private final OAuthClient client = new OAuthClient(config(), http);

    private static String googleToken(String aud, String iss, String sub, String email, String verified) {
        return "{\"aud\":\"" + aud + "\",\"iss\":\"" + iss + "\",\"sub\":\"" + sub + "\",\"name\":\"Jane Doe\",\"email\":\""
                + email + "\",\"email_verified\":\"" + verified + "\",\"exp\":\"1999999999\"}";
    }

    private static String facebookCheck(String appId, boolean valid, String userId) {
        return "{\"data\":{\"app_id\":\"" + appId + "\",\"is_valid\":" + valid + ",\"user_id\":\"" + userId + "\"}}";
    }

    // ---- which providers are on

    @Test
    void googleIsOnWithAClientIdAndTheOthersNeedTheSecretToo() {
        assertTrue(client.enabled(OAuthProvider.GOOGLE));
        assertTrue(client.enabled(OAuthProvider.FACEBOOK));
        assertTrue(client.enabled(OAuthProvider.GITHUB));

        DeveloperProperties.Provider idOnly = new DeveloperProperties.Provider("id", null, null, null, null, null);
        OAuthClient c = new OAuthClient(new DeveloperProperties.OAuth(idOnly, idOnly, idOnly), http);
        assertTrue(c.enabled(OAuthProvider.GOOGLE));
        assertFalse(c.enabled(OAuthProvider.FACEBOOK));
        assertFalse(c.enabled(OAuthProvider.GITHUB));

        OAuthClient none = new OAuthClient(new DeveloperProperties.OAuth(null, null, null), http);
        for (OAuthProvider p : OAuthProvider.values()) assertFalse(none.enabled(p));
    }

    @Test
    void theClientIdIsGivenOnlyForAProviderThatIsOn() {
        assertEquals("gid", client.clientId(OAuthProvider.GOOGLE));
        assertEquals("fid", client.clientId(OAuthProvider.FACEBOOK));
        assertEquals("hid", client.clientId(OAuthProvider.GITHUB));
        OAuthClient none = new OAuthClient(new DeveloperProperties.OAuth(null, null, null), http);
        assertNull(none.clientId(OAuthProvider.GOOGLE));
    }

    // ---- Google: an ID token

    @Test
    void googleGivesAVerifiedAddressFromAnIdTokenIssuedToThisClient() {
        http.on("https://oauth2.googleapis.com/tokeninfo", 200,
                googleToken("gid", "https://accounts.google.com", "1234567890", "Jane@Gmail.com", "true"));
        OAuthProfile p = client.fetchProfile(OAuthProvider.GOOGLE, "the.id.token");
        assertEquals("1234567890", p.subject());
        assertEquals("Jane@Gmail.com", p.email());
        assertTrue(p.emailVerified());
        assertEquals("Jane Doe", p.name());
        assertEquals("GET", http.last().method());
        assertEquals("https://oauth2.googleapis.com/tokeninfo?id_token=the.id.token", http.last().url());
        assertEquals(1, http.calls.size());
    }

    @Test
    void googleAcceptsTheShortFormOfTheIssuerAndABooleanVerifiedFlag() {
        http.on("https://oauth2.googleapis.com/tokeninfo", 200,
                "{\"aud\":\"gid\",\"iss\":\"accounts.google.com\",\"sub\":\"1\",\"email\":\"a@b.com\",\"email_verified\":true}");
        assertTrue(client.fetchProfile(OAuthProvider.GOOGLE, "t").emailVerified());
    }

    @Test
    void googleSayingTheAddressIsNotVerifiedIsBelieved() {
        http.on("https://oauth2.googleapis.com/tokeninfo", 200,
                googleToken("gid", "https://accounts.google.com", "1", "a@b.com", "false"));
        assertFalse(client.fetchProfile(OAuthProvider.GOOGLE, "t").emailVerified());
    }

    @Test
    void anIdTokenIssuedToAnotherClientIsRefused() {
        http.on("https://oauth2.googleapis.com/tokeninfo", 200,
                googleToken("someone-elses-client", "https://accounts.google.com", "1", "a@b.com", "true"));
        assertEquals(ErrorCode.OAUTH_FAILED,
                assertThrows(DeveloperException.class, () -> client.fetchProfile(OAuthProvider.GOOGLE, "t")).code());
    }

    @Test
    void anIdTokenFromAnotherIssuerIsRefused() {
        http.on("https://oauth2.googleapis.com/tokeninfo", 200,
                googleToken("gid", "https://evil.example.com", "1", "a@b.com", "true"));
        assertEquals(ErrorCode.OAUTH_FAILED,
                assertThrows(DeveloperException.class, () -> client.fetchProfile(OAuthProvider.GOOGLE, "t")).code());
    }

    @Test
    void anIdTokenGoogleRejectsIsRefusedWithoutGooglesOwnWords() {
        http.on("https://oauth2.googleapis.com/tokeninfo", 400, "{\"error\":\"invalid_token\",\"error_description\":\"secret detail\"}");
        DeveloperException e = assertThrows(DeveloperException.class, () -> client.fetchProfile(OAuthProvider.GOOGLE, "t"));
        assertEquals(ErrorCode.OAUTH_FAILED, e.code());
        assertFalse(e.getMessage().contains("secret detail"));
    }

    @Test
    void aReplyThatIsNotJsonIsRefused() {
        http.on("https://oauth2.googleapis.com/tokeninfo", 200, "<html>oops</html>");
        assertEquals(ErrorCode.OAUTH_FAILED,
                assertThrows(DeveloperException.class, () -> client.fetchProfile(OAuthProvider.GOOGLE, "t")).code());
    }

    // ---- Facebook: an access token

    @Test
    void facebookTokenIsCheckedAgainstThisAppAndTheAddressIsNeverCountedAsVerified() {
        http.on("https://graph.facebook.com/v21.0/debug_token", 200, facebookCheck("fid", true, "99887766"))
            .on("https://graph.facebook.com/v21.0/me", 200, "{\"id\":\"99887766\",\"name\":\"Jane Doe\",\"email\":\"jane@fb.com\"}");
        OAuthProfile p = client.fetchProfile(OAuthProvider.FACEBOOK, "user-token");
        assertEquals("99887766", p.subject());
        assertEquals("jane@fb.com", p.email());
        assertFalse(p.emailVerified());
        assertEquals("Jane Doe", p.name());

        FakeTransport.Call check = http.calls.get(0);
        assertEquals("GET", check.method());
        assertTrue(check.url().contains("input_token=user-token"));
        // The app's own credentials ask the question, joined with a vertical bar (encoded in the address).
        assertTrue(check.url().contains("access_token=fid%7Cfid-secret"));
        assertEquals("Bearer user-token", http.last().headers().get("Authorization"));
    }

    @Test
    void aFacebookTokenIssuedToAnotherAppIsRefusedAndNoProfileIsRead() {
        http.on("https://graph.facebook.com/v21.0/debug_token", 200, facebookCheck("another-app", true, "1"));
        assertEquals(ErrorCode.OAUTH_FAILED,
                assertThrows(DeveloperException.class, () -> client.fetchProfile(OAuthProvider.FACEBOOK, "t")).code());
        assertEquals(1, http.calls.size());
    }

    @Test
    void aFacebookTokenThatIsNotValidIsRefused() {
        http.on("https://graph.facebook.com/v21.0/debug_token", 200, facebookCheck("fid", false, "1"));
        assertEquals(ErrorCode.OAUTH_FAILED,
                assertThrows(DeveloperException.class, () -> client.fetchProfile(OAuthProvider.FACEBOOK, "t")).code());
        http.on("https://graph.facebook.com/v21.0/debug_token", 200, "{\"error\":{\"message\":\"bad\"}}");
        assertEquals(ErrorCode.OAUTH_FAILED,
                assertThrows(DeveloperException.class, () -> client.fetchProfile(OAuthProvider.FACEBOOK, "t")).code());
    }

    @Test
    void aFacebookTokenWhoseOwnerAndProfileDifferAreRefused() {
        http.on("https://graph.facebook.com/v21.0/debug_token", 200, facebookCheck("fid", true, "111"))
            .on("https://graph.facebook.com/v21.0/me", 200, "{\"id\":\"222\",\"name\":\"X\"}");
        assertEquals(ErrorCode.OAUTH_FAILED,
                assertThrows(DeveloperException.class, () -> client.fetchProfile(OAuthProvider.FACEBOOK, "t")).code());
    }

    @Test
    void facebookMayGiveNoAddressAtAll() {
        http.on("https://graph.facebook.com/v21.0/debug_token", 200, facebookCheck("fid", true, "5"))
            .on("https://graph.facebook.com/v21.0/me", 200, "{\"id\":\"5\",\"name\":\"No Mail\"}");
        assertNull(client.fetchProfile(OAuthProvider.FACEBOOK, "t").email());
    }

    @Test
    void aFacebookCheckThatIsRefusedIsRefused() {
        http.on("https://graph.facebook.com/v21.0/debug_token", 400, "{}");
        assertEquals(ErrorCode.OAUTH_FAILED,
                assertThrows(DeveloperException.class, () -> client.fetchProfile(OAuthProvider.FACEBOOK, "t")).code());
    }

    // ---- GitHub: a one time code

    @Test
    void githubTradesTheCodeAndUsesThePrimaryVerifiedAddressAndNeverThePublicProfileOne() {
        http.on("https://github.com/login/oauth/access_token", 200, "{\"access_token\":\"gho_x\",\"token_type\":\"bearer\"}")
            .on("https://api.github.com/user/emails", 200,
                "[{\"email\":\"old@x.com\",\"primary\":false,\"verified\":true},"
                + "{\"email\":\"unproven@x.com\",\"primary\":true,\"verified\":false},"
                + "{\"email\":\"main@x.com\",\"primary\":true,\"verified\":true}]")
            .on("https://api.github.com/user", 200, "{\"id\":583231,\"login\":\"octocat\",\"name\":null,\"email\":\"public@x.com\"}");
        OAuthProfile p = client.fetchProfile(OAuthProvider.GITHUB, "the-code");
        assertEquals("583231", p.subject());
        assertEquals("main@x.com", p.email());
        assertTrue(p.emailVerified());
        assertEquals("octocat", p.name());

        FakeTransport.Call trade = http.calls.get(0);
        assertEquals("POST", trade.method());
        assertEquals("the-code", trade.form().get("code"));
        assertEquals("hid", trade.form().get("client_id"));
        assertEquals("hid-secret", trade.form().get("client_secret"));
        assertFalse(trade.form().containsKey("redirect_uri"));
        assertEquals("application/json", trade.headers().get("Accept"));
        assertEquals("premisave-developer-service", http.calls.get(1).headers().get("User-Agent"));
    }

    @Test
    void githubFallsBackToAnyVerifiedAddressAndOtherwiseGivesNone() {
        http.on("https://github.com/login/oauth/access_token", 200, "{\"access_token\":\"t\"}")
            .on("https://api.github.com/user/emails", 200, "[{\"email\":\"one@x.com\",\"primary\":false,\"verified\":true}]")
            .on("https://api.github.com/user", 200, "{\"id\":1,\"login\":\"o\"}");
        assertEquals("one@x.com", client.fetchProfile(OAuthProvider.GITHUB, "c").email());

        http.on("https://api.github.com/user/emails", 200, "[{\"email\":\"u@x.com\",\"primary\":true,\"verified\":false}]");
        OAuthProfile none = client.fetchProfile(OAuthProvider.GITHUB, "c");
        assertNull(none.email());
        assertFalse(none.emailVerified());

        http.on("https://api.github.com/user/emails", 403, "{\"message\":\"no\"}");
        assertNull(client.fetchProfile(OAuthProvider.GITHUB, "c").email());

        http.on("https://api.github.com/user/emails", 200, "not json");
        assertNull(client.fetchProfile(OAuthProvider.GITHUB, "c").email());
    }

    @Test
    void githubAnswersARejectedCodeWithA200AndAnError() {
        http.on("https://github.com/login/oauth/access_token", 200, "{\"error\":\"bad_verification_code\"}");
        assertEquals(ErrorCode.OAUTH_FAILED,
                assertThrows(DeveloperException.class, () -> client.fetchProfile(OAuthProvider.GITHUB, "c")).code());
    }

    // ---- failures

    @Test
    void aRefusedProfileRequestIsRefused() {
        http.on("https://github.com/login/oauth/access_token", 200, "{\"access_token\":\"t\"}")
            .on("https://api.github.com/user", 401, "{}");
        assertEquals(ErrorCode.OAUTH_FAILED,
                assertThrows(DeveloperException.class, () -> client.fetchProfile(OAuthProvider.GITHUB, "c")).code());
    }

    @Test
    void aProviderThatCannotBeReachedIsReportedAsUnavailable() {
        http.down = true;
        for (OAuthProvider p : OAuthProvider.values()) {
            assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE,
                    assertThrows(DeveloperException.class, () -> client.fetchProfile(p, "t")).code());
        }
    }

    @Test
    void theProviderAddressesCanBeOverridden() {
        DeveloperProperties.Provider custom = new DeveloperProperties.Provider("id", "sec", null,
                "https://example.test/debug?x=1", "https://example.test/me", null);
        OAuthClient c = new OAuthClient(new DeveloperProperties.OAuth(null, custom, null), http);
        http.on("https://example.test/debug", 200, facebookCheck("id", true, "7"))
            .on("https://example.test/me", 200, "{\"id\":\"7\",\"name\":\"N\"}");
        assertEquals("7", c.fetchProfile(OAuthProvider.FACEBOOK, "t").subject());
        assertTrue(http.calls.get(0).url().startsWith("https://example.test/debug?x=1&input_token=t"));
    }
}