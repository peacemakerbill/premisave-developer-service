package com.premisave.developer.oauth;

import com.premisave.developer.common.DeveloperException;
import com.premisave.developer.common.JsonReader;
import com.premisave.developer.config.DeveloperProperties;
import com.premisave.developer.enums.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Talks to Google, Facebook and GitHub: builds the address people are sent to, trades the returned code for an access
 * token, and reads the profile. Written on the plain authorization code flow so it needs no provider library. The access
 * token is used once to read the profile and is not kept. Nothing the provider says is trusted except what its own
 * API returns over TLS for this token.
 */
public class OAuthClient {

    private static final Logger log = LoggerFactory.getLogger(OAuthClient.class);

    private final DeveloperProperties.OAuth config;
    private final HttpTransport http;

    public OAuthClient(DeveloperProperties.OAuth config, HttpTransport http) {
        this.config = config;
        this.http = http;
    }

    public DeveloperProperties.Provider settings(OAuthProvider p) {
        return switch (p) {
            case GOOGLE -> config.google();
            case FACEBOOK -> config.facebook();
            case GITHUB -> config.github();
        };
    }

    /** On when the provider has credentials and at least one return address is allowed. */
    public boolean enabled(OAuthProvider p) {
        return settings(p).enabled() && !config.allowedRedirectUris().isEmpty();
    }

    public String authorizationUrl(OAuthProvider p, String redirectUri, String state) {
        DeveloperProperties.Provider s = settings(p);
        Map<String, String> q = new LinkedHashMap<>();
        q.put("client_id", s.clientId());
        q.put("redirect_uri", redirectUri);
        q.put("response_type", "code");
        q.put("scope", s.scope());
        q.put("state", state);
        if (p == OAuthProvider.GOOGLE) q.put("prompt", "select_account");
        if (p == OAuthProvider.GITHUB) q.put("allow_signup", "true");
        return s.authorizeUrl() + (s.authorizeUrl().contains("?") ? "&" : "?") + encode(q);
    }

    /** Trades the code for a token and reads who the person is. */
    public OAuthProfile fetchProfile(OAuthProvider p, String code, String redirectUri) {
        DeveloperProperties.Provider s = settings(p);
        try {
            String token = exchange(p, s, code, redirectUri);
            return switch (p) {
                case GOOGLE -> google(s, token);
                case FACEBOOK -> facebook(s, token);
                case GITHUB -> github(s, token);
            };
        } catch (IOException e) {
            log.warn("Sign in with {} failed to reach the provider: {}", p.key(), e.toString());
            throw new DeveloperException(ErrorCode.UPSTREAM_UNAVAILABLE, "Could not reach " + label(p) + ". Try again shortly.");
        }
    }

    private String exchange(OAuthProvider p, DeveloperProperties.Provider s, String code, String redirectUri) throws IOException {
        HttpTransport.Reply reply;
        if (p == OAuthProvider.FACEBOOK) {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("client_id", s.clientId());
            q.put("client_secret", s.clientSecret());
            q.put("redirect_uri", redirectUri);
            q.put("code", code);
            reply = http.get(s.tokenUrl() + (s.tokenUrl().contains("?") ? "&" : "?") + encode(q), Map.of("Accept", "application/json"));
        } else {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("client_id", s.clientId());
            form.put("client_secret", s.clientSecret());
            form.put("code", code);
            form.put("redirect_uri", redirectUri);
            if (p == OAuthProvider.GOOGLE) form.put("grant_type", "authorization_code");
            reply = http.postForm(s.tokenUrl(), Map.of("Accept", "application/json"), form);
        }
        Map<String, Object> body = object(p, reply);
        Object token = body.get("access_token");
        if (reply.status() / 100 != 2 || !(token instanceof String t) || t.isBlank()) {
            log.info("{} did not accept the sign in code (status {})", label(p), reply.status());
            throw new DeveloperException(ErrorCode.OAUTH_FAILED, label(p) + " did not accept the sign in. Start again.");
        }
        return t;
    }

