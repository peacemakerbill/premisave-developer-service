package com.premisave.developer.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Where a request came from, for the sign in notice only. Nothing here is stored. Behind a reverse proxy set
 * server.forward-headers-strategy=native so that the address is the caller's and not the proxy's.
 */
public record ClientInfo(String ip, String userAgent) {

    public static final ClientInfo UNKNOWN = new ClientInfo(null, null);

    public static ClientInfo of(HttpServletRequest request) {
        if (request == null) return UNKNOWN;
        return new ClientInfo(clean(request.getRemoteAddr(), 64), clean(request.getHeader("User-Agent"), 300));
    }

    public String ipText() {
        return ip == null ? "Unknown" : ip;
    }

    /** A short description such as "Chrome on Windows". Never claims more than the User-Agent says. */
    public String device() {
        if (userAgent == null) return "Unknown device";
        String ua = userAgent;
        String browser = ua.contains("Edg/") ? "Edge"
                : ua.contains("OPR/") || ua.contains("Opera") ? "Opera"
                : ua.contains("Firefox/") ? "Firefox"
                : ua.contains("Chrome/") || ua.contains("CriOS/") ? "Chrome"
                : ua.contains("Safari/") ? "Safari"
                : ua.startsWith("curl/") ? "curl"
                : ua.contains("PostmanRuntime") ? "Postman"
                : null;
        String os = ua.contains("Windows") ? "Windows"
                : ua.contains("Android") ? "Android"
                : ua.contains("iPhone") || ua.contains("iPad") ? "iOS"
                : ua.contains("Mac OS X") || ua.contains("Macintosh") ? "macOS"
                : ua.contains("Linux") ? "Linux"
                : null;
        if (browser != null && os != null) return browser + " on " + os;
        if (browser != null) return browser;
        if (os != null) return os;
        return "Unknown device";
    }

    private static String clean(String s, int max) {
        if (s == null) return null;
        String t = s.replaceAll("[\\r\\n\\t]", " ").trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }
}
