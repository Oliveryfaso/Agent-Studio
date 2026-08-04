package dev.agentconfig.workbench.context.claude;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Finds Claude Code import references without resolving, reading, or executing them.
 * Absolute and home-relative imports are reported as unsupported by this project-relative helper.
 */
public final class ClaudeImportParser {
    public static final int OFFICIAL_MAXIMUM_RECURSIVE_HOPS = 4;
    public static final int DEFAULT_MAXIMUM_INPUT_CHARACTERS = 256 * 1024;
    public static final int DEFAULT_MAXIMUM_IMPORTS = 1_000;

    private final int maximumInputCharacters;
    private final int maximumImports;

    public ClaudeImportParser() {
        this(DEFAULT_MAXIMUM_INPUT_CHARACTERS, DEFAULT_MAXIMUM_IMPORTS);
    }

    public ClaudeImportParser(int maximumInputCharacters, int maximumImports) {
        if (maximumInputCharacters < 1 || maximumImports < 1) {
            throw new IllegalArgumentException("Parser limits must be positive");
        }
        this.maximumInputCharacters = maximumInputCharacters;
        this.maximumImports = maximumImports;
    }

    public ClaudeImportScan parse(String markdown) {
        Objects.requireNonNull(markdown, "markdown");
        boolean truncated = markdown.length() > maximumInputCharacters;
        String input = truncated ? markdown.substring(0, maximumInputCharacters) : markdown;
        List<ClaudeImport> imports = new ArrayList<>();
        List<ClaudeDiagnostic> diagnostics = new ArrayList<>();
        if (truncated) {
            diagnostics.add(new ClaudeDiagnostic(
                    "IMPORT_INPUT_TRUNCATED", ClaudeDiagnosticSeverity.WARNING,
                    "Import scan stopped at the configured character budget", 0, 0));
        }

        char fenceCharacter = 0;
        int fenceLength = 0;
        int line = 1;
        int lineStart = 0;
        int index = 0;
        while (index < input.length()) {
            if (index == lineStart) {
                Fence fence = fenceAtLineStart(input, index);
                if (fence != null) {
                    if (fenceCharacter == 0) {
                        fenceCharacter = fence.character();
                        fenceLength = fence.length();
                    } else if (fence.character() == fenceCharacter && fence.length() >= fenceLength
                            && fence.closingEligible()) {
                        fenceCharacter = 0;
                        fenceLength = 0;
                    }
                    index = skipLine(input, index);
                    if (index < input.length()) {
                        index++;
                        line++;
                        lineStart = index;
                    }
                    continue;
                }
            }
            char current = input.charAt(index);
            if (current == '\n') {
                index++;
                line++;
                lineStart = index;
                continue;
            }
            if (fenceCharacter != 0) {
                index++;
                continue;
            }
            if (current == '`') {
                int ticks = repeated(input, index, '`');
                int closing = findMatchingTicks(input, index + ticks, ticks);
                if (closing < 0) {
                    index += ticks;
                } else {
                    for (int position = index; position < closing + ticks; position++) {
                        if (input.charAt(position) == '\n') {
                            line++;
                            lineStart = position + 1;
                        }
                    }
                    index = closing + ticks;
                }
                continue;
            }
            if (current == '@' && importBoundary(input, index)) {
                int start = index + 1;
                int end = start;
                while (end < input.length() && importPathCharacter(input.charAt(end))) {
                    end++;
                }
                String raw = trimTrailingPunctuation(input.substring(start, end));
                if (!raw.isEmpty()) {
                    int column = index - lineStart + 1;
                    if (imports.size() >= maximumImports) {
                        diagnostics.add(new ClaudeDiagnostic(
                                "IMPORT_COUNT_LIMIT", ClaudeDiagnosticSeverity.WARNING,
                                "Import scan stopped at the configured import-count budget", line, column));
                        break;
                    }
                    addRelativeImport(raw, line, column, imports, diagnostics);
                    index = Math.max(index + 1, start + raw.length());
                    continue;
                }
            }
            index++;
        }
        return new ClaudeImportScan(imports, diagnostics, truncated,
                OFFICIAL_MAXIMUM_RECURSIVE_HOPS);
    }

