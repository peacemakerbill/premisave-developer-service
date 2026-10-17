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
        Businesses businesses,
        Security security,
        Internal internal,
        Cors cors,
        Mail mail,
        OAuth oauth,
        Cloudinary cloudinary) {

    public DeveloperProperties {
        if (jwt == null) jwt = new Jwt(null, null, null);
        if (keys == null) keys = new Keys(null, null, null);
        if (wallet == null) wallet = new Wallet(null, null, null, null, null, null);
        if (proxy == null) proxy = new Proxy(null, null, null);
        if (apps == null) apps = new Apps(null, null);
        if (businesses == null) businesses = new Businesses(null);
        if (security == null) security = new Security(null, null, null, null, null);
        if (internal == null) internal = new Internal(null);
        if (cors == null) cors = new Cors(null);
        if (mail == null) mail = new Mail(null, null, null, null, null, null, null, null, null);
        if (oauth == null) oauth = new OAuth(null, null, null);
        if (cloudinary == null) cloudinary = new Cloudinary(null, null, null, null, null);
    }

    /** Sign in tokens. */
    public record Jwt(String secret, String issuer, Integer accessTtlMinutes) {
        public Jwt {
            if (secret == null || secret.length() < 32) {
                throw new IllegalStateException("developer.jwt.secret must be set and at least 32 characters long. Set DEVELOPER_JWT_SECRET in the .env file in the folder you start the service from, or as an environment variable");
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
                throw new IllegalStateException("developer.keys.pepper must be set and at least 16 characters long. Set API_KEY_PEPPER in the .env file in the folder you start the service from, or as an environment variable");
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
            // An environment variable that is not set leaves the text ${NAME} behind; that is not a key.
            if (apiKey != null && (apiKey.isBlank() || apiKey.trim().startsWith("${"))) apiKey = null;
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

    /** Businesses an account can apply for. Each is its own merchant with its own onboarding application. */
    public record Businesses(Integer maxPerDeveloper) {
        public Businesses {
            if (maxPerDeveloper == null || maxPerDeveloper < 1) maxPerDeveloper = 10;
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

    /** Passwords and lockout, and the addresses that become administrators when they sign in. */
    public record Security(Integer maxFailedLogins, Integer lockMinutes, Integer passwordMinLength,
                           Integer pbkdf2Iterations, List<String> adminEmails) {
        public Security {
            adminEmails = adminEmails == null ? List.of() : adminEmails.stream()
                    .filter(e -> e != null && !e.isBlank()).map(e -> e.trim().toLowerCase(java.util.Locale.ROOT)).distinct().toList();
            if (maxFailedLogins == null || maxFailedLogins < 1) maxFailedLogins = 5;
            if (lockMinutes == null || lockMinutes < 1) lockMinutes = 15;
            if (passwordMinLength == null || passwordMinLength < 8) passwordMinLength = 10;
            if (pbkdf2Iterations == null || pbkdf2Iterations < 10_000) pbkdf2Iterations = 210_000;
        }

        /** True when the address is on the list of accounts that become administrators at sign in. */
        public boolean isAdminEmail(String email) {
            return email != null && adminEmails.contains(email.trim().toLowerCase(java.util.Locale.ROOT));
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
     * notifySignIn sends the owner a notice for every successful sign in, with the time, device and network address.
     */
    public record Mail(String from, String portalBaseUrl, Integer verificationTtlHours, Integer resetTtlMinutes,
                       Integer resendCooldownSeconds, Integer maxPerHour, Boolean requireVerifiedForBusiness,
                       Boolean logLinks, Boolean notifySignIn) {
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
            if (notifySignIn == null) notifySignIn = Boolean.TRUE;
        }
    }

    /**
     * Where profile pictures are stored. The picture is uploaded to Cloudinary under folder/developerId, signed with the
     * API secret, so the secret never leaves this service. With no cloud name, key or secret the picture endpoints answer
     * that pictures are not available and everything else works. maxBytes is the largest picture accepted (5 MB by
     * default, never more than 10 MB).
     */
    public record Cloudinary(String cloudName, String apiKey, String apiSecret, String folder, Integer maxBytes) {

        public static final int DEFAULT_MAX_BYTES = 5 * 1_048_576;
        public static final int LARGEST_MAX_BYTES = 10 * 1_048_576;

        public Cloudinary {
            cloudName = blankToNull(cloudName);
            apiKey = blankToNull(apiKey);
            apiSecret = blankToNull(apiSecret);
            String f = folder == null ? "" : folder.trim();
            while (f.startsWith("/")) f = f.substring(1);
            while (f.endsWith("/")) f = f.substring(0, f.length() - 1);
            folder = f.isEmpty() ? "premisave/developers" : f;
            if (maxBytes == null || maxBytes < 10_240) maxBytes = DEFAULT_MAX_BYTES;
            if (maxBytes > LARGEST_MAX_BYTES) maxBytes = LARGEST_MAX_BYTES;
        }

        /** True when all three credentials are present and the cloud name is safe to put in a web address. */
        public boolean configured() {
            return cloudName != null && cloudName.matches("[A-Za-z0-9_-]{1,64}") && apiKey != null && apiSecret != null;
        }

        /** Never prints the secret. */
        @Override
        public String toString() {
            return "Cloudinary[cloudName=" + cloudName + ", apiKey=" + (apiKey == null ? "unset" : "set")
                    + ", apiSecret=" + (apiSecret == null ? "unset" : "set") + ", folder=" + folder + ", maxBytes=" + maxBytes + "]";
        }

        private static String blankToNull(String v) {
            return v == null || v.isBlank() ? null : v.trim();
        }
    }

    /**
     * Sign in with Google, Facebook and GitHub. The browser gets the provider's token or code itself and posts it to this
     * service, which checks it with the provider. Google needs only the client id (its ID token is checked against it).
     * Facebook and GitHub also need the secret: Facebook to ask whether a token was issued to this app, GitHub to trade
     * its one time code for an access token. The default addresses are the providers' standard endpoints; override them
     * to pin a Facebook API version.
     */
    public record OAuth(Provider google, Provider facebook, Provider github) {

        public static final Provider GOOGLE_DEFAULTS = new Provider(null, null, null,
                "https://oauth2.googleapis.com/tokeninfo", null, null);
        public static final Provider FACEBOOK_DEFAULTS = new Provider(null, null, null,
                "https://graph.facebook.com/v21.0/debug_token", "https://graph.facebook.com/v21.0/me?fields=id,name,email", null);
        public static final Provider GITHUB_DEFAULTS = new Provider(null, null, "https://github.com/login/oauth/access_token",
                null, "https://api.github.com/user", "https://api.github.com/user/emails");

        public OAuth {
            google = Provider.resolve(google, GOOGLE_DEFAULTS);
            facebook = Provider.resolve(facebook, FACEBOOK_DEFAULTS);
            github = Provider.resolve(github, GITHUB_DEFAULTS);
        }
    }

    /**
     * One sign in provider. tokenUrl trades GitHub's code for an access token. verifyUrl is where Google's ID token
     * and Facebook's access token are checked. userInfoUrl is the profile address; emailsUrl is GitHub's list of
     * email addresses.
     */
    public record Provider(String clientId, String clientSecret, String tokenUrl, String verifyUrl,
                           String userInfoUrl, String emailsUrl) {

        static Provider resolve(Provider given, Provider defaults) {
            if (given == null) return defaults;
            return new Provider(blankToNull(given.clientId), blankToNull(given.clientSecret),
                    or(given.tokenUrl, defaults.tokenUrl), or(given.verifyUrl, defaults.verifyUrl),
                    or(given.userInfoUrl, defaults.userInfoUrl), or(given.emailsUrl, defaults.emailsUrl));
        }

        public boolean hasClientId() {
            return clientId != null;
        }

        /** Both a client id and a secret. Facebook and GitHub need both; Google needs only the client id. */
        public boolean enabled() {
            return clientId != null && clientSecret != null;
        }

        /** Never prints the secret. */
        @Override
        public String toString() {
            return "Provider[clientId=" + clientId + ", clientSecret=" + (clientSecret == null ? "unset" : "set") + "]";
        }

        private static String or(String v, String d) {
            return v == null || v.isBlank() ? d : v.trim();
        }

        private static String blankToNull(String v) {
            return v == null || v.isBlank() ? null : v.trim();
        }
    }
}
