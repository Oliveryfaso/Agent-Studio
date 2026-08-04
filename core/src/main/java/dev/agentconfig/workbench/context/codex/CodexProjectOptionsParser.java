package dev.agentconfig.workbench.context.codex;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A zero-dependency, bounded parser for the two Codex project-instruction discovery options.
 * It is deliberately not a general TOML parser and never retains the source snapshot.
 */
public final class CodexProjectOptionsParser {
    public static final int MAX_SNAPSHOT_BYTES = 1024 * 1024;

    private static final String FALLBACK_KEY = "project_doc_fallback_filenames";
    private static final String MAX_BYTES_KEY = "project_doc_max_bytes";

    public CodexProjectOptionsParseResult parse(String tomlSnapshot) {
        Objects.requireNonNull(tomlSnapshot, "tomlSnapshot");
        return parse(tomlSnapshot.getBytes(StandardCharsets.UTF_8));
    }

    public CodexProjectOptionsParseResult parse(byte[] tomlSnapshot) {
        Objects.requireNonNull(tomlSnapshot, "tomlSnapshot");
        if (tomlSnapshot.length > MAX_SNAPSHOT_BYTES) {
            return failed(CodexProjectOptionsDiagnosticCode.SNAPSHOT_TOO_LARGE, "", 0,
                    "Configuration snapshot exceeds the 1 MiB parser limit");
        }

        String source;
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(tomlSnapshot));
            source = decoded.toString();
        } catch (CharacterCodingException error) {
            return failed(CodexProjectOptionsDiagnosticCode.INVALID_UTF8, "", 0,
                    "Configuration snapshot is not valid UTF-8");
        }
        return parseDecoded(source);
    }

    private CodexProjectOptionsParseResult parseDecoded(String source) {
        String[] lines = source.split("\\R", -1);
        List<String> fallbackFilenames = List.of();
        long maxBytes = CodexProjectOptions.DEFAULT_MAX_BYTES;
        List<CodexProjectOptionsDiagnostic> diagnostics = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean rootTable = true;

        for (int index = 0; index < lines.length; index++) {
            String line = stripComment(lines[index]).strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[")) {
                rootTable = false;
                continue;
            }
            if (!rootTable) {
                continue;
            }

            int equals = findUnquoted(line, '=');
            if (equals < 0) {
                continue;
            }
            String key = parseKey(line.substring(0, equals).strip());
            if (!FALLBACK_KEY.equals(key) && !MAX_BYTES_KEY.equals(key)) {
                continue;
            }
            int sourceLine = index + 1;
            if (!seen.add(key)) {
                diagnostics.add(diagnostic(
                        CodexProjectOptionsDiagnosticCode.DUPLICATE_TARGET_KEY, key, sourceLine,
                        "Target option is declared more than once"));
                continue;
            }

            String value = line.substring(equals + 1).strip();
            if (FALLBACK_KEY.equals(key) && value.startsWith("[")
                    && bracketBalance(value) > 0) {
                StringBuilder combined = new StringBuilder(value);
                while (bracketBalance(combined.toString()) > 0 && index + 1 < lines.length) {
                    index++;
                    combined.append('\n').append(stripComment(lines[index]));
                }
                value = combined.toString().strip();
            }

            if (FALLBACK_KEY.equals(key)) {
                List<String> parsed = parseStringArray(value);
                if (parsed == null) {
                    diagnostics.add(diagnostic(
                            CodexProjectOptionsDiagnosticCode.INVALID_TARGET_VALUE, key, sourceLine,
                            "Expected a complete TOML array containing only strings"));
                } else {
                    fallbackFilenames = List.copyOf(parsed);
                }
            } else {
                Long parsed = parsePositiveInteger(value);
                if (parsed == null) {
                    diagnostics.add(diagnostic(
                            CodexProjectOptionsDiagnosticCode.INVALID_TARGET_VALUE, key, sourceLine,
                            "Expected a positive decimal integer"));
                } else {
                    maxBytes = parsed;
                }
            }
        }

        return new CodexProjectOptionsParseResult(
                new CodexProjectOptions(fallbackFilenames, maxBytes), diagnostics);
    }

    private static CodexProjectOptionsParseResult failed(
            CodexProjectOptionsDiagnosticCode code, String key, int line, String message) {
        return new CodexProjectOptionsParseResult(
                CodexProjectOptions.defaults(), List.of(diagnostic(code, key, line, message)));
    }

    private static CodexProjectOptionsDiagnostic diagnostic(
            CodexProjectOptionsDiagnosticCode code, String key, int line, String message) {
        return new CodexProjectOptionsDiagnostic(code, key, line, message);
    }

    private static String stripComment(String line) {
        boolean basic = false;
        boolean literal = false;
        boolean escaped = false;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (basic) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    basic = false;
                }
            } else if (literal) {
                if (current == '\'') {
                    literal = false;
                }
            } else if (current == '"') {
                basic = true;
            } else if (current == '\'') {
                literal = true;
            } else if (current == '#') {
                return line.substring(0, index);
            }
        }
        return line;
    }

    private static int findUnquoted(String value, char needle) {
        boolean basic = false;
        boolean literal = false;
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (basic) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    basic = false;
                }
            } else if (literal) {
                if (current == '\'') {
                    literal = false;
                }
            } else if (current == '"') {
                basic = true;
            } else if (current == '\'') {
                literal = true;
            } else if (current == needle) {
                return index;
            }
        }
        return -1;
    }

    private static String parseKey(String token) {
        if (token.equals(FALLBACK_KEY) || token.equals(MAX_BYTES_KEY)) {
            return token;
        }
        StringCursor cursor = new StringCursor(token);
        String parsed = cursor.readTomlString();
        cursor.skipWhitespace();
        return parsed != null && cursor.atEnd() ? parsed : null;
    }

    private static int bracketBalance(String value) {
        boolean basic = false;
        boolean literal = false;
        boolean escaped = false;
        int balance = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (basic) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    basic = false;
                }
            } else if (literal) {
                if (current == '\'') {
                    literal = false;
                }
            } else if (current == '"') {
                basic = true;
            } else if (current == '\'') {
                literal = true;
            } else if (current == '[') {
                balance++;
            } else if (current == ']') {
                balance--;
            }
        }
        return balance;
    }

    private static List<String> parseStringArray(String value) {
        StringCursor cursor = new StringCursor(value);
        cursor.skipWhitespace();
        if (!cursor.consume('[')) {
            return null;
        }
        List<String> result = new ArrayList<>();
        cursor.skipWhitespace();
        if (cursor.consume(']')) {
            cursor.skipWhitespace();
            return cursor.atEnd() ? result : null;
        }
        while (true) {
            cursor.skipWhitespace();
            String element = cursor.readTomlString();
            if (element == null) {
                return null;
            }
            result.add(element);
            cursor.skipWhitespace();
            if (cursor.consume(']')) {
                cursor.skipWhitespace();
                return cursor.atEnd() ? result : null;
            }
            if (!cursor.consume(',')) {
                return null;
            }
            cursor.skipWhitespace();
            if (cursor.consume(']')) {
                cursor.skipWhitespace();
                return cursor.atEnd() ? result : null;
            }
        }
    }

    private static Long parsePositiveInteger(String value) {
        if (!value.matches("\\+?[0-9](?:_?[0-9])*")) {
            return null;
        }
        String normalized = value.replace("_", "");
        String unsigned = normalized.startsWith("+") ? normalized.substring(1) : normalized;
        if (unsigned.startsWith("0") && unsigned.length() > 1) {
            return null;
        }
        try {
            long parsed = Long.parseLong(unsigned);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static final class StringCursor {
        private final String value;
        private int position;

        private StringCursor(String value) {
            this.value = value;
        }

        private boolean atEnd() {
            return position == value.length();
        }

        private void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(value.charAt(position))) {
                position++;
            }
        }

        private boolean consume(char expected) {
            if (!atEnd() && value.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private String readTomlString() {
            if (atEnd()) {
                return null;
            }
            char quote = value.charAt(position);
            if (quote != '"' && quote != '\'') {
                return null;
            }
            position++;
            StringBuilder result = new StringBuilder();
            while (!atEnd()) {
                char current = value.charAt(position++);
                if (current == quote) {
                    return result.toString();
                }
                if (quote == '\'' || current != '\\') {
                    if (Character.isISOControl(current) && current != '\t') {
                        return null;
                    }
                    result.append(current);
                    continue;
                }
                if (atEnd()) {
                    return null;
                }
                Character escaped = simpleEscape(value.charAt(position++));
                if (escaped != null) {
                    result.append(escaped);
                    continue;
                }
                char marker = value.charAt(position - 1);
                int digits = marker == 'u' ? 4 : marker == 'U' ? 8 : 0;
                if (digits == 0 || position + digits > value.length()) {
                    return null;
                }
                String hex = value.substring(position, position + digits);
                try {
                    int codePoint = Integer.parseUnsignedInt(hex, 16);
                    if (!Character.isValidCodePoint(codePoint)
                            || codePoint >= Character.MIN_SURROGATE
                            && codePoint <= Character.MAX_SURROGATE) {
                        return null;
                    }
                    result.appendCodePoint(codePoint);
                } catch (NumberFormatException error) {
                    return null;
                }
                position += digits;
            }
            return null;
        }

        private static Character simpleEscape(char marker) {
            return switch (marker) {
                case 'b' -> '\b';
                case 't' -> '\t';
                case 'n' -> '\n';
                case 'f' -> '\f';
                case 'r' -> '\r';
                case '"' -> '"';
                case '\\' -> '\\';
                default -> null;
            };
        }
    }
}
