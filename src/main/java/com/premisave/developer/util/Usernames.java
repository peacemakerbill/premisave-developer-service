package com.premisave.developer.util;

import com.premisave.developer.exception.DeveloperException;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Usernames. They are generated when an account is made and the owner may change them. Rules: 3 to 30 characters, lower
 * case ASCII letters and digits, starting with a letter, with single dots, hyphens or underscores between parts and not
 * at the end. ASCII only keeps look alike letters from other alphabets out. Names that sound official (admin, support,
 * premisave and so on) are reserved, also when separators are used to disguise them (ad.min).
 */
public final class Usernames {

    public static final int MIN = 3;
    public static final int MAX = 30;

    private static final Pattern FORMAT = Pattern.compile("^[a-z][a-z0-9]*([._-][a-z0-9]+)*$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Set<String> RESERVED = Set.of(
            "admin", "administrator", "root", "superuser", "sudo", "support", "help", "helpdesk", "security", "system",
            "staff", "moderator", "api", "www", "mail", "email", "postmaster", "webmaster", "info", "contact", "sales",
            "billing", "payments", "payment", "finance", "legal", "abuse", "noreply", "null", "undefined", "none",
            "anonymous", "test", "developer", "developers", "owner", "official", "team", "service", "status", "login",
            "logout", "signin", "signup", "register", "account", "accounts", "settings", "profile", "internal",
            "sandbox", "live", "wallet", "merchant", "merchants", "app", "apps", "me", "dev");
    private static final List<String> BRANDS = List.of("premisave", "safaricom", "mpesa");

    private Usernames() {}

    public static String normalise(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    /** Throws a validation error naming the problem. The argument should be normalised first. */
    public static void check(String username) {
        if (username.length() < MIN || username.length() > MAX) {
            throw DeveloperException.validation("username", "must be " + MIN + " to " + MAX + " characters");
        }
        if (!FORMAT.matcher(username).matches()) {
            throw DeveloperException.validation("username", "must start with a letter and use only lower case letters, "
                    + "digits, dots, hyphens and underscores, with no two in a row and none at the end");
        }
        if (isReserved(username)) {
            throw DeveloperException.validation("username", "is reserved, choose another");
        }
    }

    public static boolean isValid(String username) {
        return username.length() >= MIN && username.length() <= MAX && FORMAT.matcher(username).matches();
    }

    /** True for official sounding names and the brands this platform runs on. Separators do not hide them. */
    public static boolean isReserved(String username) {
        String compact = username.replaceAll("[._-]", "");
        if (RESERVED.contains(username) || RESERVED.contains(compact)) return true;
        for (String brand : BRANDS) {
            if (compact.contains(brand)) return true;
        }
        return false;
    }

    /**
     * The starting point for a generated username: first.last, from the names with accents removed. Falls back to the
     * part of the email before the @ when the names have no Latin letters, and to "dev" as a last resort.
     */
    public static String base(String first, String last, String email) {
        String f = fold(first);
        String l = fold(last);
        String base;
        if (!f.isEmpty() && !l.isEmpty()) base = f + "." + l;
        else if (!f.isEmpty()) base = f;
        else if (!l.isEmpty()) base = l;
        else base = fold(email == null || email.indexOf('@') < 0 ? email : email.substring(0, email.indexOf('@')));
        if (base.isEmpty()) base = "dev";
        if (!Character.isLetter(base.charAt(0))) base = "dev" + base;
        if (base.length() > 22) base = base.substring(0, 22);
        while (base.endsWith(".")) base = base.substring(0, base.length() - 1);
        if (base.length() < MIN) base = base + ".dev";
        return base;
    }

    /** Attempt 0 is the base itself, then the base with 4 random digits, then with 8. */
    public static String candidate(String base, int attempt) {
        if (attempt == 0) return base;
        if (attempt <= 20) return base + (1000 + RANDOM.nextInt(9000));
        return base + (10_000_000 + RANDOM.nextInt(90_000_000));
    }

    private static String fold(String s) {
        if (s == null) return "";
        String ascii = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
