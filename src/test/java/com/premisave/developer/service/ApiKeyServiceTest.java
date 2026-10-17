package com.premisave.developer.service;

import com.premisave.developer.TestProps;
import com.premisave.developer.TestRepos;
import com.premisave.developer.config.DeveloperProperties;
import com.premisave.developer.enums.Environment;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.enums.KeyStatus;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.DeveloperApp;
import com.premisave.developer.view.KeyViews;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-16T08:00:00Z");

    private final TestRepos.Keys repo = new TestRepos.Keys();
    private Instant now = T0;
    private final Clock clock = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };

    private ApiKeyService service(int maxActive, int graceHours) {
        return new ApiKeyService(repo.repo, new DeveloperProperties.Keys(TestProps.PEPPER, maxActive, graceHours), clock);
    }

    private static DeveloperApp app(Environment env) {
        DeveloperApp a = new DeveloperApp();
        a.setAppId("app_1");
        a.setDeveloperId("dev_1");
        a.setEnvironment(env);
        return a;
    }

    @Test
    void issuedKeyIsPrefixedByEnvironmentAndTheSecretIsNotStored() {
        ApiKeyService.Issued sandbox = service(5, 24).issue(app(Environment.SANDBOX), "Server");
        assertTrue(sandbox.key().getKey().startsWith("pk_sandbox_"));
        assertTrue(sandbox.secret().startsWith("sk_sandbox_"));
        assertEquals("Server", sandbox.key().getLabel());
        assertFalse(sandbox.key().getSecretHash().contains(sandbox.secret()));
        assertEquals(64, sandbox.key().getSecretHash().length());
        assertEquals(sandbox.secret().substring(sandbox.secret().length() - 4), sandbox.key().getSecretHint());
        assertTrue(service(5, 24).issue(app(Environment.LIVE), null).key().getKey().startsWith("pk_live_"));
    }

    @Test
    void everyKeyAndSecretIsDifferent() {
        ApiKeyService s = service(5, 24);
        ApiKeyService.Issued a = s.issue(app(Environment.SANDBOX), null);
        ApiKeyService.Issued b = s.issue(app(Environment.SANDBOX), null);
        assertNotEquals(a.key().getKey(), b.key().getKey());
        assertNotEquals(a.secret(), b.secret());
        assertNotEquals(a.key().getSecretHash(), b.key().getSecretHash());
        assertEquals("Default", a.key().getLabel());
    }

    @Test
    void theRightPairAuthenticatesAndOthersGetTheSameError() {
        ApiKeyService s = service(5, 24);
        ApiKeyService.Issued k = s.issue(app(Environment.SANDBOX), null);
        assertEquals(k.key().getKey(), s.authenticate(k.key().getKey(), k.secret()).getKey());

        for (String[] bad : new String[][] {
                {k.key().getKey(), "sk_sandbox_wrong"},
                {k.key().getKey(), ""},
                {k.key().getKey(), null},
                {"pk_sandbox_unknown", k.secret()},
                {null, k.secret()}}) {
            DeveloperException e = assertThrows(DeveloperException.class, () -> s.authenticate(bad[0], bad[1]));
            assertEquals(ErrorCode.INVALID_API_KEY, e.code());
            assertEquals("The API key or secret is not valid", e.getMessage());
        }
    }

    @Test
    void aSecretFromAnotherPepperDoesNotMatch() {
        ApiKeyService.Issued k = service(5, 24).issue(app(Environment.SANDBOX), null);
        ApiKeyService other = new ApiKeyService(repo.repo, new DeveloperProperties.Keys("a-completely-other-pepper", null, null), clock);
        assertThrows(DeveloperException.class, () -> other.authenticate(k.key().getKey(), k.secret()));
    }

    @Test
    void revokedKeysStopWorkingAtOnce() {
        ApiKeyService s = service(5, 24);
        ApiKeyService.Issued k = s.issue(app(Environment.SANDBOX), null);
        s.revoke(app(Environment.SANDBOX), k.key().getKey());
        assertEquals(KeyStatus.REVOKED, repo.store.get(k.key().getKey()).getStatus());
        assertNotNull(repo.store.get(k.key().getKey()).getRevokedAt());
        assertThrows(DeveloperException.class, () -> s.authenticate(k.key().getKey(), k.secret()));
        // revoking again changes nothing
        s.revoke(app(Environment.SANDBOX), k.key().getKey());
    }

    @Test
    void rotationKeepsTheOldKeyForTheGracePeriodOnly() {
        ApiKeyService s = service(5, 24);
        ApiKeyService.Issued old = s.issue(app(Environment.SANDBOX), "Server");
        ApiKeyService.Issued fresh = s.rotate(app(Environment.SANDBOX), old.key().getKey());

        assertEquals("Server", fresh.key().getLabel());
        assertEquals(KeyStatus.ROTATING, repo.store.get(old.key().getKey()).getStatus());
        assertEquals(T0.plus(Duration.ofHours(24)), repo.store.get(old.key().getKey()).getExpiresAt());
        assertNotNull(s.authenticate(old.key().getKey(), old.secret()));
        assertNotNull(s.authenticate(fresh.key().getKey(), fresh.secret()));

        now = T0.plus(Duration.ofHours(23));
        assertNotNull(s.authenticate(old.key().getKey(), old.secret()));
        now = T0.plus(Duration.ofHours(25));
        assertThrows(DeveloperException.class, () -> s.authenticate(old.key().getKey(), old.secret()));
        assertNotNull(s.authenticate(fresh.key().getKey(), fresh.secret()));
    }

    @Test
    void zeroGraceRevokesTheOldKeyImmediately() {
        ApiKeyService s = service(5, 0);
        ApiKeyService.Issued old = s.issue(app(Environment.SANDBOX), null);
        s.rotate(app(Environment.SANDBOX), old.key().getKey());
        assertEquals(KeyStatus.REVOKED, repo.store.get(old.key().getKey()).getStatus());
        assertThrows(DeveloperException.class, () -> s.authenticate(old.key().getKey(), old.secret()));
    }

    @Test
    void onlyAnActiveKeyCanBeRotated() {
        ApiKeyService s = service(5, 24);
        ApiKeyService.Issued k = s.issue(app(Environment.SANDBOX), null);
        s.revoke(app(Environment.SANDBOX), k.key().getKey());
        DeveloperException e = assertThrows(DeveloperException.class, () -> s.rotate(app(Environment.SANDBOX), k.key().getKey()));
        assertEquals(ErrorCode.INVALID_STATE, e.code());
    }

    @Test
    void anAppCannotHoldMoreActiveKeysThanTheLimit() {
        ApiKeyService s = service(2, 24);
        s.issue(app(Environment.SANDBOX), null);
        ApiKeyService.Issued second = s.issue(app(Environment.SANDBOX), null);
        DeveloperException e = assertThrows(DeveloperException.class, () -> s.issue(app(Environment.SANDBOX), null));
        assertEquals(ErrorCode.LIMIT_REACHED, e.code());
        s.revoke(app(Environment.SANDBOX), second.key().getKey());
        assertNotNull(s.issue(app(Environment.SANDBOX), null));
    }

    @Test
    void keysOfAnotherAppAreNotFound() {
        ApiKeyService s = service(5, 24);
        ApiKeyService.Issued k = s.issue(app(Environment.SANDBOX), null);
        DeveloperApp other = app(Environment.SANDBOX);
        other.setAppId("app_2");
        assertEquals(ErrorCode.KEY_NOT_FOUND, assertThrows(DeveloperException.class, () -> s.revoke(other, k.key().getKey())).code());
        assertEquals(ErrorCode.KEY_NOT_FOUND, assertThrows(DeveloperException.class, () -> s.rotate(other, k.key().getKey())).code());
    }

    @Test
    void lastUsedIsRecordedButNotOnEveryCall() {
        ApiKeyService s = service(5, 24);
        ApiKeyService.Issued k = s.issue(app(Environment.SANDBOX), null);
        assertNull(repo.store.get(k.key().getKey()).getLastUsedAt());
        s.authenticate(k.key().getKey(), k.secret());
        assertEquals(T0, repo.store.get(k.key().getKey()).getLastUsedAt());
        now = T0.plusSeconds(20);
        s.authenticate(k.key().getKey(), k.secret());
        assertEquals(T0, repo.store.get(k.key().getKey()).getLastUsedAt());
        now = T0.plusSeconds(90);
        s.authenticate(k.key().getKey(), k.secret());
        assertEquals(T0.plusSeconds(90), repo.store.get(k.key().getKey()).getLastUsedAt());
    }

    @Test
    void theViewNeverShowsTheSecretExceptOnIssue() {
        ApiKeyService s = service(5, 24);
        ApiKeyService.Issued k = s.issue(app(Environment.SANDBOX), null);
        assertFalse(KeyViews.key(k.key()).containsKey("secret"));
        assertFalse(KeyViews.key(k.key()).toString().contains(k.secret()));
        assertEquals(k.secret(), KeyViews.issued(k).get("secret"));
    }
}
