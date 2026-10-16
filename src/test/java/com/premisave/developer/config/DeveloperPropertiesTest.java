package com.premisave.developer.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeveloperPropertiesTest {

    @Test
    void secretsAreRequiredAndMustBeLongEnough() {
        assertThrows(IllegalStateException.class, () -> new DeveloperProperties.Jwt(null, null, null));
        assertThrows(IllegalStateException.class, () -> new DeveloperProperties.Jwt("short", null, null));
        assertThrows(IllegalStateException.class, () -> new DeveloperProperties.Keys(null, null, null));
        assertThrows(IllegalStateException.class, () -> new DeveloperProperties.Keys("short", null, null));
        assertEquals(60, new DeveloperProperties.Jwt("x".repeat(32), null, null).accessTtlMinutes());
        assertEquals(5, new DeveloperProperties.Keys("x".repeat(16), null, null).maxActivePerApp());
    }

    @Test
    void testNumbersDefaultToFiveAndAreNormalised() {
        DeveloperProperties.Apps defaults = new DeveloperProperties.Apps(null, null);
        assertEquals(5, defaults.testMerchantNumbers().size());
        assertEquals("PRM-TEST-0001", defaults.defaultTestNumber());
        DeveloperProperties.Apps custom = new DeveloperProperties.Apps(null, List.of(" prm-test-0100 ", "PRM-TEST-0100", "PRM-QA-7"));
        assertEquals(List.of("PRM-TEST-0100", "PRM-QA-7"), custom.testMerchantNumbers());
        assertTrue(custom.isTestNumber("prm-qa-7"));
        assertFalse(custom.isTestNumber("PRM-TEST-0001"));
    }

    @Test
    void aTestNumberCanNeverLookLikeARealOne() {
        assertThrows(IllegalStateException.class, () -> new DeveloperProperties.Apps(null, List.of("PRM-0000000001")));
        assertThrows(IllegalStateException.class, () -> new DeveloperProperties.Apps(null, List.of("NOPE")));
    }

    @Test
    void walletUrlsFallBackToTheBaseAndLoseTrailingSlashes() {
        DeveloperProperties.Wallet w = new DeveloperProperties.Wallet("http://wallet:8080/", null, "http://sbx:8080//", "key", " ", null);
        assertEquals("http://wallet:8080", w.baseUrl());
        assertEquals("http://wallet:8080", w.urlFor(true));
        assertEquals("http://sbx:8080", w.urlFor(false));
        assertNull(w.signingSecret());
        assertTrue(w.configured());
        assertFalse(new DeveloperProperties.Wallet(null, null, null, "key", null, null).configured());
        assertFalse(new DeveloperProperties.Wallet("http://x", null, null, " ", null, null).configured());
    }

    @Test
    void theInternalKeyMustBeLongEnoughToCount() {
        assertFalse(new DeveloperProperties.Internal(null).configured());
        assertFalse(new DeveloperProperties.Internal("short").configured());
        assertTrue(new DeveloperProperties.Internal("x".repeat(16)).configured());
    }

    @Test
    void mailDefaultsAreSafeAndTheLinkBaseLosesTrailingSlashes() {
        DeveloperProperties.Mail m = new DeveloperProperties.Mail(" ", " https://portal.example.com// ", null, 1, -1, 0, null, null);
        assertNull(m.from());
        assertEquals("https://portal.example.com", m.portalBaseUrl());
        assertEquals(24, m.verificationTtlHours());
        assertEquals(60, m.resetTtlMinutes());
        assertEquals(60, m.resendCooldownSeconds());
        assertEquals(5, m.maxPerHour());
        assertTrue(m.requireVerifiedForBusiness());
        assertFalse(m.logLinks());
        assertEquals("http://localhost:3000", new DeveloperProperties.Mail(null, null, null, null, null, null, null, null).portalBaseUrl());
        assertEquals(0, new DeveloperProperties.Mail(null, null, null, null, 0, null, null, null).resendCooldownSeconds());
    }

    @Test
    void aProviderIsOnOnlyWithBothAClientIdAndASecret() {
        DeveloperProperties.OAuth none = new DeveloperProperties.OAuth(null, null, null, null, null);
        assertFalse(none.google().enabled());
        assertFalse(none.facebook().enabled());
        assertFalse(none.github().enabled());
        DeveloperProperties.Provider idOnly = new DeveloperProperties.Provider("id", " ", null, null, null, null, null);
        DeveloperProperties.Provider both = new DeveloperProperties.Provider(" id ", " secret ", null, null, null, null, null);
        DeveloperProperties.OAuth some = new DeveloperProperties.OAuth(idOnly, both, null, null, null);
        assertFalse(some.google().enabled());
        assertTrue(some.facebook().enabled());
        assertEquals("id", some.facebook().clientId());
        assertEquals("secret", some.facebook().clientSecret());
    }

    @Test
    void providerAddressesDefaultToTheStandardOnesAndCanBePinned() {
        DeveloperProperties.OAuth defaults = new DeveloperProperties.OAuth(null, null, null, null, null);
        assertEquals("https://oauth2.googleapis.com/token", defaults.google().tokenUrl());
        assertEquals("https://api.github.com/user/emails", defaults.github().emailsUrl());
        assertEquals("openid email profile", defaults.google().scope());
        DeveloperProperties.Provider pinned = new DeveloperProperties.Provider("id", "s",
                null, "https://graph.facebook.com/v22.0/oauth/access_token", null, null, null);
        DeveloperProperties.OAuth custom = new DeveloperProperties.OAuth(null, pinned, null, null, null);
        assertEquals("https://graph.facebook.com/v22.0/oauth/access_token", custom.facebook().tokenUrl());
        assertEquals(DeveloperProperties.OAuth.FACEBOOK_DEFAULTS.authorizeUrl(), custom.facebook().authorizeUrl());
    }

    @Test
    void onlyListedReturnAddressesAreAllowedAndNoneMeansNone() {
        DeveloperProperties.OAuth open = new DeveloperProperties.OAuth(null, null, null, null, null);
        assertFalse(open.isAllowedRedirect("https://portal.example.com/cb"));
        assertEquals(10, open.stateTtlMinutes());
        DeveloperProperties.OAuth o = new DeveloperProperties.OAuth(null, null, null,
                List.of(" https://portal.example.com/cb ", "", "https://portal.example.com/cb"), 0);
        assertEquals(List.of("https://portal.example.com/cb"), o.allowedRedirectUris());
        assertTrue(o.isAllowedRedirect("https://portal.example.com/cb"));
        assertFalse(o.isAllowedRedirect("https://portal.example.com/cb/"));
        assertFalse(o.isAllowedRedirect("https://portal.example.com/CB"));
        assertFalse(o.isAllowedRedirect(null));
    }
}
