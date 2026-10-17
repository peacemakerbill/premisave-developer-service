package com.premisave.developer.util;

/** An email address with most of the name part hidden, so a screen can say where a code went without giving it away. */
public final class MaskedEmail {

    private MaskedEmail() {}

    /** jane.doe@example.com becomes j*******@example.com. Anything that is not an address becomes ***. */
    public static String of(String email) {
        if (email == null) return "***";
        String e = email.trim();
        int at = e.indexOf('@');
        if (at < 1 || at == e.length() - 1) return "***";
        String name = e.substring(0, at);
        String stars = "*".repeat(Math.max(3, Math.min(name.length() - 1, 8)));
        return name.charAt(0) + stars + e.substring(at);
    }
}
