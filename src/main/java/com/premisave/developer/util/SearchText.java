package com.premisave.developer.util;

import com.premisave.developer.exception.DeveloperException;

/** Turns what a staff member typed into a search box into text that is safe to put in a database pattern. */
public final class SearchText {

    public static final int MAX_LENGTH = 100;

    private static final String SPECIAL = "\\^$.|?*+()[]{}";

    private SearchText() {}

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
