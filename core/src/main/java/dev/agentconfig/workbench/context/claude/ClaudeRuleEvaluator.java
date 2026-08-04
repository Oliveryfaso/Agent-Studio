package dev.agentconfig.workbench.context.claude;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Parses the small YAML subset used by Claude Code rule path frontmatter and evaluates it against
 * one normalized project-relative path. It never reads a target file or executes rule content.
 */
public final class ClaudeRuleEvaluator {
    public static final int DEFAULT_MAXIMUM_INPUT_CHARACTERS = 128 * 1024;
    public static final int DEFAULT_MAXIMUM_FRONTMATTER_LINES = 512;
    public static final int DEFAULT_MAXIMUM_PATTERNS = 1_000;
    public static final int DEFAULT_MAXIMUM_PATTERN_CHARACTERS = 4_096;
    public static final int MAXIMUM_EXPANDED_PATTERNS = 1_000;
    public static final long MAXIMUM_EXPANDED_PATTERN_CHARACTERS = 4L * 1024L * 1024L;

    private final int maximumInputCharacters;
    private final int maximumFrontmatterLines;
    private final int maximumPatterns;
    private final int maximumPatternCharacters;

    public ClaudeRuleEvaluator() {
        this(DEFAULT_MAXIMUM_INPUT_CHARACTERS, DEFAULT_MAXIMUM_FRONTMATTER_LINES,
                DEFAULT_MAXIMUM_PATTERNS, DEFAULT_MAXIMUM_PATTERN_CHARACTERS);
    }

    public ClaudeRuleEvaluator(
            int maximumInputCharacters,
            int maximumFrontmatterLines,
            int maximumPatterns,
            int maximumPatternCharacters) {
        if (maximumInputCharacters < 1 || maximumFrontmatterLines < 1
                || maximumPatterns < 1 || maximumPatternCharacters < 1) {
            throw new IllegalArgumentException("Evaluator limits must be positive");
        }
        this.maximumInputCharacters = maximumInputCharacters;
        this.maximumFrontmatterLines = maximumFrontmatterLines;
        this.maximumPatterns = maximumPatterns;
        this.maximumPatternCharacters = maximumPatternCharacters;
    }

    public ClaudeRuleDefinition parse(String markdown) {
        Objects.requireNonNull(markdown, "markdown");
        List<ClaudeDiagnostic> diagnostics = new ArrayList<>();
        if (markdown.length() > maximumInputCharacters) {
            diagnostics.add(diagnostic("RULE_INPUT_LIMIT", ClaudeDiagnosticSeverity.ERROR,
                    "Rule exceeds the configured character budget", 0, 0));
            return new ClaudeRuleDefinition(false, false, List.of(), diagnostics);
        }
        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        if (lines.length == 0 || !stripBom(lines[0]).trim().equals("---")) {
            return new ClaudeRuleDefinition(false, true, List.of(), diagnostics);
        }
        int closing = -1;
        int inspected = Math.min(lines.length, maximumFrontmatterLines + 1);
        for (int index = 1; index < inspected; index++) {
            if (lines[index].trim().equals("---")) {
                closing = index;
                break;
            }
        }
        if (closing < 0) {
            diagnostics.add(diagnostic("FRONTMATTER_NOT_TERMINATED", ClaudeDiagnosticSeverity.ERROR,
                    "YAML frontmatter has no closing delimiter within the line budget", 1, 1));
            return new ClaudeRuleDefinition(true, false, List.of(), diagnostics);
        }

        List<String> paths = new ArrayList<>();
        boolean pathsSeen = false;
        boolean collectingPaths = false;
        int pathsIndent = -1;
        boolean valid = true;
        for (int index = 1; index < closing; index++) {
            String line = lines[index];
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int indent = indentation(line);
            if (collectingPaths && indent > pathsIndent && trimmed.startsWith("-")) {
                String item = trimmed.substring(1).trim();
                String parsed = parseScalar(item, index + 1, diagnostics);
                if (parsed == null) {
                    valid = false;
                } else if (!addPattern(parsed, index + 1, paths, diagnostics)) {
                    valid = false;
                }
                continue;
            }
            collectingPaths = false;
            int colon = trimmed.indexOf(':');
            if (colon < 0 || !trimmed.substring(0, colon).trim().equals("paths")) {
                continue;
            }
            if (pathsSeen) {
                diagnostics.add(diagnostic("DUPLICATE_PATHS_FIELD", ClaudeDiagnosticSeverity.ERROR,
                        "paths may only appear once in rule frontmatter", index + 1, indent + 1));
                valid = false;
                continue;
            }
            pathsSeen = true;
            pathsIndent = indent;
            String value = trimmed.substring(colon + 1).trim();
            if (value.isEmpty()) {
                collectingPaths = true;
            } else if (value.startsWith("[") && value.endsWith("]")) {
                List<String> inline = parseInlineList(
                        value.substring(1, value.length() - 1), index + 1, diagnostics);
                if (inline == null) {
                    valid = false;
                } else {
                    for (String pattern : inline) {
                        if (!addPattern(pattern, index + 1, paths, diagnostics)) {
                            valid = false;
                        }
                    }
                }
            } else {
                diagnostics.add(diagnostic("PATHS_NOT_A_LIST", ClaudeDiagnosticSeverity.ERROR,
                        "paths must be a YAML list", index + 1, indent + colon + 2));
                valid = false;
            }
        }
        if (pathsSeen && paths.isEmpty()) {
            diagnostics.add(diagnostic("EMPTY_PATHS_LIST", ClaudeDiagnosticSeverity.ERROR,
                    "paths must contain at least one glob", 0, 0));
            valid = false;
        }
        return new ClaudeRuleDefinition(true, valid, paths, diagnostics);
    }

