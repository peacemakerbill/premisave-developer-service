package com.premisave.developer.client;

import com.premisave.developer.config.DeveloperProperties;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.exception.DeveloperException;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The raw HTTP link to the wallet service. Adds the internal API key and, when configured, the request signature.
 * Anything it cannot reach becomes UPSTREAM_UNAVAILABLE or UPSTREAM_TIMEOUT; whatever the wallet service answers,
 * including its errors, is returned as it is.
 */
@Component("developerWalletClient")
public class WalletClient {

    /** One answer from the wallet service. Header names are lower case. */
    public record Reply(int status, byte[] body, Map<String, String> headers) {
        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private final DeveloperProperties.Wallet config;
    private final HttpClient http;

    public WalletClient(DeveloperProperties props) {
        this.config = props.wallet();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(config.timeoutSeconds())).build();
    }

    public DeveloperProperties.Wallet config() {
        return config;
    }

    /**
     * @param baseUrl       which wallet deployment to call
     * @param pathWithQuery exactly as it should be sent, already encoded, starting with /
     * @param headers       extra headers (context headers, content type); the internal key and signature are added here
     */
    public Reply send(String baseUrl, String method, String pathWithQuery, Map<String, String> headers, byte[] body,
                      Duration timeout) {
        if (baseUrl == null || !config.configured()) {
            throw new DeveloperException(ErrorCode.UPSTREAM_UNAVAILABLE, "The wallet service is not configured");
        }
        byte[] payload = body == null ? new byte[0] : body;
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl + pathWithQuery))
                    .timeout(timeout == null ? Duration.ofSeconds(config.timeoutSeconds()) : timeout)
                    .header("X-API-Key", config.apiKey());
            for (Map.Entry<String, String> h : headers.entrySet()) {
                if (h.getValue() != null) req.header(h.getKey(), h.getValue());
            }
            if (config.signingSecret() != null) {
                String ts = String.valueOf(Instant.now().getEpochSecond());
                req.header("X-Timestamp", ts);
                req.header("X-Signature", RequestSigner.sign(config.signingSecret(), ts, method, pathWithQuery, payload));
            }
            req.method(method, payload.length == 0 ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(payload));
            HttpResponse<byte[]> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
            Map<String, String> out = new LinkedHashMap<>();
            resp.headers().map().forEach((k, v) -> {
                if (!v.isEmpty()) out.put(k.toLowerCase(java.util.Locale.ROOT), v.get(0));
            });
            return new Reply(resp.statusCode(), resp.body(), out);
        } catch (HttpTimeoutException e) {
            throw new DeveloperException(ErrorCode.UPSTREAM_TIMEOUT,
                    "The wallet service did not answer in time. If this request changes money, repeat it with the same Idempotency-Key.");
        } catch (ConnectException e) {
            throw new DeveloperException(ErrorCode.UPSTREAM_UNAVAILABLE, "The wallet service cannot be reached");
        } catch (IOException e) {
            throw new DeveloperException(ErrorCode.UPSTREAM_UNAVAILABLE, "The wallet service connection failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeveloperException(ErrorCode.UPSTREAM_UNAVAILABLE, "The call to the wallet service was interrupted");
        }
    }
}
