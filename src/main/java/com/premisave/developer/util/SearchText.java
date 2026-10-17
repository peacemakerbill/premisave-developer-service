package com.premisave.developer.util;

import com.premisave.developer.exception.DeveloperException;

/** Turns what a staff member typed into a search box into text that is safe to put in a database pattern. */
public final class SearchText {

    public static final int MAX_LENGTH = 100;

    private static final String SPECIAL = "\\^$.|?*+()[]{}";

    private SearchText() {}

    /** Matches any value in a pattern built from the helpers below. */
    public static final String ANY = ".*";

    /** A pattern for the whole value to equal the text, or ANY when no text was given. */
    public static String exact(String raw) {
        String s = raw == null ? null : raw.trim();
        return s == null || s.isEmpty() ? ANY : "^" + escape(s) + "$";
    }

    /** A pattern for the text to appear anywhere in the value, or ANY when no text was given. */
    public static String contains(String raw) {
        String s = clean(raw);
        return s == null ? ANY : escape(s);
    }

    /** Trims the text and returns null when nothing is left. Throws when it is longer than MAX_LENGTH. */
    public static String clean(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (s.length() > MAX_LENGTH) throw DeveloperException.validation("q", "must be at most " + MAX_LENGTH + " characters");
        return s;
    }

    /** Puts a backslash before every character that means something in a pattern, so the text matches itself only. */
    public static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (char c : text.toCharArray()) {
            if (SPECIAL.indexOf(c) >= 0) out.append('\\');
            out.append(c);
        }
        return out.toString();
    }
}
