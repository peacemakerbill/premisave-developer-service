package com.premisave.developer.service;

import com.premisave.developer.client.WalletClient;
import com.premisave.developer.config.DeveloperProperties;
import com.premisave.developer.dto.ClientCredentials;
import com.premisave.developer.enums.DeveloperStatus;
import com.premisave.developer.enums.Environment;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.ApiKey;
import com.premisave.developer.model.DeveloperApp;
import com.premisave.developer.repository.DeveloperAppRepository;
import com.premisave.developer.repository.DeveloperRepository;
import com.premisave.developer.security.RateLimiter;
import com.premisave.developer.util.AppContextHeaders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * The public API entry. A request to /v1/{live|sandbox}/... is authenticated with an API key and secret, checked against
 * the key's own environment, rate limited, and forwarded to the wallet service's environment scoped API together with the
 * context headers that say which merchant and app is calling and what that app may do. The wallet service never sees
 * the key or secret. The portal pass-through for onboarding uses the same forwarding with the developer's merchant.
 */
@Service("developerProxyService")
public class ProxyService {

    private static final Logger log = LoggerFactory.getLogger(ProxyService.class);
    private static final String WALLET_BASE = "/internal/commercial/v1";
    private static final List<String> REQUEST_HEADERS = List.of("content-type", "accept", "accept-language", "idempotency-key");
    private static final List<String> RESPONSE_HEADERS = List.of("content-type", "content-disposition", "idempotent-replayed",
            "retry-after", "etag");

    private final ApiKeyService keys;
    private final DeveloperAppRepository apps;
    private final DeveloperRepository developers;
    private final WalletClient wallet;
    private final DeveloperProperties.Proxy config;
    private final RateLimiter limiter;
    private final LongSupplier clockMillis;

    @Autowired
    public ProxyService(ApiKeyService keys, DeveloperAppRepository apps, DeveloperRepository developers,
                        WalletClient wallet, DeveloperProperties props) {
        this(keys, apps, developers, wallet, props.proxy(), System::currentTimeMillis);
    }

    public ProxyService(ApiKeyService keys, DeveloperAppRepository apps, DeveloperRepository developers,
                        WalletClient wallet, DeveloperProperties.Proxy config, LongSupplier clockMillis) {
        this.keys = keys;
        this.apps = apps;
        this.developers = developers;
        this.wallet = wallet;
        this.config = config;
        this.limiter = new RateLimiter(config.rateLimitPerMinute());
        this.clockMillis = clockMillis;
    }

    public int maxBodyBytes() { return config.maxBodyBytes(); }

    public int maxUploadBytes() { return config.maxUploadBytes(); }

