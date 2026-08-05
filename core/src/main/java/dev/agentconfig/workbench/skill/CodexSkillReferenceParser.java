package dev.agentconfig.workbench.skill;

import static dev.agentconfig.workbench.skill.CodexSkillInventory.FindingCode.MISSING_REFERENCE_TARGET;
import static dev.agentconfig.workbench.skill.CodexSkillInventory.FindingCode.REFERENCE_LIMIT_REACHED;
import static dev.agentconfig.workbench.skill.CodexSkillInventory.FindingCode.UNSAFE_LOCAL_REFERENCE;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Bounded parser for the project Skill inventory's deliberately small inline-reference profile.
 * It does not implement full CommonMark and never opens a reference target.
 */
final class CodexSkillReferenceParser {
    private static final int MAX_REFERENCES = 128;
    private static final int MAX_DESTINATION_CHARS = 1024;
    private static final Pattern SCHEME =
            Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");
    private static final Pattern WINDOWS_DRIVE = Pattern.compile("^[A-Za-z]:.*");
    private static final Set<String> IGNORED_SCHEMES = Set.of("http", "https", "mailto");

    private CodexSkillReferenceParser() {}

    static Result parse(
            String[] lines,
            int bodyStart,
            String packageLogicalPath,
            String skillLogicalPath,
            Set<String> supportingLogicalPaths,
            boolean supportingComplete) {
        List<CodexSkillInventory.Reference> references = new ArrayList<>();
        List<CodexSkillInventory.Finding> findings = new ArrayList<>();
        Set<ReferenceKey> seen = new HashSet<>();
        Fence fence = null;
        boolean invalid = false;
        boolean partial = false;
        boolean unsafeReported = false;
        boolean missingReported = false;
        boolean limitReported = false;
        boolean htmlComment = false;

        for (int index = Math.max(0, bodyStart); index < lines.length; index++) {
            String line = lines[index];
            FenceMarker marker = htmlComment ? null : fenceMarker(line);
            if (fence != null) {
                if (marker != null && marker.character == fence.character
                        && marker.length >= fence.length && marker.closingEligible) {
                    fence = null;
                }
                continue;
            }
            if (marker != null) {
                fence = new Fence(marker.character, marker.length);
                continue;
            }

            boolean[] code = inlineCodeMask(line);
            CommentMask comment = htmlCommentMask(line, htmlComment, code);
            htmlComment = comment.openAtEnd;
            for (int closeLabel = 0; closeLabel + 1 < line.length(); closeLabel++) {
                if (line.charAt(closeLabel) != ']' || line.charAt(closeLabel + 1) != '('
                        || code[closeLabel] || code[closeLabel + 1]) {
                    continue;
                }
                if (comment.mask[closeLabel] || comment.mask[closeLabel + 1]) {
                    continue;
                }
                int openLabel = findOpenLabel(line, code, closeLabel);
                if (openLabel < 0 || comment.mask[openLabel] || isEscaped(line, openLabel)) {
                    continue;
                }
                CodexSkillInventory.ReferenceKind kind = openLabel > 0
                                && line.charAt(openLabel - 1) == '!'
                                && !code[openLabel - 1]
                        ? CodexSkillInventory.ReferenceKind.IMAGE
                        : CodexSkillInventory.ReferenceKind.LINK;
                Destination destination = destination(line, closeLabel + 2);
                if (destination == null) {
                    continue;
                }
                if (destination.value.length() > MAX_DESTINATION_CHARS) {
                    partial = true;
                    if (!limitReported) {
                        findings.add(finding(CodexSkillInventory.Severity.ERROR,
                                REFERENCE_LIMIT_REACHED, skillLogicalPath,
                                "Skill references exceed a bounded parser limit"));
                        limitReported = true;
                    }
                    closeLabel = Math.max(closeLabel, destination.closingParenthesis);
                    continue;
                }

                Classification classification = classify(
                        destination.value, packageLogicalPath, skillLogicalPath);
                if (classification.kind == ClassificationKind.IGNORED) {
                    closeLabel = Math.max(closeLabel, destination.closingParenthesis);
                    continue;
                }
                if (classification.kind == ClassificationKind.UNSAFE) {
                    invalid = true;
                    if (!unsafeReported) {
                        findings.add(finding(CodexSkillInventory.Severity.BLOCKING,
                                UNSAFE_LOCAL_REFERENCE, skillLogicalPath,
                                "SKILL.md contains a local reference outside the safe package profile"));
                        unsafeReported = true;
                    }
                    closeLabel = Math.max(closeLabel, destination.closingParenthesis);
                    continue;
                }
                ReferenceKey key = new ReferenceKey(
                        classification.targetLogicalPath, index + 1, openLabel + 1, kind);
                if (seen.add(key)) {
                    if (references.size() >= MAX_REFERENCES) {
                        partial = true;
                        if (!limitReported) {
                            findings.add(finding(CodexSkillInventory.Severity.ERROR,
                                    REFERENCE_LIMIT_REACHED, skillLogicalPath,
                                    "Skill references exceed a bounded parser limit"));
                            limitReported = true;
                        }
                    } else {
                        CodexSkillInventory.ReferenceResolution resolution;
                        if (supportingLogicalPaths.contains(classification.targetLogicalPath)) {
                            resolution = CodexSkillInventory.ReferenceResolution.RESOLVED;
                        } else if (supportingComplete) {
                            resolution = CodexSkillInventory.ReferenceResolution.MISSING;
                            invalid = true;
                            if (!missingReported) {
                                findings.add(finding(CodexSkillInventory.Severity.ERROR,
                                        MISSING_REFERENCE_TARGET, skillLogicalPath,
                                        "One or more package-local Skill reference targets were not found"));
                                missingReported = true;
                            }
                        } else {
                            resolution = CodexSkillInventory.ReferenceResolution.UNKNOWN;
                        }
                        references.add(new CodexSkillInventory.Reference(
                                skillLogicalPath,
                                resolution == CodexSkillInventory.ReferenceResolution.RESOLVED
                                        ? classification.targetLogicalPath : "",
                                index + 1,
                                openLabel + 1,
                                kind,
                                resolution));
                    }
                }
                closeLabel = Math.max(closeLabel, destination.closingParenthesis);
            }
        }
        references.sort(CodexSkillInventory.referenceOrder());
        findings.sort(CodexSkillInventory.findingOrder());
        return new Result(references, findings, invalid, partial);
    }

