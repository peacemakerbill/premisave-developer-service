package com.premisave.developer.view;

import com.premisave.developer.model.ApiKey;
import com.premisave.developer.service.ApiKeyService;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class KeyViews {

    private KeyViews() {}

    public static Map<String, Object> key(ApiKey k) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", k.getKey());
        m.put("appId", k.getAppId());
        m.put("environment", k.getEnvironment().headerValue());
        m.put("label", k.getLabel());
        m.put("status", k.getStatus().name());
        m.put("secretEndsWith", k.getSecretHint());
        m.put("expiresAt", iso(k.getExpiresAt()));
        m.put("createdAt", iso(k.getCreatedAt()));
        m.put("lastUsedAt", iso(k.getLastUsedAt()));
        m.put("revokedAt", iso(k.getRevokedAt()));
        return m;
    }

    /** The one time view: everything above plus the secret. */
    public static Map<String, Object> issued(ApiKeyService.Issued issued) {
        Map<String, Object> m = key(issued.key());
        m.put("secret", issued.secret());
        m.put("secretNotice", "Store this secret now. It cannot be shown again.");
        return m;
    }

    static String iso(Instant t) {
        return t == null ? null : t.toString();
    }
}
