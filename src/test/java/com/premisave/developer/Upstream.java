package com.premisave.developer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A throwaway HTTP server that records what it receives and answers with a canned reply. */
public final class Upstream implements AutoCloseable {

    public record Seen(String method, String uri, Map<String, String> headers, String body) {}

    public int status = 200;
    public String replyBody = "{\"success\":true,\"message\":\"ok\",\"data\":{}}";
    public Map<String, String> replyHeaders = new LinkedHashMap<>(Map.of("Content-Type", "application/json"));
    public long delayMillis;
    public final List<Seen> seen = new ArrayList<>();

    private final HttpServer server;

    public Upstream() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange ex) throws IOException {
        byte[] body = ex.getRequestBody().readAllBytes();
        Map<String, String> headers = new LinkedHashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v.get(0)));
        synchronized (seen) {
            seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().toString(), headers, new String(body, StandardCharsets.UTF_8)));
        }
        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        replyHeaders.forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        byte[] out = replyBody.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
        if (out.length > 0) ex.getResponseBody().write(out);
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
