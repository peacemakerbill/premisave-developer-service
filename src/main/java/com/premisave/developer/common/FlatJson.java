package com.premisave.developer.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads and writes one flat JSON object whose values are strings, whole numbers, booleans or null. That is all a
 * token payload needs, and it keeps token handling free of a JSON library. Nested values are rejected.
 */
public final class FlatJson {

    private FlatJson() {}

    public static String write(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(quote(e.getKey())).append(':');
            Object v = e.getValue();
            if (v == null) sb.append("null");
            else if (v instanceof Number || v instanceof Boolean) sb.append(v);
            else sb.append(quote(v.toString()));
        }
        return sb.append('}').toString();
    }

    public static Map<String, Object> read(String json) {
        Parser p = new Parser(json);
        Map<String, Object> out = p.object();
        p.end();
        return out;
    }

    public static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) { this.s = s == null ? "" : s; }

        Map<String, Object> object() {
            ws();
            expect('{');
            Map<String, Object> out = new LinkedHashMap<>();
            ws();
            if (peek() == '}') { i++; return out; }
            while (true) {
                ws();
                String key = string();
                ws();
                expect(':');
                ws();
                out.put(key, value());
                ws();
                char c = next();
                if (c == '}') return out;
                if (c != ',') throw bad();
            }
        }

        Object value() {
            char c = peek();
            if (c == '"') return string();
            if (s.startsWith("null", i)) { i += 4; return null; }
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (c == '-' || (c >= '0' && c <= '9')) {
                int start = i;
                if (c == '-') i++;
                while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
                if (i == start || (i == start + 1 && c == '-')) throw bad();
                try {
                    return Long.parseLong(s.substring(start, i));
                } catch (NumberFormatException e) {
                    throw bad();
                }
            }
            throw bad();
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"', '\\', '/' -> sb.append(e);
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (i + 4 > s.length()) throw bad();
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw bad();
                            }
                            i += 4;
                        }
                        default -> throw bad();
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        void end() { ws(); if (i != s.length()) throw bad(); }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        char peek() { if (i >= s.length()) throw bad(); return s.charAt(i); }
        char next() { if (i >= s.length()) throw bad(); return s.charAt(i++); }
        void expect(char c) { if (next() != c) throw bad(); }
        IllegalArgumentException bad() { return new IllegalArgumentException("Not a flat JSON object"); }
    }
}
