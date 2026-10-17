package com.premisave.developer.client;

import com.premisave.developer.config.DeveloperProperties;
import com.premisave.developer.dto.OAuthProfile;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.enums.OAuthProvider;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.util.JsonReader;

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
 * Talks to Google, Facebook and GitHub to find out who a token belongs to. The browser gets the token itself, so this
 * class only checks it:
 *
 * <ul>
 *   <li>Google: an ID token. Google's token info endpoint checks the signature and expiry, and this class checks that
 *       the token was issued to this service's client id by Google.</li>
 *   <li>Facebook: a user access token. Facebook's debug endpoint is asked, with the app's own credentials, whether the
 *       token is valid and was issued to this app, so a token from another app is refused.</li>
 *   <li>GitHub: a one time authorization code. It is traded for an access token with the client secret, which never
 *       leaves this service.</li>
 * </ul>
 *
 * The provider access token is used once to read the profile and is not kept. Nothing is trusted except what the
 * provider's own API returns over TLS.
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

    /** Google is on with a client id. Facebook and GitHub need a client id and a secret. */
    public boolean enabled(OAuthProvider p) {
        DeveloperProperties.Provider s = settings(p);
        return p == OAuthProvider.GOOGLE ? s.hasClientId() : s.enabled();
    }

    /** The public id the browser needs to start a login, or null when the provider is off. It is not a secret. */
    public String clientId(OAuthProvider p) {
        return enabled(p) ? settings(p).clientId() : null;
    }

    /**
     * Checks the token or code the browser sent and reads who the person is.
     * only when the browser started the GitHub login with one.
     */
    public OAuthProfile fetchProfile(OAuthProvider p, String token) {
        DeveloperProperties.Provider s = settings(p);
        try {
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

    private OAuthProfile google(DeveloperProperties.Provider s, String idToken) throws IOException {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("id_token", idToken);
        Map<String, Object> u = object(OAuthProvider.GOOGLE,
                require(OAuthProvider.GOOGLE, http.get(withQuery(s.verifyUrl(), q), Map.of("Accept", "application/json"))));
        if (!s.clientId().equals(text(u.get("aud")))) {
            log.info("A Google token was presented that was issued to another client");
            throw refused(OAuthProvider.GOOGLE);
        }
        String issuer = text(u.get("iss"));
        if (!"accounts.google.com".equals(issuer) && !"https://accounts.google.com".equals(issuer)) {
            log.info("A Google token was presented with an unexpected issuer");
            throw refused(OAuthProvider.GOOGLE);
        }
        boolean verified = Boolean.TRUE.equals(u.get("email_verified")) || "true".equalsIgnoreCase(text(u.get("email_verified")));
        return new OAuthProfile(OAuthProvider.GOOGLE, text(u.get("sub")), clean(text(u.get("email"))), verified, text(u.get("name")));
    }

    private OAuthProfile facebook(DeveloperProperties.Provider s, String accessToken) throws IOException {
        // Asks Facebook, as this app, who the token was issued to. Without it a token made for any other app would pass.
        Map<String, String> q = new LinkedHashMap<>();
        q.put("input_token", accessToken);
        q.put("access_token", s.clientId() + "|" + s.clientSecret());
        Map<String, Object> checked = object(OAuthProvider.FACEBOOK,
                require(OAuthProvider.FACEBOOK, http.get(withQuery(s.verifyUrl(), q), Map.of("Accept", "application/json"))));
        if (!(checked.get("data") instanceof Map<?, ?> data) || !Boolean.TRUE.equals(data.get("is_valid"))
                || !s.clientId().equals(text(data.get("app_id")))) {
            log.info("A Facebook token was presented that is not valid for this app");
            throw refused(OAuthProvider.FACEBOOK);
        }
        Map<String, Object> u = object(OAuthProvider.FACEBOOK,
                require(OAuthProvider.FACEBOOK, http.get(s.userInfoUrl(), bearer(accessToken))));
        String subject = text(u.get("id"));
        String owner = text(data.get("user_id"));
        if (owner != null && !owner.equals(subject)) {
            log.info("A Facebook token and its profile belong to different users");
            throw refused(OAuthProvider.FACEBOOK);
        }
        // Facebook does not promise that an address on a profile was ever confirmed, so it is never counted as verified.
        return new OAuthProfile(OAuthProvider.FACEBOOK, subject, clean(text(u.get("email"))), false, text(u.get("name")));
    }

    private OAuthProfile github(DeveloperProperties.Provider s, String code) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", s.clientId());
        form.put("client_secret", s.clientSecret());
        form.put("code", code);
        HttpTransport.Reply reply = http.postForm(s.tokenUrl(), Map.of("Accept", "application/json"), form);
        Map<String, Object> body = object(OAuthProvider.GITHUB, reply);
        Object issued = body.get("access_token");
        if (reply.status() / 100 != 2 || !(issued instanceof String accessToken) || accessToken.isBlank()) {
            log.info("GitHub did not accept the sign in code (status {})", reply.status());
            throw refused(OAuthProvider.GITHUB);
        }

        Map<String, String> headers = new LinkedHashMap<>(bearer(accessToken));
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

    private static DeveloperException refused(OAuthProvider p) {
        return new DeveloperException(ErrorCode.OAUTH_FAILED, label(p) + " did not accept the sign in. Start again.");
    }

    private static HttpTransport.Reply require(OAuthProvider p, HttpTransport.Reply r) {
        if (r.status() / 100 != 2) {
            log.info("{} refused the request (status {})", label(p), r.status());
            throw refused(p);
        }
        return r;
    }

    private static Map<String, Object> object(OAuthProvider p, HttpTransport.Reply r) {
        try {
            return JsonReader.readObject(r.body());
        } catch (IllegalArgumentException e) {
            log.info("{} answered something that is not a JSON object (status {})", label(p), r.status());
            throw refused(p);
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

    private static String withQuery(String url, Map<String, String> q) {
        StringJoiner j = new StringJoiner("&");
        q.forEach((k, v) -> j.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return url + (url.contains("?") ? "&" : "?") + j;
    }

    public static String label(OAuthProvider p) {
        return switch (p) {
            case GOOGLE -> "Google";
            case FACEBOOK -> "Facebook";
            case GITHUB -> "GitHub";
        };
    }
}