    /** Reads at most limit bytes, and refuses a body that is larger. */
    public static byte[] readBody(InputStream in, int limit) throws IOException {
        byte[] body = in.readNBytes(limit + 1);
        if (body.length > limit) {
            throw new DeveloperException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "The request body is larger than " + Math.max(1, limit / 1_048_576) + " MB");
        }
        return body;
    }

    /** requestUri looks like /v1/sandbox/payments/pay_123 */
    public ResponseEntity<byte[]> handlePublic(String method, String requestUri, String query,
                                               Function<String, String> header, byte[] body, String requestId) {
        long started = clockMillis.getAsLong();
        String afterV1 = requestUri.startsWith("/v1/") ? requestUri.substring(3) : requestUri;
        int slash = afterV1.indexOf('/', 1);
        String segment = slash < 0 ? afterV1.substring(1) : afterV1.substring(1, slash);
        Environment pathEnv = Environment.fromPathSegment(segment)
                .orElseThrow(() -> new DeveloperException(ErrorCode.NOT_FOUND, "Unknown route, use /v1/live/... or /v1/sandbox/..."));
        String rest = safeRest(slash < 0 ? "" : afterV1.substring(slash));

        ClientCredentials credentials = ClientCredentials.from(header.apply("Authorization"),
                header.apply("X-Client-Key"), header.apply("X-Client-Secret"));
        ApiKey key = keys.authenticate(credentials.key(), credentials.secret());
        if (key.getEnvironment() != pathEnv) {
            throw new DeveloperException(ErrorCode.ENVIRONMENT_MISMATCH,
                    "This is a " + key.getEnvironment().headerValue() + " key and cannot be used on /v1/" + pathEnv.pathSegment());
        }
        RateLimiter.Decision decision = limiter.tryAcquire(key.getKey(), started);
        if (!decision.allowed()) {
            throw new DeveloperException(ErrorCode.RATE_LIMITED, "Too many requests, slow down")
                    .with("retryAfterSeconds", decision.retryAfterSeconds());
        }
        DeveloperApp app = apps.findById(key.getAppId())
                .orElseThrow(() -> new DeveloperException(ErrorCode.INVALID_API_KEY, "The API key or secret is not valid"));
        developers.findById(app.getDeveloperId()).ifPresent(d -> {
            if (d.getStatus() == DeveloperStatus.DISABLED) {
                throw new DeveloperException(ErrorCode.ACCOUNT_DISABLED, "This account has been disabled");
            }
        });

        Map<String, String> headers = forwardedHeaders(header, requestId);
        headers.putAll(AppContextHeaders.of(app));
        String path = WALLET_BASE + "/" + pathEnv.pathSegment() + rest + (query == null ? "" : "?" + query);
        WalletClient.Reply reply = wallet.send(wallet.config().urlFor(pathEnv.livemode()), method, path, headers, body, null);

        log.info("api request={} key={} app={} {} /{}{} -> {} in {}ms", requestId, key.getKey(), app.getAppId(), method,
                pathEnv.pathSegment(), rest, reply.status(), clockMillis.getAsLong() - started);
        ResponseEntity.BodyBuilder out = ResponseEntity.status(reply.status());
        copyResponseHeaders(reply, out);
        out.header("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        return out.body(reply.body());
    }

    /** Forwards a call from the portal to the wallet service's application routes for the developer's own merchant. */
    public ResponseEntity<byte[]> forwardApplication(String method, String merchantId, String actorId, String rest,
                                                     String query, Function<String, String> header, byte[] body,
                                                     String requestId) {
        boolean root = rest == null || rest.isEmpty() || rest.equals("/");
        String safe = root ? "" : safeRest(rest);
        Map<String, String> headers = forwardedHeaders(header, requestId);
        headers.put("X-Actor-Id", actorId);
        String path = WALLET_BASE + "/merchants/" + merchantId + "/application" + safe
                + (query == null ? "" : "?" + query);
        Duration timeout = Duration.ofSeconds(Math.max(60, wallet.config().timeoutSeconds()));
        WalletClient.Reply reply = wallet.send(wallet.config().baseUrl(), method, path, headers, body, timeout);
        ResponseEntity.BodyBuilder out = ResponseEntity.status(reply.status());
        copyResponseHeaders(reply, out);
        return out.body(reply.body());
    }

    private static Map<String, String> forwardedHeaders(Function<String, String> header, String requestId) {
        Map<String, String> h = new LinkedHashMap<>();
        for (String name : REQUEST_HEADERS) {
            String v = header.apply(name);
            if (v != null && !v.isBlank()) h.put(name, v);
        }
        if (requestId != null) h.put("X-Request-Id", requestId);
        return h;
    }

    private static void copyResponseHeaders(WalletClient.Reply reply, ResponseEntity.BodyBuilder out) {
        for (String name : RESPONSE_HEADERS) {
            String v = reply.headers().get(name);
            if (v != null) out.header(name, v);
        }
    }

    /**
     * The part of the path after the environment. Anything that could climb out of the environment scoped API is refused:
     * dot segments, empty segments, encoded dots, slashes and backslashes, matrix parameters, control characters.
     * An empty remainder is not a route.
     */
    static String safeRest(String rest) {
        if (rest == null || rest.isEmpty() || rest.equals("/")) {
            throw new DeveloperException(ErrorCode.NOT_FOUND, "Unknown route");
        }
        if (rest.charAt(0) != '/' || rest.length() > 400) {
            throw new DeveloperException(ErrorCode.NOT_FOUND, "Unknown route");
        }
        String lower = rest.toLowerCase(Locale.ROOT);
        if (lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c") || lower.contains("%00")
                || lower.contains("\\") || lower.contains(";") || lower.contains("//")) {
            throw DeveloperException.validation("path", "contains characters that are not allowed");
        }
        for (char c : rest.toCharArray()) {
            if (c <= 0x20 || c == 0x7f) throw DeveloperException.validation("path", "contains characters that are not allowed");
        }
        for (String part : rest.substring(1).split("/", -1)) {
            if (part.equals(".") || part.equals("..")) {
                throw DeveloperException.validation("path", "contains characters that are not allowed");
            }
        }
        return rest;
    }
}
