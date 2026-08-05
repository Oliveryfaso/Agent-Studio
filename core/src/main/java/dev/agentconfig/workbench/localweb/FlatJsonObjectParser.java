package dev.agentconfig.workbench.localweb;

import java.util.LinkedHashMap;
import java.util.Map;

/** Strict parser for the flat string/boolean request objects used by loopback API v1. */
final class FlatJsonObjectParser {
    private final String source;
    private int index;

    private FlatJsonObjectParser(String source) {
        this.source = source;
    }

    static Map<String, Object> parse(String source) {
        if (source == null) throw new IllegalArgumentException("json");
        return new FlatJsonObjectParser(source).object();
    }

    private Map<String, Object> object() {
        Map<String, Object> values = new LinkedHashMap<>();
        whitespace();
        expect('{');
        whitespace();
        if (take('}')) return finish(values);
        while (true) {
            String key = string();
            whitespace();
            expect(':');
            whitespace();
            Object value = peek('"') ? string() : bool();
            if (values.putIfAbsent(key, value) != null) throw new IllegalArgumentException("duplicate");
            whitespace();
            if (take('}')) return finish(values);
            expect(',');
            whitespace();
        }
    }

    private Map<String, Object> finish(Map<String, Object> values) {
        whitespace();
        if (index != source.length()) throw new IllegalArgumentException("trailing");
        return Map.copyOf(values);
    }

    private String string() {
        expect('"');
        StringBuilder value = new StringBuilder();
        while (index < source.length()) {
            char character = source.charAt(index++);
            if (character == '"') return validString(value.toString());
            if (character < 0x20) throw new IllegalArgumentException("control");
            if (character != '\\') {
                value.append(character);
                continue;
            }
            if (index >= source.length()) throw new IllegalArgumentException("escape");
            char escaped = source.charAt(index++);
            switch (escaped) {
                case '"', '\\', '/' -> value.append(escaped);
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> value.append(unicode());
                default -> throw new IllegalArgumentException("escape");
            }
        }
        throw new IllegalArgumentException("string");
    }

    private char unicode() {
        if (index + 4 > source.length()) throw new IllegalArgumentException("unicode");
        int value = 0;
        for (int count = 0; count < 4; count++) {
            int digit = Character.digit(source.charAt(index++), 16);
            if (digit < 0) throw new IllegalArgumentException("unicode");
            value = value * 16 + digit;
        }
        return (char) value;
    }

    private Boolean bool() {
        if (source.startsWith("true", index)) {
            index += 4;
            return Boolean.TRUE;
        }
        if (source.startsWith("false", index)) {
            index += 5;
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("value");
    }

    private static String validString(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException("surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("surrogate");
            }
        }
        return value;
    }

    private void whitespace() {
        while (index < source.length()) {
            char character = source.charAt(index);
            if (character != ' ' && character != '\t' && character != '\r'
                    && character != '\n') return;
            index++;
        }
    }

    private boolean peek(char value) {
        return index < source.length() && source.charAt(index) == value;
    }

    private boolean take(char value) {
        if (!peek(value)) return false;
        index++;
        return true;
    }

    private void expect(char value) {
        if (!take(value)) throw new IllegalArgumentException("expected " + value);
    }
}