    private OAuthProfile google(DeveloperProperties.Provider s, String token) throws IOException {
        Map<String, Object> u = object(OAuthProvider.GOOGLE, require(OAuthProvider.GOOGLE, http.get(s.userInfoUrl(), bearer(token))));
        String subject = text(u.get("sub"));
        boolean verified = Boolean.TRUE.equals(u.get("email_verified")) || "true".equalsIgnoreCase(text(u.get("email_verified")));
        return new OAuthProfile(OAuthProvider.GOOGLE, subject, clean(text(u.get("email"))), verified, text(u.get("name")));
    }

    private OAuthProfile facebook(DeveloperProperties.Provider s, String token) throws IOException {
        Map<String, Object> u = object(OAuthProvider.FACEBOOK, require(OAuthProvider.FACEBOOK, http.get(s.userInfoUrl(), bearer(token))));
        // Facebook does not promise that an address on a profile was ever confirmed, so it is never counted as verified.
        return new OAuthProfile(OAuthProvider.FACEBOOK, text(u.get("id")), clean(text(u.get("email"))), false, text(u.get("name")));
    }

    private OAuthProfile github(DeveloperProperties.Provider s, String token) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>(bearer(token));
        headers.put("Accept", "application/vnd.github+json");
        headers.put("User-Agent", "premisave-developer-service");
        Map<String, Object> u = object(OAuthProvider.GITHUB, require(OAuthProvider.GITHUB, http.get(s.userInfoUrl(), headers)));
        String subject = text(u.get("id"));
        String name = text(u.get("name"));
        if (name == null || name.isBlank()) name = text(u.get("login"));
        // The public profile address can be anything, so only an address GitHub lists as verified is used.
        String email = null;
        HttpTransport.Reply r = http.get(s.emailsUrl(), headers);
        if (r.status() / 100 == 2) {
            try {
                List<Object> list = JsonReader.readArray(r.body());
                String firstVerified = null;
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> m) || !Boolean.TRUE.equals(m.get("verified"))) continue;
                    String address = clean(text(m.get("email")));
                    if (address == null) continue;
                    if (Boolean.TRUE.equals(m.get("primary"))) {
                        email = address;
                        break;
                    }
                    if (firstVerified == null) firstVerified = address;
                }
                if (email == null) email = firstVerified;
            } catch (IllegalArgumentException e) {
                log.warn("GitHub returned email addresses this service could not read");
            }
        }
        return new OAuthProfile(OAuthProvider.GITHUB, subject, email, email != null, name);
    }

    private static Map<String, String> bearer(String token) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Accept", "application/json");
        return h;
    }

    private static HttpTransport.Reply require(OAuthProvider p, HttpTransport.Reply r) {
        if (r.status() / 100 != 2) {
            log.info("{} refused the profile request (status {})", label(p), r.status());
            throw new DeveloperException(ErrorCode.OAUTH_FAILED, label(p) + " did not accept the sign in. Start again.");
        }
        return r;
    }

    private static Map<String, Object> object(OAuthProvider p, HttpTransport.Reply r) {
        try {
            return JsonReader.readObject(r.body());
        } catch (IllegalArgumentException e) {
            log.info("{} answered something that is not a JSON object (status {})", label(p), r.status());
            throw new DeveloperException(ErrorCode.OAUTH_FAILED, label(p) + " did not accept the sign in. Start again.");
        }
    }

    private static String text(Object o) {
        if (o == null) return null;
        if (o instanceof Double d && d == Math.rint(d)) return String.valueOf(d.longValue());
        return String.valueOf(o);
    }

    private static String clean(String email) {
        if (email == null) return null;
        String e = email.trim();
        return e.isEmpty() ? null : e;
    }

    private static String encode(Map<String, String> q) {
        StringJoiner j = new StringJoiner("&");
        q.forEach((k, v) -> j.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return j.toString();
    }

    static String label(OAuthProvider p) {
        return switch (p) {
            case GOOGLE -> "Google";
            case FACEBOOK -> "Facebook";
            case GITHUB -> "GitHub";
        };
    }
}
