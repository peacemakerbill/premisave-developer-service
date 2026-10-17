package com.premisave.developer.client;

import com.premisave.developer.config.DeveloperProperties;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.util.JsonReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Stores pictures in Cloudinary through its upload API, signed with the API secret (a SHA-1 of the sorted parameters
 * followed by the secret, which is what Cloudinary documents), so no Cloudinary library is needed. A picture is stored
 * under folder/developerId with overwrite on, so a new picture replaces the old one and nothing is left behind. The
 * stored picture is limited to 1024 by 1024 pixels. Failures are logged without the secret and reported as a temporary
 * problem.
 */
public class CloudinaryImageStorage implements ImageStorage {

    private static final Logger log = LoggerFactory.getLogger(CloudinaryImageStorage.class);

    static final String API = "https://api.cloudinary.com/v1_1/";
    static final String LIMIT = "c_limit,h_1024,w_1024";

    private final DeveloperProperties.Cloudinary config;
    private final MediaTransport http;
    private final Clock clock;

    public CloudinaryImageStorage(DeveloperProperties.Cloudinary config, MediaTransport http, Clock clock) {
        this.config = config;
        this.http = http;
        this.clock = clock;
    }

    @Override
    public boolean configured() {
        return config.configured();
    }

    @Override
    public String idFor(String developerId) {
        return config.folder() + "/" + developerId;
    }

    @Override
    public Stored upload(String developerId, byte[] image, String contentType) {
        requireConfigured();
        String publicId = idFor(developerId);
        Map<String, String> params = new TreeMap<>();
        params.put("invalidate", "true");
        params.put("overwrite", "true");
        params.put("public_id", publicId);
        params.put("timestamp", String.valueOf(clock.instant().getEpochSecond()));
        params.put("transformation", LIMIT);
        Map<String, String> fields = new LinkedHashMap<>(params);
        fields.put("api_key", config.apiKey());
        fields.put("signature", sign(params, config.apiSecret()));
        HttpTransport.Reply reply;
        try {
            reply = http.postMultipart(API + config.cloudName() + "/image/upload", fields, "file",
                    developerId + extension(contentType), contentType, image);
        } catch (IOException | RuntimeException e) {
            log.warn("Cloudinary upload failed for developer {}: {}", developerId, e.toString());
            throw unavailable();
        }
        if (reply.status() != 200) {
            log.warn("Cloudinary upload for developer {} answered {}: {}", developerId, reply.status(), snippet(reply.body()));
            throw unavailable();
        }
        try {
            Map<String, Object> json = JsonReader.readObject(reply.body());
            Object url = json.get("secure_url");
            if (!(url instanceof String s) || !s.startsWith("https://")) {
                throw new IllegalArgumentException("no secure_url");
            }
            Object id = json.get("public_id");
            return new Stored(s, id instanceof String text && !text.isBlank() ? text : publicId);
        } catch (RuntimeException e) {
            log.warn("Cloudinary upload for developer {} gave an answer that could not be read: {}", developerId, snippet(reply.body()));
            throw unavailable();
        }
    }

    @Override
    public void delete(String publicId) {
        requireConfigured();
        Map<String, String> params = new TreeMap<>();
        params.put("invalidate", "true");
        params.put("public_id", publicId);
        params.put("timestamp", String.valueOf(clock.instant().getEpochSecond()));
        Map<String, String> form = new LinkedHashMap<>(params);
        form.put("api_key", config.apiKey());
        form.put("signature", sign(params, config.apiSecret()));
        HttpTransport.Reply reply;
        try {
            reply = http.postForm(API + config.cloudName() + "/image/destroy", Map.of(), form);
        } catch (IOException | RuntimeException e) {
            log.warn("Cloudinary delete of {} failed: {}", publicId, e.toString());
            throw unavailable();
        }
        if (reply.status() != 200) {
            log.warn("Cloudinary delete of {} answered {}: {}", publicId, reply.status(), snippet(reply.body()));
            throw unavailable();
        }
    }

    /**
     * Cloudinary's signature: the parameters that are signed (not the file, api_key, cloud_name or resource_type),
     * sorted by name and written as name=value joined by &amp;, with the API secret added at the end, then SHA-1 in hex.
     */
    static String sign(Map<String, String> params, String secret) {
        StringBuilder toSign = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(params).entrySet()) {
            if (toSign.length() > 0) toSign.append('&');
            toSign.append(e.getKey()).append('=').append(e.getValue());
        }
        toSign.append(secret);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(toSign.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void requireConfigured() {
        if (!config.configured()) throw unavailable();
    }

    private static String extension(String contentType) {
        return switch (contentType == null ? "" : contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
    }

    private static String snippet(String body) {
        if (body == null) return "";
        String one = body.replaceAll("\\s+", " ");
        return one.length() > 300 ? one.substring(0, 300) : one;
    }

    private static DeveloperException unavailable() {
        return new DeveloperException(ErrorCode.UPSTREAM_UNAVAILABLE,
                "Profile pictures are not available right now. Try again later.");
    }
}