    private static int findOpenLabel(String line, boolean[] code, int closeLabel) {
        for (int index = closeLabel - 1; index >= 0; index--) {
            if (code[index]) {
                continue;
            }
            if (line.charAt(index) == ']') {
                return -1;
            }
            if (line.charAt(index) == '[') {
                return index;
            }
        }
        return -1;
    }

    private static boolean isEscaped(String line, int index) {
        int slashes = 0;
        for (int cursor = index - 1; cursor >= 0 && line.charAt(cursor) == '\\'; cursor--) {
            slashes++;
        }
        return slashes % 2 == 1;
    }

    private static CommentMask htmlCommentMask(
            String line, boolean openAtStart, boolean[] inlineCode) {
        boolean[] mask = new boolean[line.length()];
        boolean open = openAtStart;
        int index = 0;
        while (index < line.length()) {
            if (!open && !inlineCode[index] && line.startsWith("<!--", index)) {
                open = true;
            }
            if (open) {
                mask[index] = true;
                if (line.startsWith("-->", index)) {
                    for (int offset = 0; offset < 3 && index + offset < line.length(); offset++) {
                        mask[index + offset] = true;
                    }
                    open = false;
                    index += 3;
                    continue;
                }
            }
            index++;
        }
        return new CommentMask(mask, open);
    }

    private static Destination destination(String line, int start) {
        int cursor = start;
        while (cursor < line.length() && Character.isWhitespace(line.charAt(cursor))) {
            cursor++;
        }
        if (cursor >= line.length()) {
            return null;
        }
        if (line.charAt(cursor) == '<') {
            int end = cursor + 1;
            while (end < line.length() && line.charAt(end) != '>') {
                end++;
            }
            if (end >= line.length()) {
                return null;
            }
            int close = closingParenthesis(line, end + 1);
            return close < 0 ? null : new Destination(line.substring(cursor + 1, end), close);
        }

        int valueStart = cursor;
        int nested = 0;
        boolean escaped = false;
        while (cursor < line.length()) {
            char character = line.charAt(cursor);
            if (escaped) {
                escaped = false;
                cursor++;
                continue;
            }
            if (character == '\\') {
                escaped = true;
                cursor++;
                continue;
            }
            if (character == '(') {
                nested++;
            } else if (character == ')') {
                if (nested == 0) {
                    return new Destination(line.substring(valueStart, cursor), cursor);
                }
                nested--;
            } else if (Character.isWhitespace(character) && nested == 0) {
                int close = closingParenthesis(line, cursor);
                return close < 0 ? null : new Destination(line.substring(valueStart, cursor), close);
            }
            cursor++;
        }
        return null;
    }

    private static int closingParenthesis(String line, int start) {
        boolean quoted = false;
        char quote = 0;
        for (int index = start; index < line.length(); index++) {
            char character = line.charAt(index);
            if ((character == '\'' || character == '"') && (index == 0 || line.charAt(index - 1) != '\\')) {
                if (!quoted) {
                    quoted = true;
                    quote = character;
                } else if (quote == character) {
                    quoted = false;
                }
            } else if (character == ')' && !quoted) {
                return index;
            }
        }
        return -1;
    }

