package com.premisave.developer.view;

import com.premisave.developer.model.Developer;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DeveloperViews {

    private DeveloperViews() {}

    public static Map<String, Object> developer(Developer d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("developerId", d.getDeveloperId());
        m.put("email", d.getEmail());
        m.put("username", d.getUsername());
        m.put("firstName", d.getFirstName());
        m.put("middleName", d.getMiddleName());
        m.put("lastName", d.getLastName());
        m.put("fullName", d.getFullName());
        m.put("country", d.getCountry());
        m.put("addressLine1", d.getAddressLine1());
        m.put("addressLine2", d.getAddressLine2());
        m.put("city", d.getCity());
        m.put("stateOrRegion", d.getStateOrRegion());
        m.put("postalCode", d.getPostalCode());
        m.put("timezone", d.getTimezone());
        m.put("phoneNumber", d.getPhoneNumber());
        m.put("companyName", d.getCompanyName());
        m.put("marketingOptIn", d.isMarketingOptIn());
        m.put("termsAcceptedAt", iso(d.getTermsAcceptedAt()));
        m.put("profileComplete", d.getCountry() != null && d.getFirstName() != null && d.getLastName() != null);
        m.put("emailVerified", d.isEmailVerified());
        m.put("hasPassword", d.getPasswordHash() != null);
        m.put("profilePictureUrl", d.getProfilePictureUrl());
        m.put("providers", d.getAuthProviders().stream().sorted().toList());
        m.put("status", d.getStatus().name());
        m.put("businessIds", d.businessIds());
        m.put("createdAt", iso(d.getCreatedAt()));
        m.put("lastLoginAt", iso(d.getLastLoginAt()));
        return m;
    }

    /** The short form staff see when searching for a developer, for example to pick the developer id of a new merchant. */
    public static Map<String, Object> summary(Developer d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("developerId", d.getDeveloperId());
        m.put("email", d.getEmail());
        m.put("username", d.getUsername());
        m.put("fullName", d.getFullName());
        m.put("companyName", d.getCompanyName());
        m.put("country", d.getCountry());
        m.put("status", d.getStatus().name());
        m.put("emailVerified", d.isEmailVerified());
        m.put("businessIds", d.businessIds());
        m.put("createdAt", iso(d.getCreatedAt()));
        m.put("lastLoginAt", iso(d.getLastLoginAt()));
        return m;
    }

    static String iso(Instant t) {
        return t == null ? null : t.toString();
    }
}
