package com.premisave.developer.service;

import com.premisave.developer.TestProps;
import com.premisave.developer.TestRepos;
import com.premisave.developer.Upstream;
import com.premisave.developer.client.RequestSigner;
import com.premisave.developer.client.WalletClient;
import com.premisave.developer.config.DeveloperProperties;
import com.premisave.developer.enums.AppStatus;
import com.premisave.developer.enums.DeveloperStatus;
import com.premisave.developer.enums.Environment;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.enums.MethodType;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.Developer;
import com.premisave.developer.model.DeveloperApp;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyServiceTest {

    private final TestRepos.Keys keyRepo = new TestRepos.Keys();
    private final TestRepos.Apps appRepo = new TestRepos.Apps();
    private final TestRepos.Developers devRepo = new TestRepos.Developers();
    private final ApiKeyService keys = new ApiKeyService(keyRepo.repo, TestProps.keys(), Clock.systemUTC());

    private DeveloperApp app(Environment env, String id) {
        DeveloperApp a = new DeveloperApp();
        a.setAppId(id);
        a.setDeveloperId("dev_1");
        a.setMerchantId("mer_1");
        a.setEnvironment(env);
        a.setStatus(AppStatus.ACTIVE);
        a.setVersion(1760601600000L);
        appRepo.store.put(id, a);
        return a;
    }

    private ApiKeyService.Issued key(DeveloperApp a) {
        return keys.issue(a, null);
    }

    private ProxyService proxy(String baseUrl, String signingSecret, int perMinute) {
        Developer d = new Developer();
        d.setDeveloperId("dev_1");
        devRepo.store.put("dev_1", d);
        DeveloperProperties props = TestProps.with(baseUrl, signingSecret);
        return new ProxyService(keys, appRepo.repo, devRepo.repo, new WalletClient(props),
                new DeveloperProperties.Proxy(perMinute, null, null), System::currentTimeMillis);
    }

    private static Function<String, String> headers(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i].toLowerCase(Locale.ROOT), kv[i + 1]);
        return n -> m.get(n.toLowerCase(Locale.ROOT));
    }

    private static String basic(ApiKeyService.Issued k) {
        return "Basic " + Base64.getEncoder().encodeToString((k.key().getKey() + ":" + k.secret()).getBytes(StandardCharsets.UTF_8));
    }

    private static DeveloperException fails(Runnable r) {
        return assertThrows(DeveloperException.class, r::run);
    }

    @Test
    void forwardsToTheEnvironmentScopedWalletApiWithContextAndWithoutTheCredentials() {
        try (Upstream up = new Upstream()) {
            DeveloperApp a = app(Environment.SANDBOX, "app_s");
            a.setEnabledMethods(EnumSet.of(MethodType.CARD));
            ApiKeyService.Issued k = key(a);
            up.status = 201;
            up.replyBody = "{\"success\":true,\"message\":\"Payment created\",\"data\":{\"id\":\"pay_1\"}}";
            up.replyHeaders.put("Idempotent-Replayed", "true");
            up.replyHeaders.put("Set-Cookie", "evil=1");

            ResponseEntity<byte[]> r = proxy(up.url(), null, 600).handlePublic("POST", "/v1/sandbox/payments", "expand=fees",
                    headers("Authorization", basic(k), "Content-Type", "application/json", "Idempotency-Key", "idem-1",
                            "Cookie", "session=1", "X-Merchant-Id", "mer_attacker", "X-App-Status", "ACTIVE"),
                    "{\"amount\":\"10.00\"}".getBytes(StandardCharsets.UTF_8), "req_abc");

            assertEquals(1, up.seen.size());
            Upstream.Seen s = up.seen.get(0);
            assertEquals("POST", s.method());
            assertEquals("/internal/commercial/v1/sandbox/payments?expand=fees", s.uri());
            assertEquals("{\"amount\":\"10.00\"}", s.body());
            assertEquals("internal-key-for-tests", s.headers().get("x-api-key"));
            assertEquals("mer_1", s.headers().get("x-merchant-id"));
            assertEquals("app_s", s.headers().get("x-app-id"));
            assertEquals("sandbox", s.headers().get("x-app-environment"));
            assertEquals("ACTIVE", s.headers().get("x-app-status"));
            assertEquals("CARD", s.headers().get("x-app-methods"));
            assertEquals("1760601600000", s.headers().get("x-app-version"));
            assertEquals("idem-1", s.headers().get("idempotency-key"));
            assertEquals("application/json", s.headers().get("content-type"));
            assertEquals("req_abc", s.headers().get("x-request-id"));
            assertNull(s.headers().get("authorization"));
            assertNull(s.headers().get("cookie"));
            assertFalse(s.headers().toString().contains(k.secret()));
            assertNull(s.headers().get("x-timestamp"));

            assertEquals(201, r.getStatusCode().value());
            assertTrue(new String(r.getBody(), StandardCharsets.UTF_8).contains("pay_1"));
        }
    }

    @Test
    void theClientKeyHeadersWorkToo() {
        try (Upstream up = new Upstream()) {
            ApiKeyService.Issued k = key(app(Environment.SANDBOX, "app_s"));
            proxy(up.url(), null, 600).handlePublic("GET", "/v1/sandbox/balance", null,
                    headers("X-Client-Key", k.key().getKey(), "X-Client-Secret", k.secret()), new byte[0], "req_1");
            assertEquals("/internal/commercial/v1/sandbox/balance", up.seen.get(0).uri());
            assertEquals("", up.seen.get(0).body());
        }
    }

    @Test
    void aLiveKeyOnTheLivePathReachesTheLiveEnvironment() {
        try (Upstream up = new Upstream()) {
            ApiKeyService.Issued k = key(app(Environment.LIVE, "app_l"));
            proxy(up.url(), null, 600).handlePublic("GET", "/v1/live/balance", null, headers("Authorization", basic(k)), new byte[0], "r");
            assertEquals("/internal/commercial/v1/live/balance", up.seen.get(0).uri());
            assertEquals("production", up.seen.get(0).headers().get("x-app-environment"));
        }
    }

    @Test
    void aKeyCannotCrossEnvironments() {
        try (Upstream up = new Upstream()) {
            ApiKeyService.Issued sandboxKey = key(app(Environment.SANDBOX, "app_s"));
            ApiKeyService.Issued liveKey = key(app(Environment.LIVE, "app_l"));
            ProxyService p = proxy(up.url(), null, 600);
            DeveloperException e1 = fails(() -> p.handlePublic("GET", "/v1/live/balance", null, headers("Authorization", basic(sandboxKey)), new byte[0], "r"));
            DeveloperException e2 = fails(() -> p.handlePublic("GET", "/v1/sandbox/balance", null, headers("Authorization", basic(liveKey)), new byte[0], "r"));
            assertEquals(ErrorCode.ENVIRONMENT_MISMATCH, e1.code());
            assertEquals(ErrorCode.ENVIRONMENT_MISMATCH, e2.code());
            assertTrue(up.seen.isEmpty());
        }
    }

    @Test
    void badOrMissingCredentialsNeverReachTheWalletService() {
        try (Upstream up = new Upstream()) {
            ApiKeyService.Issued k = key(app(Environment.SANDBOX, "app_s"));
            ProxyService p = proxy(up.url(), null, 600);
            String wrongSecret = "Basic " + Base64.getEncoder().encodeToString((k.key().getKey() + ":sk_sandbox_wrong").getBytes());
            assertEquals(ErrorCode.INVALID_API_KEY, fails(() -> p.handlePublic("GET", "/v1/sandbox/balance", null, headers("Authorization", wrongSecret), new byte[0], "r")).code());
            assertEquals(ErrorCode.UNAUTHENTICATED, fails(() -> p.handlePublic("GET", "/v1/sandbox/balance", null, headers(), new byte[0], "r")).code());
            assertTrue(up.seen.isEmpty());
        }
    }

    @Test
    void aRevokedKeyIsRefused() {
        try (Upstream up = new Upstream()) {
            DeveloperApp a = app(Environment.SANDBOX, "app_s");
            ApiKeyService.Issued k = key(a);
            keys.revoke(a, k.key().getKey());
            assertEquals(ErrorCode.INVALID_API_KEY, fails(() -> proxy(up.url(), null, 600)
                    .handlePublic("GET", "/v1/sandbox/balance", null, headers("Authorization", basic(k)), new byte[0], "r")).code());
        }
    }

    @Test
    void unknownEnvironmentsAndEmptyRoutesAreNotFound() {
        try (Upstream up = new Upstream()) {
            ApiKeyService.Issued k = key(app(Environment.SANDBOX, "app_s"));
            ProxyService p = proxy(up.url(), null, 600);
            for (String uri : new String[] {"/v1/staging/payments", "/v1/sandbox", "/v1/sandbox/", "/v1", "/v1/"}) {
                assertEquals(ErrorCode.NOT_FOUND, fails(() -> p.handlePublic("GET", uri, null, headers("Authorization", basic(k)), new byte[0], "r")).code(), uri);
            }
            assertTrue(up.seen.isEmpty());
        }
    }

    @Test
    void pathsThatCouldEscapeTheEnvironmentAreRefused() {
        try (Upstream up = new Upstream()) {
            ApiKeyService.Issued k = key(app(Environment.SANDBOX, "app_s"));
            ProxyService p = proxy(up.url(), null, 600);
            for (String uri : new String[] {"/v1/sandbox/../merchants/mer_1", "/v1/sandbox/./payments", "/v1/sandbox/%2e%2e/merchants",
                    "/v1/sandbox/%2E%2E/merchants", "/v1/sandbox/payments%2f..%2fmerchants", "/v1/sandbox//merchants", "/v1/sandbox/a\\b",
                    "/v1/sandbox/payments;jsessionid=1", "/v1/sandbox/pay ments", "/v1/sandbox/a%00b"}) {
                DeveloperException e = fails(() -> p.handlePublic("GET", uri, null, headers("Authorization", basic(k)), new byte[0], "r"));
                assertTrue(e.code() == ErrorCode.VALIDATION_FAILED || e.code() == ErrorCode.NOT_FOUND, uri);
            }
            assertTrue(up.seen.isEmpty());
        }
    }

    @Test
    void theSafePathRuleAcceptsNormalRoutes() {
        for (String ok : new String[] {"/payments", "/payments/pay_123/refunds", "/payment-links/abc-1", "/payments/", "/reports/transactions.csv"}) {
            assertEquals(ok, ProxyService.safeRest(ok));
        }
    }

    @Test
    void tooManyRequestsInAMinuteAreRefusedWithRetryAfter() {
        try (Upstream up = new Upstream()) {
            ApiKeyService.Issued k = key(app(Environment.SANDBOX, "app_s"));
            ProxyService p = proxy(up.url(), null, 2);
            p.handlePublic("GET", "/v1/sandbox/balance", null, headers("Authorization", basic(k)), new byte[0], "r");
            p.handlePublic("GET", "/v1/sandbox/balance", null, headers("Authorization", basic(k)), new byte[0], "r");
            DeveloperException e = fails(() -> p.handlePublic("GET", "/v1/sandbox/balance", null, headers("Authorization", basic(k)), new byte[0], "r"));
            assertEquals(ErrorCode.RATE_LIMITED, e.code());
            assertTrue(((Integer) e.extra().get("retryAfterSeconds")) >= 1);
            assertEquals(2, up.seen.size());
        }
    }

    @Test
    void walletErrorsPassThroughUntouched() {
        try (Upstream up = new Upstream()) {
            ApiKeyService.Issued k = key(app(Environment.SANDBOX, "app_s"));
            up.status = 422;
            up.replyBody = "{\"success\":false,\"message\":\"Amount exceeds the singleTransactionUsd limit\",\"data\":{\"code\":\"LIMIT_EXCEEDED\"}}";
            ResponseEntity<byte[]> r = proxy(up.url(), null, 600).handlePublic("POST", "/v1/sandbox/payments", null,
                    headers("Authorization", basic(k)), "{}".getBytes(), "r");
            assertEquals(422, r.getStatusCode().value());
            assertEquals(up.replyBody, new String(r.getBody(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void aSuspendedAppStillReachesTheWalletServiceWhichDecidesWhatItMayDo() {
        try (Upstream up = new Upstream()) {
            DeveloperApp a = app(Environment.SANDBOX, "app_s");
            a.setStatus(AppStatus.SUSPENDED);
            ApiKeyService.Issued k = key(a);
            proxy(up.url(), null, 600).handlePublic("GET", "/v1/sandbox/balance", null, headers("Authorization", basic(k)), new byte[0], "r");
            assertEquals("SUSPENDED", up.seen.get(0).headers().get("x-app-status"));
        }
    }

    @Test
    void aDisabledDeveloperIsStoppedAtTheDoor() {
        try (Upstream up = new Upstream()) {
            ApiKeyService.Issued k = key(app(Environment.SANDBOX, "app_s"));
            ProxyService p = proxy(up.url(), null, 600);
            devRepo.store.get("dev_1").setStatus(DeveloperStatus.DISABLED);
            assertEquals(ErrorCode.ACCOUNT_DISABLED, fails(() -> p.handlePublic("GET", "/v1/sandbox/balance", null, headers("Authorization", basic(k)), new byte[0], "r")).code());
            assertTrue(up.seen.isEmpty());
        }
    }

    @Test
    void whenSigningIsOnTheForwardedCallCarriesAValidSignature() {
        try (Upstream up = new Upstream()) {
            ApiKeyService.Issued k = key(app(Environment.SANDBOX, "app_s"));
            byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
            proxy(up.url(), "shared-signing-secret", 600).handlePublic("POST", "/v1/sandbox/payments", "x=1",
                    headers("Authorization", basic(k), "Content-Type", "application/json"), body, "r");
            Upstream.Seen s = up.seen.get(0);
            String ts = s.headers().get("x-timestamp");
            assertEquals(RequestSigner.sign("shared-signing-secret", ts, "POST", "/internal/commercial/v1/sandbox/payments?x=1", body),
                    s.headers().get("x-signature"));
        }
    }

    @Test
    void anUnreachableWalletServiceIsReportedAsUnavailable() {
        ApiKeyService.Issued k = key(app(Environment.SANDBOX, "app_s"));
        String closed;
        try (Upstream up = new Upstream()) {
            closed = up.url();
        }
        DeveloperException e = fails(() -> proxy(closed, null, 600).handlePublic("GET", "/v1/sandbox/balance", null,
                headers("Authorization", basic(k)), new byte[0], "r"));
        assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE, e.code());
    }

    @Test
    void aSlowWalletServiceIsReportedAsATimeoutWithAdviceToRepeatWithTheSameKey() {
        try (Upstream up = new Upstream()) {
            up.delayMillis = 2500;
            ApiKeyService.Issued k = key(app(Environment.SANDBOX, "app_s"));
            DeveloperProperties props = new DeveloperProperties(
                    new DeveloperProperties.Jwt(TestProps.JWT_SECRET, null, null), TestProps.keys(),
                    new DeveloperProperties.Wallet(up.url(), null, null, "internal-key-for-tests", null, 1),
                    null, null, null, null, null, null, null, null, null);
            Developer d = new Developer();
            d.setDeveloperId("dev_1");
            devRepo.store.put("dev_1", d);
            ProxyService p = new ProxyService(keys, appRepo.repo, devRepo.repo, new WalletClient(props),
                    new DeveloperProperties.Proxy(null, null, null), System::currentTimeMillis);
            DeveloperException e = fails(() -> p.handlePublic("POST", "/v1/sandbox/payments", null,
                    headers("Authorization", basic(k)), "{}".getBytes(), "r"));
            assertEquals(ErrorCode.UPSTREAM_TIMEOUT, e.code());
            assertTrue(e.getMessage().contains("Idempotency-Key"));
        }
    }

    @Test
    void theApplicationPassThroughFixesTheMerchantAndNamesTheActor() {
        try (Upstream up = new Upstream()) {
            up.replyHeaders.put("Content-Disposition", "attachment; filename=\"doc.pdf\"");
            ProxyService p = proxy(up.url(), null, 600);
            p.forwardApplication("POST", "mer_1", "dev_1", "/documents", null,
                    headers("Content-Type", "application/json", "X-Actor-Id", "someone_else", "X-Merchant-Id", "mer_other"),
                    "{\"type\":\"X\"}".getBytes(), "req_9");
            Upstream.Seen s = up.seen.get(0);
            assertEquals("/internal/commercial/v1/merchants/mer_1/application/documents", s.uri());
            assertEquals("dev_1", s.headers().get("x-actor-id"));
            assertNull(s.headers().get("x-merchant-id"));
            assertEquals("internal-key-for-tests", s.headers().get("x-api-key"));

            ResponseEntity<byte[]> r = p.forwardApplication("GET", "mer_1", "dev_1", "/", null, headers(), new byte[0], "req_10");
            assertEquals("/internal/commercial/v1/merchants/mer_1/application", up.seen.get(1).uri());
            assertEquals(200, r.getStatusCode().value());

            p.forwardApplication("GET", "mer_1", "dev_1", "/timeline", "a=b", headers(), new byte[0], "req_11");
            assertEquals("/internal/commercial/v1/merchants/mer_1/application/timeline?a=b", up.seen.get(2).uri());
        }
    }

    @Test
    void theApplicationPassThroughRefusesPathTricks() {
        try (Upstream up = new Upstream()) {
            ProxyService p = proxy(up.url(), null, 600);
            for (String rest : new String[] {"/../../merchants/mer_2/application", "/%2e%2e/x", "//x"}) {
                assertEquals(ErrorCode.VALIDATION_FAILED,
                        fails(() -> p.forwardApplication("GET", "mer_1", "dev_1", rest, null, headers(), new byte[0], "r")).code(), rest);
            }
            assertTrue(up.seen.isEmpty());
        }
    }

    @Test
    void bodiesLargerThanTheLimitAreRefused() throws Exception {
        byte[] ok = ProxyService.readBody(new java.io.ByteArrayInputStream(new byte[10]), 10);
        assertEquals(10, ok.length);
        DeveloperException e = assertThrows(DeveloperException.class,
                () -> ProxyService.readBody(new java.io.ByteArrayInputStream(new byte[11]), 10));
        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, e.code());
    }
}