    private static Classification classify(
            String raw, String packageLogicalPath, String skillLogicalPath) {
        String value = raw.strip();
        if (value.isEmpty() || value.startsWith("#")) {
            return Classification.ignored();
        }
        java.util.regex.Matcher scheme = SCHEME.matcher(value);
        if (scheme.find()) {
            String name = value.substring(0, value.indexOf(':')).toLowerCase(Locale.ROOT);
            return IGNORED_SCHEMES.contains(name)
                    ? Classification.ignored() : Classification.unsafe();
        }
        if (value.startsWith("/") || value.startsWith("\\") || value.startsWith("//")
                || WINDOWS_DRIVE.matcher(value).matches() || value.indexOf('\\') >= 0
                || value.indexOf('\0') >= 0) {
            return Classification.unsafe();
        }
        int fragment = value.indexOf('#');
        int query = value.indexOf('?');
        int suffix = fragment < 0 ? query : query < 0 ? fragment : Math.min(fragment, query);
        String pathPart = (suffix < 0 ? value : value.substring(0, suffix)).strip();
        if (pathPart.isEmpty()) {
            return Classification.ignored();
        }
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : pathPart.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (segments.isEmpty()) {
                    return Classification.unsafe();
                }
                segments.removeLast();
                continue;
            }
            if (!portableSegment(segment)) {
                return Classification.unsafe();
            }
            segments.addLast(segment);
        }
        if (segments.isEmpty()) {
            return Classification.ignored();
        }
        String target = packageLogicalPath + "/" + String.join("/", segments);
        return target.equals(skillLogicalPath)
                ? Classification.ignored() : Classification.local(target);
    }

    private static boolean portableSegment(String segment) {
        if (segment.endsWith(" ") || segment.endsWith(".")) {
            return false;
        }
        for (int index = 0; index < segment.length(); index++) {
            char character = segment.charAt(index);
            if (character < 0x20 || ":*\"<>|".indexOf(character) >= 0) {
                return false;
            }
        }
        String stem = segment.toUpperCase(Locale.ROOT).split("\\.", 2)[0];
        if (Set.of("CON", "PRN", "AUX", "NUL").contains(stem)) {
            return false;
        }
        return !stem.matches("COM[1-9]|LPT[1-9]");
    }

    private static FenceMarker fenceMarker(String line) {
        String candidate = line.stripLeading();
        if (line.length() - candidate.length() > 3 || candidate.length() < 3) {
            return null;
        }
        char character = candidate.charAt(0);
        if (character != '`' && character != '~') {
            return null;
        }
        int length = 0;
        while (length < candidate.length() && candidate.charAt(length) == character) {
            length++;
        }
        boolean closingEligible = candidate.substring(length).strip().isEmpty();
        return length >= 3 ? new FenceMarker(character, length, closingEligible) : null;
    }

    private static boolean[] inlineCodeMask(String line) {
        boolean[] result = new boolean[line.length()];
        int index = 0;
        while (index < line.length()) {
            if (line.charAt(index) != '`') {
                index++;
                continue;
            }
            int run = 1;
            while (index + run < line.length() && line.charAt(index + run) == '`') {
                run++;
            }
            int close = findBacktickRun(line, index + run, run);
            int end = close < 0 ? line.length() : close + run;
            for (int masked = index; masked < end; masked++) {
                result[masked] = true;
            }
            index = end;
        }
        return result;
    }

    private static int findBacktickRun(String line, int start, int length) {
        for (int index = start; index + length <= line.length(); index++) {
            boolean matches = true;
            for (int offset = 0; offset < length; offset++) {
                if (line.charAt(index + offset) != '`') {
                    matches = false;
                    break;
                }
            }
            if (matches && (index + length == line.length()
                    || line.charAt(index + length) != '`')) {
                return index;
            }
        }
        return -1;
    }

    private static CodexSkillInventory.Finding finding(
            CodexSkillInventory.Severity severity,
            CodexSkillInventory.FindingCode code,
            String path,
            String summary) {
        return new CodexSkillInventory.Finding(severity, code, path, summary);
    }

    record Result(
            List<CodexSkillInventory.Reference> references,
            List<CodexSkillInventory.Finding> findings,
            boolean invalid,
            boolean partial) {
        Result {
            references = List.copyOf(references);
            findings = List.copyOf(findings);
        }
    }

    private enum ClassificationKind { IGNORED, UNSAFE, LOCAL }

    private record Classification(ClassificationKind kind, String targetLogicalPath) {
        private static Classification ignored() {
            return new Classification(ClassificationKind.IGNORED, "");
        }

        private static Classification unsafe() {
            return new Classification(ClassificationKind.UNSAFE, "");
        }

        private static Classification local(String target) {
            return new Classification(ClassificationKind.LOCAL, target);
        }
    }

    private record Destination(String value, int closingParenthesis) {}

    private record ReferenceKey(
            String targetLogicalPath,
            int line,
            int column,
            CodexSkillInventory.ReferenceKind kind) {}

    private record Fence(char character, int length) {}

    private record FenceMarker(char character, int length, boolean closingEligible) {}

    private record CommentMask(boolean[] mask, boolean openAtEnd) {}
}
