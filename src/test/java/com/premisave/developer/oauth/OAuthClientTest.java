package com.premisave.developer.oauth;

import com.premisave.developer.common.DeveloperException;
import com.premisave.developer.config.DeveloperProperties;
import com.premisave.developer.enums.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OAuthClientTest {

    private static final String URI = "https://portal.example.com/auth/callback";

    private static DeveloperProperties.Provider creds(String id) {
        return new DeveloperProperties.Provider(id, id + "-secret", null, null, null, null, null);
    }

    static DeveloperProperties.OAuth config() {
        return new DeveloperProperties.OAuth(creds("gid"), creds("fid"), creds("hid"), List.of(URI), null);
    }

    private final FakeTransport http = new FakeTransport();
    private final OAuthClient client = new OAuthClient(config(), http);

    @Test
    void aProviderIsOnOnlyWithCredentialsAndAReturnAddress() {
        assertTrue(client.enabled(OAuthProvider.GOOGLE));
        DeveloperProperties.OAuth noAddress = new DeveloperProperties.OAuth(creds("g"), creds("f"), creds("h"), List.of(), null);
        assertFalse(new OAuthClient(noAddress, http).enabled(OAuthProvider.GOOGLE));
        DeveloperProperties.OAuth onlyGoogle = new DeveloperProperties.OAuth(creds("g"), null, null, List.of(URI), null);
        OAuthClient c = new OAuthClient(onlyGoogle, http);
        assertTrue(c.enabled(OAuthProvider.GOOGLE));
        assertFalse(c.enabled(OAuthProvider.FACEBOOK));
        assertFalse(c.enabled(OAuthProvider.GITHUB));
    }

    @Test
    void theAuthorizationAddressCarriesTheClientReturnAddressScopeAndState() {
        String g = client.authorizationUrl(OAuthProvider.GOOGLE, URI, "st ate");
        assertTrue(g.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"));
        assertTrue(g.contains("client_id=gid"));
        assertTrue(g.contains("redirect_uri=https%3A%2F%2Fportal.example.com%2Fauth%2Fcallback"));
        assertTrue(g.contains("response_type=code"));
        assertTrue(g.contains("scope=openid+email+profile"));
        assertTrue(g.contains("state=st+ate"));
        assertFalse(g.contains("secret"));

        String h = client.authorizationUrl(OAuthProvider.GITHUB, URI, "s");
        assertTrue(h.startsWith("https://github.com/login/oauth/authorize?"));
        assertTrue(h.contains("scope=read%3Auser+user%3Aemail"));

        String f = client.authorizationUrl(OAuthProvider.FACEBOOK, URI, "s");
        assertTrue(f.startsWith("https://www.facebook.com/"));
        assertTrue(f.contains("scope=email%2Cpublic_profile"));
    }

    // ---- Google

    @Test
    void googleGivesAVerifiedAddress() {
        http.on("https://oauth2.googleapis.com/token", 200, "{\"access_token\":\"tok\",\"token_type\":\"Bearer\"}")
            .on("https://openidconnect.googleapis.com/v1/userinfo", 200,
                "{\"sub\":\"1234567890\",\"name\":\"Jane Doe\",\"email\":\"Jane@Gmail.com\",\"email_verified\":true}");
        OAuthProfile p = client.fetchProfile(OAuthProvider.GOOGLE, "the-code", URI);
        assertEquals("1234567890", p.subject());
        assertEquals("Jane@Gmail.com", p.email());
        assertTrue(p.emailVerified());
        assertEquals("Jane Doe", p.name());

        FakeTransport.Call token = http.calls.get(0);
        assertEquals("POST", token.method());
        assertEquals("authorization_code", token.form().get("grant_type"));
        assertEquals("the-code", token.form().get("code"));
        assertEquals("gid-secret", token.form().get("client_secret"));
        assertEquals(URI, token.form().get("redirect_uri"));
        assertEquals("Bearer tok", http.last().headers().get("Authorization"));
    }

    @Test
    void googleSayingTheAddressIsNotVerifiedIsBelieved() {
        http.on("https://oauth2.googleapis.com/token", 200, "{\"access_token\":\"tok\"}")
            .on("https://openidconnect.googleapis.com/", 200, "{\"sub\":\"1\",\"email\":\"a@b.com\",\"email_verified\":false}");
        assertFalse(client.fetchProfile(OAuthProvider.GOOGLE, "c", URI).emailVerified());
    }

    // ---- GitHub

    @Test
    void githubUsesThePrimaryVerifiedAddressAndNeverThePublicProfileOne() {
        http.on("https://github.com/login/oauth/access_token", 200, "{\"access_token\":\"gho_x\",\"token_type\":\"bearer\"}")
            .on("https://api.github.com/user/emails", 200,
                "[{\"email\":\"old@x.com\",\"primary\":false,\"verified\":true},"
                + "{\"email\":\"unproven@x.com\",\"primary\":true,\"verified\":false},"
                + "{\"email\":\"main@x.com\",\"primary\":true,\"verified\":true}]")
            .on("https://api.github.com/user", 200, "{\"id\":583231,\"login\":\"octocat\",\"name\":null,\"email\":\"public@x.com\"}");
        OAuthProfile p = client.fetchProfile(OAuthProvider.GITHUB, "c", URI);
        assertEquals("583231", p.subject());
        assertEquals("main@x.com", p.email());
        assertTrue(p.emailVerified());
        assertEquals("octocat", p.name());
        assertEquals("application/json", http.calls.get(0).headers().get("Accept"));
        assertEquals("premisave-developer-service", http.calls.get(1).headers().get("User-Agent"));
    }

    @Test
    void githubFallsBackToAnyVerifiedAddressAndOtherwiseGivesNone() {
        http.on("https://github.com/login/oauth/access_token", 200, "{\"access_token\":\"t\"}")
            .on("https://api.github.com/user/emails", 200, "[{\"email\":\"one@x.com\",\"primary\":false,\"verified\":true}]")
            .on("https://api.github.com/user", 200, "{\"id\":1,\"login\":\"o\"}");
        assertEquals("one@x.com", client.fetchProfile(OAuthProvider.GITHUB, "c", URI).email());

        http.on("https://api.github.com/user/emails", 200, "[{\"email\":\"u@x.com\",\"primary\":true,\"verified\":false}]");
        OAuthProfile none = client.fetchProfile(OAuthProvider.GITHUB, "c", URI);
        assertNull(none.email());
        assertFalse(none.emailVerified());

        http.on("https://api.github.com/user/emails", 403, "{\"message\":\"no\"}");
        assertNull(client.fetchProfile(OAuthProvider.GITHUB, "c", URI).email());

        http.on("https://api.github.com/user/emails", 200, "not json");
        assertNull(client.fetchProfile(OAuthProvider.GITHUB, "c", URI).email());
    }

    @Test
    void githubAnswersARejectedCodeWithA200AndAnError() {
        http.on("https://github.com/login/oauth/access_token", 200, "{\"error\":\"bad_verification_code\"}");
        assertEquals(ErrorCode.OAUTH_FAILED,
                assertThrows(DeveloperException.class, () -> client.fetchProfile(OAuthProvider.GITHUB, "c", URI)).code());
    }

    // ---- Facebook

    @Test
    void facebookAddressIsNeverCountedAsVerified() {
        http.on("https://graph.facebook.com/v21.0/oauth/access_token", 200, "{\"access_token\":\"fbtok\",\"token_type\":\"bearer\"}")
            .on("https://graph.facebook.com/v21.0/me", 200, "{\"id\":\"99887766\",\"name\":\"Jane Doe\",\"email\":\"jane@fb.com\"}");
        OAuthProfile p = client.fetchProfile(OAuthProvider.FACEBOOK, "c", URI);
        assertEquals("99887766", p.subject());
        assertEquals("jane@fb.com", p.email());
        assertFalse(p.emailVerified());
        FakeTransport.Call token = http.calls.get(0);
        assertEquals("GET", token.method());
        assertTrue(token.url().contains("client_secret=fid-secret"));
        assertTrue(token.url().contains("code=c"));
        assertEquals("Bearer fbtok", http.last().headers().get("Authorization"));
    }

    @Test
    void facebookMayGiveNoAddressAtAll() {
        http.on("https://graph.facebook.com/v21.0/oauth/access_token", 200, "{\"access_token\":\"t\"}")
            .on("https://graph.facebook.com/v21.0/me", 200, "{\"id\":\"5\",\"name\":\"No Mail\"}");
        assertNull(client.fetchProfile(OAuthProvider.FACEBOOK, "c", URI).email());
    }

    // ---- failures

    @Test
    void aProviderThatRefusesTheCodeIsReportedWithoutItsOwnWords() {
        http.on("https://oauth2.googleapis.com/token", 400, "{\"error\":\"invalid_grant\",\"error_description\":\"secret detail\"}");
        DeveloperException e = assertThrows(DeveloperException.class, () -> client.fetchProfile(OAuthProvider.GOOGLE, "c", URI));
        assertEquals(ErrorCode.OAUTH_FAILED, e.code());
        assertFalse(e.getMessage().contains("secret detail"));
    }

    @Test
    void aReplyThatIsNotJsonIsRefused() {
        http.on("https://oauth2.googleapis.com/token", 200, "<html>oops</html>");
        assertEquals(ErrorCode.OAUTH_FAILED,
                assertThrows(DeveloperException.class, () -> client.fetchProfile(OAuthProvider.GOOGLE, "c", URI)).code());
    }

    @Test
    void aRefusedProfileRequestIsRefused() {
        http.on("https://oauth2.googleapis.com/token", 200, "{\"access_token\":\"t\"}")
            .on("https://openidconnect.googleapis.com/", 401, "{}");
        assertEquals(ErrorCode.OAUTH_FAILED,
                assertThrows(DeveloperException.class, () -> client.fetchProfile(OAuthProvider.GOOGLE, "c", URI)).code());
    }

    @Test
    void aProviderThatCannotBeReachedIsReportedAsUnavailable() {
        http.down = true;
        assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE,
                assertThrows(DeveloperException.class, () -> client.fetchProfile(OAuthProvider.GITHUB, "c", URI)).code());
    }

    @Test
    void theProviderAddressesCanBeOverridden() {
        DeveloperProperties.Provider custom = new DeveloperProperties.Provider("id", "sec",
                "https://example.test/dialog/oauth?x=1", "https://example.test/token", "https://example.test/me", null, "email");
        OAuthClient c = new OAuthClient(new DeveloperProperties.OAuth(null, custom, null, List.of(URI), null), http);
        String url = c.authorizationUrl(OAuthProvider.FACEBOOK, URI, "s");
        assertTrue(url.startsWith("https://example.test/dialog/oauth?x=1&client_id=id"));
    }
}
