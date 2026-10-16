package com.premisave.developer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Settings for the developer service (developer.*). Secrets have no defaults: the service refuses to start
 * without a token secret and an API key pepper, so a deployment can never run on a built in secret.
 */
@ConfigurationProperties(prefix = "developer")
public record DeveloperProperties(
        Jwt jwt,
        Keys keys,
        Wallet wallet,
        Proxy proxy,
        Apps apps,
        Security security,
        Internal internal,
        Cors cors,
        Mail mail,
        OAuth oauth) {

    public DeveloperProperties {
        if (jwt == null) jwt = new Jwt(null, null, null);
        if (keys == null) keys = new Keys(null, null, null);
        if (wallet == null) wallet = new Wallet(null, null, null, null, null, null);
        if (proxy == null) proxy = new Proxy(null, null, null);
        if (apps == null) apps = new Apps(null, null);
        if (security == null) security = new Security(null, null, null, null);
        if (internal == null) internal = new Internal(null);
        if (cors == null) cors = new Cors(null);
        if (mail == null) mail = new Mail(null, null, null, null, null, null, null, null);
        if (oauth == null) oauth = new OAuth(null, null, null, null, null);
    }

    /** Sign in tokens. */
    public record Jwt(String secret, String issuer, Integer accessTtlMinutes) {
        public Jwt {
            if (secret == null || secret.length() < 32) {
                throw new IllegalStateException("developer.jwt.secret must be set and at least 32 characters long");
            }
            if (issuer == null || issuer.isBlank()) issuer = "premisave-developer";
            if (accessTtlMinutes == null || accessTtlMinutes < 1) accessTtlMinutes = 60;
        }
    }

    /**
     * API keys. The pepper is mixed into the stored hash of every secret, so a copy of the database alone cannot be
     * used to test guesses. Changing it invalidates every existing secret.
     */
    public record Keys(String pepper, Integer maxActivePerApp, Integer rotationGraceHours) {
        public Keys {
            if (pepper == null || pepper.length() < 16) {
                throw new IllegalStateException("developer.keys.pepper must be set and at least 16 characters long");
            }
            if (maxActivePerApp == null || maxActivePerApp < 1) maxActivePerApp = 5;
            if (rotationGraceHours == null || rotationGraceHours < 0) rotationGraceHours = 24;
        }
    }

    /**
     * The wallet service. baseUrl serves merchants, onboarding and lookups; liveUrl and sandboxUrl optionally send the
     * environment scoped API elsewhere, otherwise baseUrl serves both.
     */
    public record Wallet(String baseUrl, String liveUrl, String sandboxUrl, String apiKey, String signingSecret,
                         Integer timeoutSeconds) {
        public Wallet {
            baseUrl = trimSlash(baseUrl);
            liveUrl = trimSlash(liveUrl);
            sandboxUrl = trimSlash(sandboxUrl);
            if (signingSecret != null && signingSecret.isBlank()) signingSecret = null;
            if (timeoutSeconds == null || timeoutSeconds < 1) timeoutSeconds = 30;
        }

        public boolean configured() {
            return baseUrl != null && apiKey != null && !apiKey.isBlank();
        }

        public String urlFor(boolean live) {
            String specific = live ? liveUrl : sandboxUrl;
            return specific != null ? specific : baseUrl;
        }

        private static String trimSlash(String u) {
            if (u == null || u.isBlank()) return null;
            String t = u.trim();
            while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
            return t;
        }
    }

    /** The public API entry. */
    public record Proxy(Integer rateLimitPerMinute, Integer maxBodyBytes, Integer maxUploadBytes) {
        public Proxy {
            if (rateLimitPerMinute == null || rateLimitPerMinute < 1) rateLimitPerMinute = 600;
            if (maxBodyBytes == null || maxBodyBytes < 1024) maxBodyBytes = 1_048_576;
            if (maxUploadBytes == null || maxUploadBytes < maxBodyBytes) maxUploadBytes = 12 * 1_048_576;
        }
    }

    /**
     * Apps. testMerchantNumbers are the numbers sandbox apps may use. They are fixed entries from the configuration, not
     * counted, and can never look like a real number (PRM- followed by exactly ten digits).
     */
    public record Apps(Integer maxPerEnvironment, List<String> testMerchantNumbers) {

        public static final List<String> DEFAULT_TEST_NUMBERS = List.of(
                "PRM-TEST-0001", "PRM-TEST-0002", "PRM-TEST-0003", "PRM-TEST-0004", "PRM-TEST-0005");
        private static final Pattern TEST_FORMAT = Pattern.compile("^PRM-[A-Z0-9][A-Z0-9-]{2,24}$");
        private static final Pattern REAL_FORMAT = Pattern.compile("^PRM-\\d{10}$");

        public Apps {
            if (maxPerEnvironment == null || maxPerEnvironment < 1) maxPerEnvironment = 10;
            List<String> cleaned = new ArrayList<>();
            if (testMerchantNumbers != null) {
                for (String n : testMerchantNumbers) {
                    if (n == null || n.isBlank()) continue;
                    String v = n.trim().toUpperCase(Locale.ROOT);
                    if (!TEST_FORMAT.matcher(v).matches() || REAL_FORMAT.matcher(v).matches()) {
                        throw new IllegalStateException("developer.apps.test-merchant-numbers: '" + n
                                + "' must look like PRM-TEST-0001 and must not be PRM- followed by ten digits");
                    }
                    cleaned.add(v);
                }
            }
            if (cleaned.isEmpty()) cleaned.addAll(DEFAULT_TEST_NUMBERS);
            testMerchantNumbers = List.copyOf(new LinkedHashSet<>(cleaned));
        }

        public boolean isTestNumber(String candidate) {
            return candidate != null && testMerchantNumbers.contains(candidate.trim().toUpperCase(Locale.ROOT));
        }

        public String defaultTestNumber() {
            return testMerchantNumbers.get(0);
        }
    }

    /** Passwords and lockout. */
    public record Security(Integer maxFailedLogins, Integer lockMinutes, Integer passwordMinLength,
                           Integer pbkdf2Iterations) {
        public Security {
            if (maxFailedLogins == null || maxFailedLogins < 1) maxFailedLogins = 5;
            if (lockMinutes == null || lockMinutes < 1) lockMinutes = 15;
            if (passwordMinLength == null || passwordMinLength < 8) passwordMinLength = 10;
            if (pbkdf2Iterations == null || pbkdf2Iterations < 10_000) pbkdf2Iterations = 210_000;
        }
    }

    /** The key Premisave staff tooling and the wallet service use to call /internal/**. */
    public record Internal(String apiKey) {
        public boolean configured() {
            return apiKey != null && apiKey.length() >= 16;
        }
    }

    public record Cors(List<String> allowedOrigins) {
        public Cors {
            allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins.stream()
                    .filter(o -> o != null && !o.isBlank()).map(String::trim).toList();
        }
    }

    /**
     * Email for verification and password reset links. portalBaseUrl is where the developer portal lives: links are
     * built as portalBaseUrl plus /verify-email?token=... and /reset-password?token=.... With no mail server configured
     * (spring.mail.host) nothing can be sent; logLinks then writes the link to the log instead, for local work only.
     */
    public record Mail(String from, String portalBaseUrl, Integer verificationTtlHours, Integer resetTtlMinutes,
                       Integer resendCooldownSeconds, Integer maxPerHour, Boolean requireVerifiedForBusiness,
                       Boolean logLinks) {
        public Mail {
            if (from != null && from.isBlank()) from = null;
            if (portalBaseUrl == null || portalBaseUrl.isBlank()) portalBaseUrl = "http://localhost:3000";
            portalBaseUrl = portalBaseUrl.trim();
            while (portalBaseUrl.endsWith("/")) portalBaseUrl = portalBaseUrl.substring(0, portalBaseUrl.length() - 1);
            if (verificationTtlHours == null || verificationTtlHours < 1) verificationTtlHours = 24;
            if (resetTtlMinutes == null || resetTtlMinutes < 5) resetTtlMinutes = 60;
            if (resendCooldownSeconds == null || resendCooldownSeconds < 0) resendCooldownSeconds = 60;
            if (maxPerHour == null || maxPerHour < 1) maxPerHour = 5;
            if (requireVerifiedForBusiness == null) requireVerifiedForBusiness = Boolean.TRUE;
            if (logLinks == null) logLinks = Boolean.FALSE;
        }
    }

    /**
     * Sign in with Google, Facebook and GitHub. A provider is on when it has a client id and secret. Sign in is off
     * altogether until allowedRedirectUris lists the exact portal addresses the provider may send people back to.
     * The default addresses are the providers' standard endpoints; override them to pin a Facebook API version.
     */
    public record OAuth(Provider google, Provider facebook, Provider github, List<String> allowedRedirectUris,
                        Integer stateTtlMinutes) {

        public static final Provider GOOGLE_DEFAULTS = new Provider(null, null,
                "https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token",
                "https://openidconnect.googleapis.com/v1/userinfo", null, "openid email profile");
        public static final Provider FACEBOOK_DEFAULTS = new Provider(null, null,
                "https://www.facebook.com/v21.0/dialog/oauth", "https://graph.facebook.com/v21.0/oauth/access_token",
                "https://graph.facebook.com/v21.0/me?fields=id,name,email", null, "email,public_profile");
        public static final Provider GITHUB_DEFAULTS = new Provider(null, null,
                "https://github.com/login/oauth/authorize", "https://github.com/login/oauth/access_token",
                "https://api.github.com/user", "https://api.github.com/user/emails", "read:user user:email");

        public OAuth {
            google = Provider.resolve(google, GOOGLE_DEFAULTS);
            facebook = Provider.resolve(facebook, FACEBOOK_DEFAULTS);
            github = Provider.resolve(github, GITHUB_DEFAULTS);
            allowedRedirectUris = allowedRedirectUris == null ? List.of() : allowedRedirectUris.stream()
                    .filter(u -> u != null && !u.isBlank()).map(String::trim).distinct().toList();
            if (stateTtlMinutes == null || stateTtlMinutes < 1) stateTtlMinutes = 10;
        }

        public boolean isAllowedRedirect(String uri) {
            return uri != null && allowedRedirectUris.contains(uri.trim());
        }
    }

    /** One sign in provider. userInfoUrl is the profile address; emailsUrl is GitHub's list of email addresses. */
    public record Provider(String clientId, String clientSecret, String authorizeUrl, String tokenUrl,
                           String userInfoUrl, String emailsUrl, String scope) {

        static Provider resolve(Provider given, Provider defaults) {
            if (given == null) return defaults;
            return new Provider(blankToNull(given.clientId), blankToNull(given.clientSecret),
                    or(given.authorizeUrl, defaults.authorizeUrl), or(given.tokenUrl, defaults.tokenUrl),
                    or(given.userInfoUrl, defaults.userInfoUrl), or(given.emailsUrl, defaults.emailsUrl),
                    or(given.scope, defaults.scope));
        }

        public boolean enabled() {
            return clientId != null && clientSecret != null;
        }

        private static String or(String v, String d) {
            return v == null || v.isBlank() ? d : v.trim();
        }

        private static String blankToNull(String v) {
            return v == null || v.isBlank() ? null : v.trim();
        }
    }
}
