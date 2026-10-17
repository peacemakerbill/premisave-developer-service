package com.premisave.developer.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;

/** HTTP through the JDK client: short timeouts, no redirects, and a cap on how much of a reply is read. */
public class JdkHttpTransport implements HttpTransport {

    private static final int MAX_BYTES = 262_144;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public Reply postForm(String url, Map<String, String> headers, Map<String, String> form) throws IOException {
        StringJoiner body = new StringJoiner("&");
        form.forEach((k, v) -> body.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        headers.forEach(b::header);
        return send(b.build());
    }

    @Override
    public Reply get(String url, Map<String, String> headers) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET();
        headers.forEach(b::header);
        return send(b.build());
    }

    protected Reply send(HttpRequest request) throws IOException {
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                byte[] bytes = in.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) throw new IOException("The provider's reply is too large");
                return new Reply(response.statusCode(), new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }
}
