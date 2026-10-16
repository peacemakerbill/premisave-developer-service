package com.premisave.developer.auth;

/** The wording of the emails. Plain HTML, no external images or scripts. */
public final class EmailTemplates {

    private EmailTemplates() {}

    public record Message(String subject, String html) {}

    public static Message verify(String name, String link, int validHours) {
        String body = "<p>Hello " + esc(name) + ",</p>"
                + "<p>Confirm your email address to finish setting up your Premisave developer account.</p>"
                + button(link, "Confirm email address")
                + "<p>This link works once and expires in " + validHours + " hours. If you did not create this account, "
                + "you can ignore this email.</p>";
        return new Message("Confirm your Premisave developer account", page(body));
    }

    public static Message reset(String name, String link, int validMinutes) {
        String body = "<p>Hello " + esc(name) + ",</p>"
                + "<p>We received a request to set a new password for your Premisave developer account.</p>"
                + button(link, "Choose a new password")
                + "<p>This link works once and expires in " + validMinutes + " minutes. If you did not ask for it, "
                + "ignore this email: your password stays as it is.</p>";
        return new Message("Reset your Premisave developer password", page(body));
    }

    public static Message passwordChanged(String name) {
        String body = "<p>Hello " + esc(name) + ",</p>"
                + "<p>The password of your Premisave developer account was just changed, and other signed in "
                + "sessions were ended.</p>"
                + "<p>If this was not you, reset your password now and contact Premisave support.</p>";
        return new Message("Your Premisave developer password was changed", page(body));
    }

    private static String button(String link, String label) {
        String safe = esc(link);
        return "<p><a href=\"" + safe + "\" style=\"display:inline-block;padding:10px 18px;background:#0b5fff;"
                + "color:#ffffff;text-decoration:none;border-radius:6px\">" + esc(label) + "</a></p>"
                + "<p>Or copy this address into your browser:<br>" + safe + "</p>";
    }

    private static String page(String body) {
        return "<div style=\"font-family:Arial,Helvetica,sans-serif;font-size:15px;color:#1b1f24;max-width:560px\">"
                + body + "<p style=\"color:#6b7280;font-size:13px\">Premisave</p></div>";
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
