package com.premisave.developer.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The emails. Each one is an HTML file under src/main/resources/email/ with {{name}} style placeholders, wrapped in
 * layout.html. Values are escaped when they are put in; only a placeholder whose name ends in Html is inserted as is,
 * and only this class fills those. Placeholders are replaced in one pass, so a value that itself contains {{x}} is
 * never expanded. The pages use tables and inline styles because mail programs ignore most other CSS, and they load
 * no images or scripts.
 */
public final class EmailTemplates {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.ENGLISH);
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private EmailTemplates() {}

    public record Message(String subject, String html) {}

    public static Message verify(String name, String link, int validHours, String portalUrl) {
        Map<String, String> v = base(name, portalUrl);
        v.put("link", link);
        v.put("validFor", validHours == 1 ? "1 hour" : validHours + " hours");
        String subject = "Confirm your Premisave developer account";
        return new Message(subject, page("confirm-email", v, subject, "Confirm your email address to finish setting up your account."));
    }

    public static Message welcome(String name, String portalUrl) {
        String subject = "Welcome to Premisave";
        return new Message(subject, page("welcome", base(name, portalUrl), subject, "Your account is ready. Here is how to get started."));
    }

    public static Message signIn(String name, String method, String device, String ip, Instant at, String portalUrl) {
        Map<String, String> v = base(name, portalUrl);
        v.put("method", method);
        v.put("device", device);
        v.put("ip", ip);
        v.put("time", at == null ? "" : WHEN.format(at.atOffset(ZoneOffset.UTC)));
        String subject = "New sign in to your Premisave developer account";
        return new Message(subject, page("sign-in", v, subject, "Someone signed in to your account. Check that it was you."));
    }

    public static Message reset(String name, String link, int validMinutes, String portalUrl) {
        Map<String, String> v = base(name, portalUrl);
        v.put("link", link);
        v.put("validFor", validMinutes == 1 ? "1 minute" : validMinutes + " minutes");
        String subject = "Reset your Premisave developer password";
        return new Message(subject, page("reset-password", v, subject, "Use this link to choose a new password."));
    }

    public static Message passwordChanged(String name, String portalUrl) {
        String subject = "Your Premisave developer password was changed";
        return new Message(subject, page("password-changed", base(name, portalUrl), subject, "The password of your account was changed."));
    }

    /**
     * The code that confirms moving an app or taking up a business. It goes to the address linked to the business, which may
     * belong to someone with no developer account, so the greeting is general when no name is known.
     */
    public static Message transferCode(String name, String code, String action, String business, String merchantNumber,
                                       String requestedBy, int validMinutes, String portalUrl) {
        Map<String, String> v = base(name, portalUrl);
        v.put("code", code);
        v.put("action", action);
        v.put("business", business);
        v.put("merchantNumber", merchantNumber);
        v.put("requestedBy", requestedBy);
        v.put("validFor", validMinutes == 1 ? "1 minute" : validMinutes + " minutes");
        String subject = "Your Premisave confirmation code is " + code;
        return new Message(subject, page("transfer-code", v, subject, "Share this code only if you expect this request."));
    }

    public static Message appReceived(String name, String fromName, String appName, String business, String merchantNumber,
                                      String portalUrl) {
        Map<String, String> v = base(name, portalUrl);
        v.put("fromName", fromName);
        v.put("appName", appName);
        v.put("business", business);
        v.put("merchantNumber", merchantNumber);
        String subject = "A production app was transferred to your Premisave account";
        return new Message(subject, page("app-received", v, subject, "Create a new API key before you use the app."));
    }

    public static Message appHandedOver(String name, String appName, String business, String merchantNumber, String toEmail,
                                        String portalUrl) {
        Map<String, String> v = base(name, portalUrl);
        v.put("appName", appName);
        v.put("business", business);
        v.put("merchantNumber", merchantNumber);
        v.put("toEmail", toEmail);
        String subject = "Your Premisave production app was transferred";
        return new Message(subject, page("app-handed-over", v, subject, "The app now belongs to another developer account."));
    }

    /** The test message the mail check sends at startup, so a person sees what the service's emails look like. */
    public static Message mailCheck(String sentTo, String sentFrom, String server, Instant at, String portalUrl) {
        Map<String, String> v = base(null, portalUrl);
        v.put("sentTo", sentTo);
        v.put("sentFrom", sentFrom == null || sentFrom.isBlank() ? "the mail server's login user" : sentFrom);
        v.put("server", server);
        v.put("time", at == null ? "" : WHEN.format(at.atOffset(ZoneOffset.UTC)));
        String subject = "Premisave developer service: mail check";
        return new Message(subject, page("mail-check", v, subject, "If you can read this, email is working."));
    }

    private static Map<String, String> base(String name, String portalUrl) {
        Map<String, String> v = new HashMap<>();
        v.put("name", name == null || name.isBlank() ? "there" : name.trim());
        v.put("portalUrl", portalUrl == null ? "" : portalUrl);
        v.put("secureLink", (portalUrl == null ? "" : portalUrl) + "/forgot-password");
        return v;
    }

    private static String page(String body, Map<String, String> values, String title, String preheader) {
        String content = fill(template(body), values);
        Map<String, String> layout = new HashMap<>();
        layout.put("title", title);
        layout.put("preheader", preheader);
        layout.put("portalUrl", values.getOrDefault("portalUrl", ""));
        layout.put("year", String.valueOf(java.time.Year.now(ZoneOffset.UTC).getValue()));
        layout.put("contentHtml", content);
        return fill(template("layout"), layout);
    }

    /** One pass over the template. Unknown placeholders become empty text. */
    static String fill(String template, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 256);
        while (m.find()) {
            String key = m.group(1);
            String value = values.get(key);
            String text = value == null ? "" : key.endsWith("Html") ? value : esc(value);
            m.appendReplacement(out, Matcher.quoteReplacement(text));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String template(String name) {
        return CACHE.computeIfAbsent(name, n -> {
            String path = "email/" + n + ".html";
            try (InputStream in = EmailTemplates.class.getClassLoader().getResourceAsStream(path)) {
                if (in == null) throw new IllegalStateException("Email template missing from the build: " + path);
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Could not read the email template " + path, e);
            }
        });
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