package com.premisave.developer.enums;

import java.util.Optional;

/**
 * The two environments. The URL segment is live or sandbox; the value sent to the wallet service in
 * X-App-Environment uses "production" for the live side, matching what the wallet service expects.
 */
public enum Environment {
    LIVE("live", "production"),
    SANDBOX("sandbox", "sandbox");

    private final String pathSegment;
    private final String headerValue;

    Environment(String pathSegment, String headerValue) {
        this.pathSegment = pathSegment;
        this.headerValue = headerValue;
    }

    public String pathSegment() { return pathSegment; }

    public String headerValue() { return headerValue; }

    public boolean livemode() { return this == LIVE; }

    public static Optional<Environment> fromPathSegment(String segment) {
        for (Environment e : values()) {
            if (e.pathSegment.equals(segment)) return Optional.of(e);
        }
        return Optional.empty();
    }

    /** Accepts live, production or sandbox in any case. */
    public static Optional<Environment> parse(String value) {
        if (value == null) return Optional.empty();
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        for (Environment e : values()) {
            if (e.pathSegment.equals(v) || e.headerValue.equals(v)) return Optional.of(e);
        }
        return Optional.empty();
    }
}
