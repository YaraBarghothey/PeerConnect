/*
 * PeerConnect - Computer Networks I (10636454) Homework 2026
 * Authors: YOUR_NAME_1 / YOUR_ID_1  and  YOUR_NAME_2 / YOUR_ID_2   (replace placeholders)
 */
package com.peerconnect.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, strict JSON parser/serializer (RFC 8259 subset) written for this project so that the
 * build has ZERO third-party dependencies. Objects are Map&lt;String,Object&gt;, arrays are List&lt;Object&gt;,
 * numbers are Long or Double. Error messages never contain the input text (it may hold passwords).
 */
public final class Json {
    private Json() {}

    public static final class JsonException extends Exception {
        public JsonException(String message) { super(message); }
    }

    private static final int MAX_DEPTH = 16;

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String s) throws JsonException {
        Object o = parse(s);
        if (!(o instanceof Map)) throw new JsonException("JSON value is not an object");
        return (Map<String, Object>) o;
    }

    public static Object parse(String s) throws JsonException {
        if (s == null) throw new JsonException("null input");
        Parser p = new Parser(s);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != s.length()) throw new JsonException("Unexpected trailing data at position " + p.i);
        return v;
    }

    private static final class Parser {
        final String s;
        int i = 0;
        Parser(String s) { this.s = s; }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++; else break;
            }
        }

        Object value(int depth) throws JsonException {
            if (depth > MAX_DEPTH) throw new JsonException("Nesting too deep");
            if (i >= s.length()) throw new JsonException("Unexpected end of input");
            char c = s.charAt(i);
            switch (c) {
                case '{': return object(depth);
                case '[': return array(depth);
                case '"': return string();
                case 't': literal("true"); return Boolean.TRUE;
                case 'f': literal("false"); return Boolean.FALSE;
                case 'n': literal("null"); return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return number();
                    throw new JsonException("Unexpected character at position " + i);
            }
        }

        void literal(String lit) throws JsonException {
            if (!s.startsWith(lit, i)) throw new JsonException("Invalid literal at position " + i);
            i += lit.length();
        }

        Object number() throws JsonException {
            int start = i;
            boolean isDouble = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '.' || c == 'e' || c == 'E') { isDouble = true; i++; }
                else if (c == '-' || c == '+' || (c >= '0' && c <= '9')) i++;
                else break;
            }
            String t = s.substring(start, i);
            try {
                if (isDouble) return Double.parseDouble(t);
                return Long.parseLong(t);
            } catch (NumberFormatException e) {
                throw new JsonException("Invalid number at position " + start);
            }
        }

        String string() throws JsonException {
            i++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw new JsonException("Unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c < 0x20) throw new JsonException("Unescaped control character in string");
                if (c != '\\') { sb.append(c); continue; }
                if (i >= s.length()) throw new JsonException("Bad escape");
                char e = s.charAt(i++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw new JsonException("Bad unicode escape");
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw new JsonException("Bad unicode escape");
                        }
                        i += 4;
                        break;
                    default: throw new JsonException("Bad escape character");
                }
            }
        }

        Map<String, Object> object(int depth) throws JsonException {
            i++; // {
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') throw new JsonException("Expected object key at position " + i);
                String k = string();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw new JsonException("Expected ':' at position " + i);
                i++;
                ws();
                m.put(k, value(depth + 1));
                ws();
                if (i >= s.length()) throw new JsonException("Unterminated object");
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw new JsonException("Expected ',' or '}' at position " + (i - 1));
            }
        }

        List<Object> array(int depth) throws JsonException {
            i++; // [
            List<Object> l = new ArrayList<>();
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value(depth + 1));
                ws();
                if (i >= s.length()) throw new JsonException("Unterminated array");
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw new JsonException("Expected ',' or ']' at position " + (i - 1));
            }
        }
    }

    // ---------------------------------------------------------------- serializer

    public static String stringify(Object o) {
        StringBuilder sb = new StringBuilder(128);
        write(sb, o);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object o) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof String) { quote(sb, (String) o); return; }
        if (o instanceof Boolean) { sb.append(o.toString()); return; }
        if (o instanceof Number) {
            if (o instanceof Double || o instanceof Float) {
                double d = ((Number) o).doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException("NaN/Infinity not allowed");
            }
            sb.append(o.toString());
            return;
        }
        if (o instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (o instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object x : (Iterable<?>) o) {
                if (!first) sb.append(',');
                first = false;
                write(sb, x);
            }
            sb.append(']');
            return;
        }
        if (o instanceof Msg) { write(sb, ((Msg) o).asMap()); return; }
        throw new IllegalArgumentException("Unsupported JSON type: " + o.getClass().getName());
    }

    /** Escapes quotes, backslashes and ALL control characters, so a frame never contains a raw newline. */
    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20 || c == '\u2028' || c == '\u2029') sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }
}
