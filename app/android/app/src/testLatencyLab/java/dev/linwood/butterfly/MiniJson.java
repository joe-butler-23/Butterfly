package dev.linwood.butterfly;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny recursive-descent JSON reader for test fixtures only.
 *
 * <p>{@code org.json} (bundled in the Android SDK stub jar) throws {@code RuntimeException:
 * Method ... not mocked} under a plain JVM unit test, and pulling in a full JSON dependency for
 * one fixture file is not worth it, so this parses just enough of JSON -- objects, arrays,
 * strings, numbers, booleans and null -- to read {@code perfect_freehand_fixtures.json}.
 */
final class MiniJson {
    private final String text;
    private int index;

    private MiniJson(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        MiniJson parser = new MiniJson(text);
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.index != text.length()) {
            throw new IllegalArgumentException("Trailing content at index " + parser.index);
        }
        return value;
    }

    private Object readValue() {
        skipWhitespace();
        char c = text.charAt(index);
        if (c == '{') return readObject();
        if (c == '[') return readArray();
        if (c == '"') return readString();
        if (c == 't') { expect("true"); return Boolean.TRUE; }
        if (c == 'f') { expect("false"); return Boolean.FALSE; }
        if (c == 'n') { expect("null"); return null; }
        return readNumber();
    }

    private Map<String, Object> readObject() {
        Map<String, Object> result = new LinkedHashMap<>();
        index++; // '{'
        skipWhitespace();
        if (peek() == '}') { index++; return result; }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            if (text.charAt(index) != ':') throw new IllegalArgumentException("Expected ':'");
            index++;
            result.put(key, readValue());
            skipWhitespace();
            char c = text.charAt(index++);
            if (c == '}') return result;
            if (c != ',') throw new IllegalArgumentException("Expected ',' or '}'");
        }
    }

    private List<Object> readArray() {
        List<Object> result = new ArrayList<>();
        index++; // '['
        skipWhitespace();
        if (peek() == ']') { index++; return result; }
        while (true) {
            result.add(readValue());
            skipWhitespace();
            char c = text.charAt(index++);
            if (c == ']') return result;
            if (c != ',') throw new IllegalArgumentException("Expected ',' or ']'");
        }
    }

    private String readString() {
        if (text.charAt(index) != '"') throw new IllegalArgumentException("Expected '\"'");
        index++;
        StringBuilder builder = new StringBuilder();
        while (true) {
            char c = text.charAt(index++);
            if (c == '"') return builder.toString();
            if (c == '\\') {
                char escaped = text.charAt(index++);
                switch (escaped) {
                    case 'n' -> builder.append('\n');
                    case 't' -> builder.append('\t');
                    case 'r' -> builder.append('\r');
                    default -> builder.append(escaped);
                }
            } else {
                builder.append(c);
            }
        }
    }

    private Double readNumber() {
        int start = index;
        while (index < text.length() && "-+.0123456789eE".indexOf(text.charAt(index)) >= 0) {
            index++;
        }
        return Double.parseDouble(text.substring(start, index));
    }

    private void expect(String literal) {
        if (!text.startsWith(literal, index)) {
            throw new IllegalArgumentException("Expected '" + literal + "' at " + index);
        }
        index += literal.length();
    }

    private char peek() {
        return text.charAt(index);
    }

    private void skipWhitespace() {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
    }
}
