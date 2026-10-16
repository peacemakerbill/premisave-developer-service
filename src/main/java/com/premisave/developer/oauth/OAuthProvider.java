package com.premisave.developer.oauth;

import com.premisave.developer.common.DeveloperException;
import com.premisave.developer.enums.ErrorCode;

import java.util.Locale;

/** The sign in providers. The lower case name is what appears in URLs and in stored identities. */
public enum OAuthProvider {
    GOOGLE, FACEBOOK, GITHUB;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static OAuthProvider parse(String value) {
        if (value != null) {
            String v = value.trim().toUpperCase(Locale.ROOT);
            for (OAuthProvider p : values()) {
                if (p.name().equals(v)) return p;
            }
        }
        throw new DeveloperException(ErrorCode.OAUTH_NOT_CONFIGURED, "Unknown sign in provider");
    }
}
