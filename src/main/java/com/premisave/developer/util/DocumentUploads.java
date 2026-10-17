package com.premisave.developer.util;

import com.premisave.developer.exception.DeveloperException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Turns a file picked in a form into the JSON the wallet service takes for a business document. The caller sends a plain
 * file and a document type in ordinary words; the type may be written as "business registration", "business-registration"
 * or BUSINESS_REGISTRATION. Nothing here touches the network, so it is all checked in unit tests.
 */
public final class DocumentUploads {

    /** Largest file accepted, in bytes. The wallet service holds the final say. */
    public static final int MAX_BYTES = 5 * 1_048_576;

    public static final List<String> TYPES = List.of("BUSINESS_REGISTRATION", "CERTIFICATE_OF_INCORPORATION", "TAX_COMPLIANCE",
            "DIRECTOR_ID", "PROOF_OF_ADDRESS", "BANK_STATEMENT", "AUTHORISATION_LETTER", "OTHER");

    private DocumentUploads() {}

    /** BUSINESS_REGISTRATION from "business registration", "Business-Registration" and so on. A missing type is OTHER. */
    public static String type(String raw) {
        if (raw == null || raw.isBlank()) return "OTHER";
        String t = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        if (!TYPES.contains(t)) {
            throw DeveloperException.validation("type", "must be one of " + String.join(", ", TYPES).toLowerCase(Locale.ROOT)
                    .replace('_', ' '));
        }
        return t;
    }

    /** The content type to use: the one the browser sent when it is allowed, else a guess from the file extension. */
    public static String contentType(String sent, String fileName) {
        String s = sent == null ? "" : sent.trim().toLowerCase(Locale.ROOT);
        int semi = s.indexOf(';');
        if (semi >= 0) s = s.substring(0, semi).trim();
        if (s.equals("application/pdf") || s.equals("image/png") || s.equals("image/jpeg")) return s;
        String n = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        throw DeveloperException.validation("file", "must be a PDF, PNG or JPEG file");
    }

    /** The file name without any folder part, control characters or surrounding space, at most 200 characters. */
    public static String fileName(String original) {
        String n = original == null ? "" : original;
        int cut = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (cut >= 0) n = n.substring(cut + 1);
        StringBuilder b = new StringBuilder();
        for (char c : n.toCharArray()) {
            if (c >= 0x20 && c != 0x7f) b.append(c);
        }
        n = b.toString().trim();
        if (n.isEmpty()) throw DeveloperException.validation("file", "needs a file name");
        return n.length() > 200 ? n.substring(n.length() - 200) : n;
    }

    /** The JSON body for the wallet service's document upload. */
    public static byte[] walletBody(String type, String originalName, String sentContentType, byte[] content) {
        if (content == null || content.length == 0) throw DeveloperException.validation("file", "is empty");
        if (content.length > MAX_BYTES) {
            throw DeveloperException.validation("file", "is larger than " + (MAX_BYTES / 1_048_576) + " MB");
        }
        String name = fileName(originalName);
        String json = "{\"type\":" + quote(type(type)) + ",\"fileName\":" + quote(name) + ",\"contentType\":"
                + quote(contentType(sentContentType, name)) + ",\"contentBase64\":\""
                + Base64.getEncoder().encodeToString(content) + "\"}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}
