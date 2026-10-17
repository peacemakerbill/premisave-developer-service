package com.premisave.developer;

import com.premisave.developer.config.DeveloperProperties;

import java.util.List;

public final class TestProps {

    private TestProps() {}

    public static final String JWT_SECRET = "0123456789abcdef0123456789abcdef-test";
    public static final String PEPPER = "test-pepper-0123456789";

    public static DeveloperProperties.Mail mail() {
        return new DeveloperProperties.Mail(null, "https://portal.example.com", null, null, 60, 5, true, false, true);
    }

    public static DeveloperProperties.Keys keys() {
        return new DeveloperProperties.Keys(PEPPER, null, null);
    }

    public static DeveloperProperties.Security fastSecurity() {
        return new DeveloperProperties.Security(3, 10, 10, 10_000);
    }

    public static DeveloperProperties with(String baseUrl, String signingSecret) {
        return new DeveloperProperties(
                new DeveloperProperties.Jwt(JWT_SECRET, null, null),
                keys(),
                new DeveloperProperties.Wallet(baseUrl, null, null, "internal-key-for-tests", signingSecret, 5),
                null, null, null, fastSecurity(), new DeveloperProperties.Internal("internal-admin-key-1234"),
                new DeveloperProperties.Cors(List.of()), null, null, null);
    }
}
