package com.premisave.developer.developer;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DeveloperViews {

    private DeveloperViews() {}

    public static Map<String, Object> developer(Developer d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("developerId", d.getDeveloperId());
        m.put("email", d.getEmail());
        m.put("fullName", d.getFullName());
        m.put("country", d.getCountry());
        m.put("phoneNumber", d.getPhoneNumber());
        m.put("companyName", d.getCompanyName());
        m.put("marketingOptIn", d.isMarketingOptIn());
        m.put("termsAcceptedAt", iso(d.getTermsAcceptedAt()));
        m.put("profileComplete", d.getCountry() != null);
        m.put("emailVerified", d.isEmailVerified());
        m.put("hasPassword", d.getPasswordHash() != null);
        m.put("providers", d.getAuthProviders().stream().sorted().toList());
        m.put("status", d.getStatus().name());
        m.put("merchantId", d.getMerchantId());
        m.put("createdAt", iso(d.getCreatedAt()));
        m.put("lastLoginAt", iso(d.getLastLoginAt()));
        return m;
    }

    static String iso(Instant t) {
        return t == null ? null : t.toString();
    }
}
