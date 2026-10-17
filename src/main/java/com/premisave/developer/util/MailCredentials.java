package com.premisave.developer.util;

import java.util.regex.Pattern;

/**
 * Tidies the mail login as people paste it. A .env file is read as plain properties, so quotes around a value stay in
 * the value, and Google shows an App Password in four groups of four with spaces between them. Both reach the mail
 * server as part of the password and the login is refused. This removes one pair of matching quotes round a value, and
 * the spaces of a Google App Password (16 lower case letters in groups of four). Any other password is left exactly as
 * it was written, spaces included.
 */
public final class MailCredentials {

    private static final String SPACE = "[\\s\\u00a0]";
    private static final Pattern GOOGLE_APP_PASSWORD = Pattern.compile("[a-z]{4}(" + SPACE + "+[a-z]{4}){3}");

    private MailCredentials() {}

    /** A mail user name: surrounding space and one pair of matching quotes removed. */
    public static String username(String raw) {
        return unquote(raw);
    }

    /** A mail password: as {@link #username}, and the spaces of a Google App Password removed. */
    public static String password(String raw) {
        String s = unquote(raw);
        if (s != null && GOOGLE_APP_PASSWORD.matcher(s).matches()) return s.replaceAll(SPACE + "+", "");
        return s;
    }

    private static String unquote(String raw) {
        if (raw == null) return null;
        String s = strip(raw);
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '\'' || first == '"') && first == last) s = strip(s.substring(1, s.length() - 1));
        }
        return s;
    }

    /** Like String.strip, and also treats a non breaking space as space, which is what a copy from a web page carries. */
    private static String strip(String s) {
        int from = 0;
        int to = s.length();
        while (from < to && isSpace(s.charAt(from))) from++;
        while (to > from && isSpace(s.charAt(to - 1))) to--;
        return s.substring(from, to);
    }

    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || c == ' ';
    }
}
