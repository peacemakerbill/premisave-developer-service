package com.premisave.developer.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads any JSON document into Maps, Lists, Strings, Longs, Doubles, Booleans and null, using only the JDK. It is used
 * for the replies of sign in providers (Google, Facebook, GitHub), which are not flat objects: GitHub answers a list.
 * Nesting is limited so a hostile reply cannot exhaust the stack.
 */
public final class JsonReader {

    private static final int MAX_DEPTH = 32;

    private final String s;
    private int i;

    private JsonReader(String s) {
        this.s = s == null ? "" : s;
    }

    public static Object read(String json) {
        JsonReader r = new JsonReader(json);
        r.ws();
        Object v = r.value(0);
        r.ws();
        if (r.i != r.s.length()) throw r.bad();
        return v;
    }

    /** The document as an object, or an error when it is anything else. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readObject(String json) {
        Object v = read(json);
        if (v instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new IllegalArgumentException("JSON object expected");
    }

    /** The document as a list, or an error when it is anything else. */
    @SuppressWarnings("unchecked")
    public static List<Object> readArray(String json) {
        Object v = read(json);
        if (v instanceof List<?> l) return (List<Object>) l;
        throw new IllegalArgumentException("JSON array expected");
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) throw bad();
        char c = peek();
        switch (c) {
            case '{': return object(depth);
            case '[': return array(depth);
            case '"': return string();
            default:
        }
        if (s.startsWith("null", i)) { i += 4; return null; }
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (c == '-' || (c >= '0' && c <= '9')) return number();
        throw bad();
    }

    private Map<String, Object> object(int depth) {
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
            out.put(key, value(depth + 1));
            ws();
            char c = next();
            if (c == '}') return out;
            if (c != ',') throw bad();
        }
    }

    private List<Object> array(int depth) {
        expect('[');
        List<Object> out = new ArrayList<>();
        ws();
        if (peek() == ']') { i++; return out; }
        while (true) {
            ws();
            out.add(value(depth + 1));
            ws();
            char c = next();
            if (c == ']') return out;
            if (c != ',') throw bad();
        }
    }

    private Object number() {
        int start = i;
        if (s.charAt(i) == '-') i++;
        int digits = i;
        while (i < s.length() && Character.isDigit(s.charAt(i)) && s.charAt(i) < 128) i++;
        if (i == digits) throw bad();
        boolean fraction = false;
        if (i < s.length() && s.charAt(i) == '.') {
            fraction = true;
            i++;
            int f = i;
            while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
            if (i == f) throw bad();
        }
        if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            fraction = true;
            i++;
            if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
            int e = i;
            while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
            if (i == e) throw bad();
        }
        String text = s.substring(start, i);
        try {
            if (!fraction) {
                try {
                    return Long.parseLong(text);
                } catch (NumberFormatException tooBig) {
                    return Double.parseDouble(text);
                }
            }
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw bad();
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') return sb.toString();
            if (c < 0x20) throw bad();
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = next();
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u': {
                    if (i + 4 > s.length()) throw bad();
                    try {
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw bad();
                    }
                    i += 4;
                    break;
                }
                default: throw bad();
            }
        }
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
            else break;
        }
    }

    private char peek() {
        if (i >= s.length()) throw bad();
        return s.charAt(i);
    }

    private char next() {
        if (i >= s.length()) throw bad();
        return s.charAt(i++);
    }

    private void expect(char c) {
        if (next() != c) throw bad();
    }

    private IllegalArgumentException bad() {
        return new IllegalArgumentException("Invalid JSON at position " + i);
    }
}