    private static void addRelativeImport(
            String raw,
            int line,
            int column,
            List<ClaudeImport> imports,
            List<ClaudeDiagnostic> diagnostics) {
        if (raw.startsWith("/") || raw.startsWith("~") || raw.matches("^[A-Za-z]:[\\\\/].*")) {
            diagnostics.add(new ClaudeDiagnostic(
                    "UNSUPPORTED_NON_RELATIVE_IMPORT", ClaudeDiagnosticSeverity.INFO,
                    "This helper only models project-relative imports: @" + raw, line, column));
            return;
        }
        try {
            Path path = Path.of(raw).normalize();
            if (path.toString().isEmpty()) {
                diagnostics.add(new ClaudeDiagnostic(
                        "INVALID_IMPORT_PATH", ClaudeDiagnosticSeverity.WARNING,
                        "Import path becomes empty after normalization", line, column));
                return;
            }
            imports.add(new ClaudeImport(raw, path, line, column));
        } catch (InvalidPathException exception) {
            diagnostics.add(new ClaudeDiagnostic(
                    "INVALID_IMPORT_PATH", ClaudeDiagnosticSeverity.WARNING,
                    "Import path is not valid on this platform", line, column));
        }
    }

    private static boolean importBoundary(String input, int index) {
        if (index == 0) {
            return true;
        }
        char previous = input.charAt(index - 1);
        return !Character.isLetterOrDigit(previous) && previous != '_' && previous != '.';
    }

    private static boolean importPathCharacter(char value) {
        return Character.isLetterOrDigit(value)
                || value == '.' || value == '_' || value == '-' || value == '/'
                || value == '\\' || value == '~' || value == ':';
    }

    private static String trimTrailingPunctuation(String raw) {
        int end = raw.length();
        while (end > 0) {
            char value = raw.charAt(end - 1);
            if (value == '.' || value == ',' || value == ';' || value == ':') {
                end--;
            } else {
                break;
            }
        }
        return raw.substring(0, end);
    }

    private static Fence fenceAtLineStart(String input, int lineStart) {
        int cursor = lineStart;
        int spaces = 0;
        while (cursor < input.length() && input.charAt(cursor) == ' ' && spaces < 4) {
            cursor++;
            spaces++;
        }
        if (spaces > 3 || cursor >= input.length()) {
            return null;
        }
        char value = input.charAt(cursor);
        if (value != '`' && value != '~') {
            return null;
        }
        int count = repeated(input, cursor, value);
        if (count < 3) {
            return null;
        }
        int suffix = cursor + count;
        while (suffix < input.length() && input.charAt(suffix) != '\n'
                && Character.isWhitespace(input.charAt(suffix))) {
            suffix++;
        }
        boolean closingEligible = suffix >= input.length() || input.charAt(suffix) == '\n';
        return new Fence(value, count, closingEligible);
    }

    private static int repeated(String input, int index, char value) {
        int cursor = index;
        while (cursor < input.length() && input.charAt(cursor) == value) {
            cursor++;
        }
        return cursor - index;
    }

    private static int findMatchingTicks(String input, int start, int count) {
        int cursor = start;
        while (cursor < input.length()) {
            if (input.charAt(cursor) == '`') {
                int found = repeated(input, cursor, '`');
                if (found == count) {
                    return cursor;
                }
                cursor += found;
            } else {
                cursor++;
            }
        }
        return -1;
    }

    private static int skipLine(String input, int index) {
        int cursor = index;
        while (cursor < input.length() && input.charAt(cursor) != '\n') {
            cursor++;
        }
        return cursor;
    }

    private record Fence(char character, int length, boolean closingEligible) {}
}
