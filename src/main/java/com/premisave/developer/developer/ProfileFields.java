package com.premisave.developer.developer;

import com.premisave.developer.common.DeveloperException;

import java.time.ZoneId;
import java.util.Locale;
import java.util.regex.Pattern;

/** Validation of the profile fields that are not names or usernames. Every method returns the tidied value, or null for blank. */
public final class ProfileFields {

    private static final Pattern POSTAL = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9 -]{1,11}$");

    private ProfileFields() {}

    /** A 2 letter country code, upper case. Required. */
    public static String country(String value) {
        String c = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!c.matches("[A-Z]{2}")) throw DeveloperException.validation("country", "must be a 2 letter country code");
        return c;
    }

    /** Digits with an optional leading plus, 7 to 15 of them, after spaces, dots, dashes and brackets are removed. */
    public static String phone(String value) {
        if (value == null || value.isBlank()) return null;
        String p = value.replaceAll("[\\s.\\-()]", "");
        if (!p.matches("\\+?[0-9]{7,15}")) {
            throw DeveloperException.validation("phoneNumber", "must be 7 to 15 digits, optionally starting with +");
        }
        return p;
    }

    /** Free text such as a company name or an address line. No markup characters and no control characters. */
    public static String text(String field, String value, int max) {
        if (value == null || value.isBlank()) return null;
        String t = value.trim().replaceAll("\\s+", " ");
        if (t.length() > max) throw DeveloperException.validation(field, "must be at most " + max + " characters");
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isISOControl(c) || c == '<' || c == '>') {
                throw DeveloperException.validation(field, "contains characters that are not allowed");
            }
        }
        return t;
    }

    public static String postalCode(String value) {
        if (value == null || value.isBlank()) return null;
        String p = value.trim().replaceAll("\\s+", " ");
        if (!POSTAL.matcher(p).matches()) {
            throw DeveloperException.validation("postalCode", "must be 2 to 12 letters, digits, spaces or hyphens");
        }
        return p.toUpperCase(Locale.ROOT);
    }

    /** An IANA time zone such as Africa/Nairobi. Offsets like +03:00 are refused. */
    public static String timezone(String value) {
        if (value == null || value.isBlank()) return null;
        String z = value.trim();
        if (!ZoneId.getAvailableZoneIds().contains(z) || !(z.contains("/") || z.equals("UTC"))) {
            throw DeveloperException.validation("timezone", "must be a time zone name such as Africa/Nairobi");
        }
        return z;
    }
}
