package com.premisave.developer.util;

import com.premisave.developer.exception.DeveloperException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * First, middle and last names. Letters from any alphabet are fine, so are spaces, hyphens, apostrophes and full
 * stops (Anne-Marie, O'Brien, van der Merwe, Jr.). Digits, symbols, markup and control characters are refused, which
 * also keeps names safe to show in emails and on the portal.
 */
public final class PersonNames {

    private static final Pattern VALID = Pattern.compile("^[\\p{L}\\p{M}][\\p{L}\\p{M} .'’-]*$");
    private static final Pattern INVALID_CHARS = Pattern.compile("[^\\p{L}\\p{M} .'’-]");

    private PersonNames() {}

    public record Parts(String first, String middle, String last) {}

    /** A name that must be given. */
    public static String required(String field, String value, int max) {
        String v = optional(field, value, max);
        if (v == null) throw DeveloperException.validation(field, "is required");
        return v;
    }

    /** A name that may be left out. Blank means none. */
    public static String optional(String field, String value, int max) {
        if (value == null) return null;
        String v = value.trim().replaceAll("\\s+", " ");
        if (v.isEmpty()) return null;
        if (v.length() > max) throw DeveloperException.validation(field, "must be at most " + max + " characters");
        if (!VALID.matcher(v).matches()) {
            throw DeveloperException.validation(field, "may only contain letters, spaces, hyphens, apostrophes and full stops");
        }
        return v;
    }

    /** The names joined for display. */
    public static String full(String first, String middle, String last) {
        List<String> parts = new ArrayList<>(3);
        for (String p : new String[] {first, middle, last}) {
            if (p != null && !p.isBlank()) parts.add(p.trim());
        }
        return String.join(" ", parts);
    }

    /**
     * Best effort split of a single display name from a sign in provider or an older account: the first word is the
     * first name, the last word the last name, anything between is the middle name. Characters that are not allowed
     * in names are dropped. A single word gives a first name only.
     */
    public static Parts split(String displayName) {
        String clean = displayName == null ? "" : INVALID_CHARS.matcher(displayName).replaceAll(" ").trim().replaceAll("\\s+", " ");
        if (clean.isEmpty()) return new Parts(null, null, null);
        String[] words = clean.split(" ");
        List<String> usable = new ArrayList<>();
        for (String w : words) {
            if (VALID.matcher(w).matches() && w.length() <= 60) usable.add(w);
        }
        if (usable.isEmpty()) return new Parts(null, null, null);
        if (usable.size() == 1) return new Parts(usable.get(0), null, null);
        String middle = usable.size() > 2 ? String.join(" ", usable.subList(1, usable.size() - 1)) : null;
        if (middle != null && middle.length() > 60) middle = middle.substring(0, 60).trim();
        return new Parts(usable.get(0), middle, usable.get(usable.size() - 1));
    }
}
