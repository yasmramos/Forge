package io.github.yasmramos.forge.core.audit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader for the OSS Index responses (AGENT.md section 18).
 *
 * <p>Forge ships no JSON library: the SBOM writer produces JSON by hand, and
 * the audit needs only to read a small, well-defined response. This parser
 * covers the JSON subset those documents use - objects, arrays, strings,
 * numbers, booleans and null - and is strict on malformed input, throwing
 * {@link JsonException} rather than returning a half-parsed document.</p>
 */
final class Json {

    /** Raised on malformed JSON; callers translate it into a structured error. */
    static final class JsonException extends RuntimeException {
        JsonException(String message) {
            super(message);
        }
    }

    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    /** Parses a whole document; trailing non-whitespace is an error. */
    static Object parse(String text) {
        if (text == null) {
            throw new JsonException("empty JSON document");
        }
        Json parser = new Json(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.pos < parser.text.length()) {
            throw new JsonException("unexpected trailing content at offset " + parser.pos);
        }
        return value;
    }

    private Object readValue() {
        if (pos >= text.length()) {
            throw new JsonException("unexpected end of input");
        }
        char c = text.charAt(pos);
        switch (c) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return readNumber();
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // '{'
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            if (peek() != ':') {
                throw new JsonException("expected ':' at offset " + pos);
            }
            pos++;
            skipWhitespace();
            map.put(key, readValue());
            skipWhitespace();
            char next = peek();
            if (next == ',') {
                pos++;
                continue;
            }
            if (next == '}') {
                pos++;
                return map;
            }
            throw new JsonException("expected ',' or '}' at offset " + pos);
        }
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<>();
        pos++; // '['
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(readValue());
            skipWhitespace();
            char next = peek();
            if (next == ',') {
                pos++;
                continue;
            }
            if (next == ']') {
                pos++;
                return list;
            }
            throw new JsonException("expected ',' or ']' at offset " + pos);
        }
    }

    private String readString() {
        if (peek() != '"') {
            throw new JsonException("expected string at offset " + pos);
        }
        pos++;
        StringBuilder sb = new StringBuilder();
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                sb.append(readEscape());
            } else {
                sb.append(c);
            }
        }
        throw new JsonException("unterminated string");
    }

    private char readEscape() {
        if (pos >= text.length()) {
            throw new JsonException("unterminated escape sequence");
        }
        char c = text.charAt(pos++);
        switch (c) {
            case '"': return '"';
            case '\\': return '\\';
            case '/': return '/';
            case 'b': return '\b';
            case 'f': return '\f';
            case 'n': return '\n';
            case 'r': return '\r';
            case 't': return '\t';
            case 'u':
                if (pos + 4 > text.length()) {
                    throw new JsonException("truncated unicode escape");
                }
                String hex = text.substring(pos, pos + 4);
                pos += 4;
                try {
                    return (char) Integer.parseInt(hex, 16);
                } catch (NumberFormatException e) {
                    throw new JsonException("invalid unicode escape \\u" + hex);
                }
            default:
                throw new JsonException("invalid escape '\\" + c + "'");
        }
    }

    private Object readNumber() {
        int start = pos;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9')) {
                pos++;
            } else {
                break;
            }
        }
        if (start == pos) {
            throw new JsonException("unexpected character '" + text.charAt(pos)
                + "' at offset " + pos);
        }
        String number = text.substring(start, pos);
        try {
            if (number.indexOf('.') < 0 && number.indexOf('e') < 0 && number.indexOf('E') < 0) {
                return Long.parseLong(number);
            }
            return Double.parseDouble(number);
        } catch (NumberFormatException e) {
            throw new JsonException("invalid number '" + number + "'");
        }
    }

    private void expect(String literal) {
        if (!text.startsWith(literal, pos)) {
            throw new JsonException("expected '" + literal + "' at offset " + pos);
        }
        pos += literal.length();
    }

    private char peek() {
        if (pos >= text.length()) {
            throw new JsonException("unexpected end of input");
        }
        return text.charAt(pos);
    }

    private void skipWhitespace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }
}