    public ClaudeRuleEvaluation evaluate(String markdown, String projectRelativeTarget) {
        return evaluate(parse(markdown), projectRelativeTarget);
    }

    public ClaudeRuleEvaluation evaluate(
            ClaudeRuleDefinition definition, String projectRelativeTarget) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(projectRelativeTarget, "projectRelativeTarget");
        List<ClaudeDiagnostic> diagnostics = new ArrayList<>(definition.diagnostics());
        if (!definition.valid()) {
            return result(ClaudeRuleApplicability.INVALID, definition.paths(), List.of(), diagnostics);
        }
        if (definition.unconditional()) {
            return result(ClaudeRuleApplicability.ALWAYS, definition.paths(), List.of(), diagnostics);
        }
        String target = normalizeTarget(projectRelativeTarget);
        if (target == null) {
            diagnostics.add(diagnostic("INVALID_TARGET_PATH", ClaudeDiagnosticSeverity.ERROR,
                    "Target must be a normalized project-relative path without traversal", 0, 0));
            return result(ClaudeRuleApplicability.INVALID, definition.paths(), List.of(), diagnostics);
        }

        List<String> matching = new ArrayList<>();
        int usablePatterns = 0;
        ExpansionBudget budget = new ExpansionBudget();
        for (String configured : definition.paths()) {
            List<String> expanded = expandBraces(configured, budget, diagnostics);
            if (expanded.isEmpty()) {
                continue;
            }
            for (String pattern : expanded) {
                String regex = globToRegex(pattern, diagnostics);
                if (regex == null) {
                    continue;
                }
                usablePatterns++;
                try {
                    if (Pattern.matches(regex, target)) {
                        matching.add(configured);
                        break;
                    }
                } catch (PatternSyntaxException exception) {
                    diagnostics.add(diagnostic("INVALID_GLOB", ClaudeDiagnosticSeverity.WARNING,
                            "Glob could not be compiled: " + configured, 0, 0));
                }
            }
        }
        if (usablePatterns == 0) {
            diagnostics.add(diagnostic("NO_USABLE_GLOBS", ClaudeDiagnosticSeverity.ERROR,
                    "No valid path glob remains for evaluation", 0, 0));
            return result(ClaudeRuleApplicability.INVALID, definition.paths(), matching, diagnostics);
        }
        ClaudeRuleApplicability applicability = matching.isEmpty()
                ? ClaudeRuleApplicability.CONDITIONAL_NO_MATCH
                : ClaudeRuleApplicability.CONDITIONAL_MATCH;
        return result(applicability, definition.paths(), matching, diagnostics);
    }

    private boolean addPattern(
            String pattern,
            int line,
            List<String> paths,
            List<ClaudeDiagnostic> diagnostics) {
        if (pattern.isEmpty()) {
            diagnostics.add(diagnostic("EMPTY_PATH_PATTERN", ClaudeDiagnosticSeverity.ERROR,
                    "Path glob cannot be empty", line, 1));
            return false;
        }
        if (pattern.length() > maximumPatternCharacters) {
            diagnostics.add(diagnostic("PATH_PATTERN_LIMIT", ClaudeDiagnosticSeverity.ERROR,
                    "Path glob exceeds the configured character budget", line, 1));
            return false;
        }
        if (paths.size() >= maximumPatterns) {
            diagnostics.add(diagnostic("PATH_COUNT_LIMIT", ClaudeDiagnosticSeverity.ERROR,
                    "paths exceeds the configured pattern-count budget", line, 1));
            return false;
        }
        paths.add(pattern.replace('\\', '/'));
        return true;
    }

    private static List<String> parseInlineList(
            String content, int line, List<ClaudeDiagnostic> diagnostics) {
        List<String> result = new ArrayList<>();
        int start = 0;
        char quote = 0;
        for (int index = 0; index <= content.length(); index++) {
            char current = index == content.length() ? ',' : content.charAt(index);
            if ((current == '\'' || current == '"')) {
                if (quote == 0) {
                    quote = current;
                } else if (quote == current && (index == 0 || content.charAt(index - 1) != '\\')) {
                    quote = 0;
                }
            }
            if (current == ',' && quote == 0) {
                String scalar = parseScalar(content.substring(start, index).trim(), line, diagnostics);
                if (scalar == null) {
                    return null;
                }
                result.add(scalar);
                start = index + 1;
            }
        }
        if (quote != 0) {
            diagnostics.add(diagnostic("MALFORMED_INLINE_PATHS", ClaudeDiagnosticSeverity.ERROR,
                    "Inline paths list has an unterminated quote", line, 1));
            return null;
        }
        return result;
    }

    private static String parseScalar(
            String input, int line, List<ClaudeDiagnostic> diagnostics) {
        String value = input;
        if (value.length() >= 2
                && ((value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"')
                || (value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\''))) {
            value = value.substring(1, value.length() - 1);
        } else {
            int comment = value.indexOf(" #");
            if (comment >= 0) {
                value = value.substring(0, comment).trim();
            }
            if (value.startsWith("\"") || value.startsWith("'")) {
                diagnostics.add(diagnostic("MALFORMED_PATH_SCALAR", ClaudeDiagnosticSeverity.ERROR,
                        "Path glob has an unterminated quote", line, 1));
                return null;
            }
        }
        return value;
    }

    private static List<String> expandBraces(
            String pattern,
            ExpansionBudget budget,
            List<ClaudeDiagnostic> diagnostics) {
        List<String> pending = new ArrayList<>();
        pending.add(pattern);
        for (int cursor = 0; cursor < pending.size(); cursor++) {
            String candidate = pending.get(cursor);
            int open = unescapedIndexOf(candidate, '{', 0);
            if (open < 0) {
                continue;
            }
            int close = unescapedIndexOf(candidate, '}', open + 1);
            if (close < 0) {
                diagnostics.add(diagnostic("INVALID_GLOB", ClaudeDiagnosticSeverity.WARNING,
                        "Glob has an unclosed brace: " + pattern, 0, 0));
                return List.of();
            }
            String[] alternatives = candidate.substring(open + 1, close).split(",", -1);
            if (alternatives.length < 2) {
                diagnostics.add(diagnostic("INVALID_GLOB", ClaudeDiagnosticSeverity.WARNING,
                        "Brace expression must contain alternatives: " + pattern, 0, 0));
                return List.of();
            }
            pending.remove(cursor);
            cursor--;
            for (String alternative : alternatives) {
                String expanded = candidate.substring(0, open) + alternative
                        + candidate.substring(close + 1);
                if (!budget.accept(expanded)) {
                    diagnostics.add(diagnostic("GLOB_EXPANSION_LIMIT", ClaudeDiagnosticSeverity.WARNING,
                            "Brace expansion exceeded the shared 1,000-pattern or 4 MiB budget", 0, 0));
                    return List.of(pattern);
                }
                pending.add(expanded);
            }
        }
        return List.copyOf(pending);
    }

    private static String globToRegex(String glob, List<ClaudeDiagnostic> diagnostics) {
        if (glob.isEmpty() || glob.startsWith("/") || glob.equals("..") || glob.startsWith("../")) {
            diagnostics.add(diagnostic("INVALID_GLOB", ClaudeDiagnosticSeverity.WARNING,
                    "Glob must be project-relative: " + glob, 0, 0));
            return null;
        }
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < glob.length(); index++) {
            char value = glob.charAt(index);
            if (value == '\\' && index + 1 < glob.length()) {
                appendLiteral(regex, glob.charAt(++index));
            } else if (value == '*') {
                if (index + 1 < glob.length() && glob.charAt(index + 1) == '*') {
                    index++;
                    if (index + 1 < glob.length() && glob.charAt(index + 1) == '/') {
                        index++;
                        regex.append("(?:[^/]+/)*");
                    } else {
                        regex.append(".*");
                    }
                } else {
                    regex.append("[^/]*");
                }
            } else if (value == '?') {
                regex.append("[^/]");
            } else if (value == '[') {
                int close = closingBracket(glob, index + 1);
                if (close < 0) {
                    diagnostics.add(diagnostic("INVALID_GLOB", ClaudeDiagnosticSeverity.WARNING,
                            "Glob has an unclosed bracket expression: " + glob, 0, 0));
                    return null;
                }
                appendCharacterClass(regex, glob.substring(index + 1, close));
                index = close;
            } else {
                appendLiteral(regex, value);
            }
        }
        return regex.append('$').toString();
    }

    private static void appendCharacterClass(StringBuilder regex, String content) {
        regex.append('[');
        int index = 0;
        if (!content.isEmpty() && (content.charAt(0) == '!' || content.charAt(0) == '^')) {
            regex.append('^');
            index++;
        }
        for (; index < content.length(); index++) {
            char value = content.charAt(index);
            if (value == '\\' || value == ']') {
                regex.append('\\');
            }
            regex.append(value);
        }
        regex.append(']');
    }

    private static int closingBracket(String glob, int start) {
        for (int index = start; index < glob.length(); index++) {
            if (glob.charAt(index) == ']' && index > start) {
                return index;
            }
        }
        return -1;
    }

    private static void appendLiteral(StringBuilder regex, char value) {
        if (".()|+^$@%{}[]".indexOf(value) >= 0) {
            regex.append('\\');
        }
        regex.append(value);
    }

    private static int unescapedIndexOf(String value, char target, int start) {
        for (int index = start; index < value.length(); index++) {
            if (value.charAt(index) == target && (index == 0 || value.charAt(index - 1) != '\\')) {
                return index;
            }
        }
        return -1;
    }

    private static String normalizeTarget(String target) {
        String normalized = target.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.isEmpty() || normalized.startsWith("/") || normalized.equals("..")
                || normalized.startsWith("../") || normalized.contains("/../")
                || normalized.matches("^[A-Za-z]:/.*")) {
            return null;
        }
        return normalized;
    }

    private static String stripBom(String value) {
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private static int indentation(String value) {
        int result = 0;
        while (result < value.length() && value.charAt(result) == ' ') {
            result++;
        }
        return result;
    }

    private static ClaudeRuleEvaluation result(
            ClaudeRuleApplicability applicability,
            List<String> configured,
            List<String> matching,
            List<ClaudeDiagnostic> diagnostics) {
        return new ClaudeRuleEvaluation(applicability, configured, matching, diagnostics);
    }

    private static ClaudeDiagnostic diagnostic(
            String code, ClaudeDiagnosticSeverity severity, String message, int line, int column) {
        return new ClaudeDiagnostic(code, severity, message, line, column);
    }

    private static final class ExpansionBudget {
        private int count;
        private long characters;

        boolean accept(String pattern) {
            if (count + 1 > MAXIMUM_EXPANDED_PATTERNS
                    || characters + pattern.length() > MAXIMUM_EXPANDED_PATTERN_CHARACTERS) {
                return false;
            }
            count++;
            characters += pattern.length();
            return true;
        }
    }
}
