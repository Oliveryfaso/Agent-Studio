package dev.agentconfig.workbench.analyze;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministically extracts redacted directive metadata from Markdown list items.
 * The returned model never retains the source Markdown or normalized directive text.
 */
public final class DirectiveAnalyzer {
    private static final Pattern LIST_ITEM = Pattern.compile(
            "^[ \\t]{0,3}(?:[-+*]|[0-9]{1,9}[.)])[ \\t]+(.*)$");
    private static final Pattern FENCE = Pattern.compile("^[ \\t]{0,3}(`{3,}|~{3,}).*$");
    private static final Pattern TASK_MARKER = Pattern.compile("^\\[[ xX-]][ \\t]+(.*)$");
    private static final Pattern LINK = Pattern.compile("!?\\[([^]\\r\\n]+)]\\([^)]*\\)");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern NEGATIVE_ENGLISH = Pattern.compile(
            "(?i)(?:\\bdo[ \\t]+not\\b|\\bdon't\\b|\\bnever\\b|"
                    + "\\bmust[ \\t]+not\\b|\\bshall[ \\t]+not\\b)");
    private static final Pattern POSITIVE_MODAL_ENGLISH = Pattern.compile(
            "(?i)(?:\\bmust\\b|\\balways\\b)");
    private static final Pattern IMPERATIVE_USE_ENGLISH = Pattern.compile(
            "(?i)^(?:please[ \\t]+)?use\\b");
    private static final Pattern NON_NORMATIVE_PREFIX = Pattern.compile(
            "(?i)^(?:example|examples|for example|e\\.g\\.|示例|例如)(?:\\s|[:：])");
    private static final Pattern SUBJECT_MODAL_ENGLISH = Pattern.compile(
            "(?i)\\b(?:do[ \\t]+not|don't|never|must[ \\t]+not|shall[ \\t]+not|must|always)\\b");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern EDGE_PUNCTUATION = Pattern.compile(
            "^[\\p{Punct}，。；：！？、&&[^\"']]+|[\\p{Punct}，。；：！？、&&[^\"']]+$");

    private final DirectiveAnalyzerLimits limits;

    public DirectiveAnalyzer() {
        this(DirectiveAnalyzerLimits.DEFAULT);
    }

    public DirectiveAnalyzer(DirectiveAnalyzerLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public DirectiveAnalysis analyze(List<DirectiveSourceInput> sources) {
        Objects.requireNonNull(sources, "sources");
        if (sources.size() > limits.maximumSources()) {
            throw new IllegalArgumentException("Source count exceeds configured limit");
        }
        List<DirectiveSourceInput> orderedSources = new ArrayList<>(sources);
        Set<String> sourceIds = new HashSet<>();
        for (DirectiveSourceInput source : orderedSources) {
            Objects.requireNonNull(source, "source");
            if (!sourceIds.add(source.sourceId())) {
                throw new IllegalArgumentException("Duplicate sourceId: " + source.sourceId());
            }
        }
        orderedSources.sort(Comparator
                .comparingInt((DirectiveSourceInput value) -> value.metadata().sourceOrder())
                .thenComparing(DirectiveSourceInput::sourceId));

        List<DirectiveUnit> units = new ArrayList<>();
        List<DirectiveAnalysisNotice> notices = new ArrayList<>();
        for (DirectiveSourceInput source : orderedSources) {
            extract(source, units, notices);
            if (units.size() >= limits.maximumUnits()) {
                break;
            }
        }

        List<DirectiveFinding> findings = analyzeUnits(units);
        return new DirectiveAnalysis(units, findings, notices);
    }

    private void extract(
            DirectiveSourceInput source,
            List<DirectiveUnit> units,
            List<DirectiveAnalysisNotice> notices) {
        String markdown = boundedText(source, notices);
        String[] lines = markdown.split("\\R", -1);
        int lineCount = Math.min(lines.length, limits.maximumLinesPerSource());
        if (lines.length > lineCount) {
            notices.add(new DirectiveAnalysisNotice(
                    "LINE_LIMIT_REACHED",
                    source.sourceId(),
                    source.metadata().startingLine() + lineCount - 1));
        }

        FenceState fence = null;
        boolean inHtmlComment = false;
        for (int index = 0; index < lineCount; index++) {
            int sourceLine = source.metadata().startingLine() + index;
            CommentStrip stripped = stripHtmlComments(lines[index], inHtmlComment);
            inHtmlComment = stripped.inComment();
            String line = stripped.text();

            Matcher fenceMatcher = FENCE.matcher(line);
            if (fence != null) {
                if (fenceMatcher.matches()
                        && fenceMatcher.group(1).charAt(0) == fence.marker()
                        && fenceMatcher.group(1).length() >= fence.length()
                        && line.substring(fenceMatcher.end(1)).isBlank()) {
                    fence = null;
                }
                continue;
            }
            if (fenceMatcher.matches()) {
                String marker = fenceMatcher.group(1);
                fence = new FenceState(marker.charAt(0), marker.length());
                continue;
            }

            Matcher itemMatcher = LIST_ITEM.matcher(line);
            if (!itemMatcher.matches()) {
                continue;
            }
            String item = stripInlineCode(itemMatcher.group(1));
            Matcher taskMatcher = TASK_MARKER.matcher(item);
            if (taskMatcher.matches()) {
                item = taskMatcher.group(1);
            }
            item = plainMarkdown(item);
            if (item.isBlank()) {
                continue;
            }
            if (item.length() > limits.maximumItemCharacters()) {
                notices.add(new DirectiveAnalysisNotice("ITEM_TOO_LONG", source.sourceId(), sourceLine));
                continue;
            }
            if (units.size() >= limits.maximumUnits()) {
                notices.add(new DirectiveAnalysisNotice("DIRECTIVE_LIMIT_REACHED", source.sourceId(), sourceLine));
                return;
            }

            String normalized = normalize(item);
            if (normalized.isEmpty()) {
                continue;
            }
            DirectivePolarity polarity = polarity(normalized);
            String normalizedHash = sha256(normalized);
            String subject = normalizeSubject(normalized, polarity);
            String subjectHash = sha256(subject);
            String id = "du_" + sha256("directive-unit-v1\n"
                    + source.sourceId() + "\n" + sourceLine + "\n" + normalizedHash);
            units.add(new DirectiveUnit(
                    id,
                    source.sourceId(),
                    sourceLine,
                    polarity,
                    normalizedHash,
                    subjectHash));
        }
    }

    private String boundedText(
            DirectiveSourceInput source,
            List<DirectiveAnalysisNotice> notices) {
        String markdown = source.markdown();
        if (markdown.length() <= limits.maximumCharactersPerSource()) {
            return markdown;
        }
        int end = limits.maximumCharactersPerSource();
        if (end > 0 && Character.isHighSurrogate(markdown.charAt(end - 1))) {
            end--;
        }
        notices.add(new DirectiveAnalysisNotice(
                "INPUT_TRUNCATED", source.sourceId(), source.metadata().startingLine()));
        return markdown.substring(0, end);
    }

    private static List<DirectiveFinding> analyzeUnits(List<DirectiveUnit> units) {
        Map<String, List<DirectiveUnit>> exactGroups = new LinkedHashMap<>();
        Map<String, List<DirectiveUnit>> subjectGroups = new LinkedHashMap<>();
        for (DirectiveUnit unit : units) {
            exactGroups.computeIfAbsent(unit.normalizedSha256(), ignored -> new ArrayList<>()).add(unit);
            if (unit.polarity() != DirectivePolarity.NEUTRAL) {
                subjectGroups.computeIfAbsent(unit.subjectHash(), ignored -> new ArrayList<>()).add(unit);
            }
        }

        List<DirectiveFinding> findings = new ArrayList<>();
        exactGroups.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> findings.add(finding(
                        DirectiveFindingType.NORMALIZED_DIRECTIVE_DUPLICATE,
                        DirectiveFindingClassification.HEURISTIC_CANDIDATE,
                        entry.getValue().get(0).subjectHash(),
                        entry.getValue())));

        subjectGroups.entrySet().stream()
                .filter(entry -> hasDirectConflict(entry.getValue()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> findings.add(finding(
                        DirectiveFindingType.DIRECT_POLARITY_CONFLICT,
                        DirectiveFindingClassification.HEURISTIC_CANDIDATE,
                        entry.getKey(),
                        entry.getValue())));
        return findings;
    }

    private static boolean hasDirectConflict(List<DirectiveUnit> units) {
        boolean require = false;
        boolean prohibit = false;
        for (DirectiveUnit unit : units) {
            require |= unit.polarity() == DirectivePolarity.REQUIRE;
            prohibit |= unit.polarity() == DirectivePolarity.PROHIBIT;
        }
        return require && prohibit;
    }

    private static DirectiveFinding finding(
            DirectiveFindingType type,
            DirectiveFindingClassification classification,
            String subjectHash,
            List<DirectiveUnit> units) {
        List<DirectiveReference> references = units.stream()
                .map(unit -> new DirectiveReference(unit.sourceId(), unit.id(), unit.line()))
                .toList();
        StringBuilder identity = new StringBuilder(type.name()).append('\n').append(subjectHash);
        for (DirectiveReference reference : references) {
            identity.append('\n').append(reference.sourceId())
                    .append('\n').append(reference.unitId())
                    .append('\n').append(reference.line());
        }
        return new DirectiveFinding(
                "df_" + sha256(identity.toString()),
                type,
                classification,
                subjectHash,
                references);
    }

    private static DirectivePolarity polarity(String normalized) {
        String cues = stripQuotedSegments(normalized);
        if (NON_NORMATIVE_PREFIX.matcher(cues).find()) {
            return DirectivePolarity.NEUTRAL;
        }
        if (NEGATIVE_ENGLISH.matcher(cues).find()
                || containsChineseProhibition(cues)) {
            return DirectivePolarity.PROHIBIT;
        }
        if (POSITIVE_MODAL_ENGLISH.matcher(cues).find()
                || IMPERATIVE_USE_ENGLISH.matcher(cues).find()
                || containsAny(cues, "必须", "始终")
                || cues.contains("不得不")
                || cues.startsWith("使用")
                || cues.startsWith("请使用")) {
            return DirectivePolarity.REQUIRE;
        }
        return DirectivePolarity.NEUTRAL;
    }

    private static String normalizeSubject(String normalized, DirectivePolarity polarity) {
        if (polarity == DirectivePolarity.NEUTRAL) {
            return normalized;
        }
        String subject = SUBJECT_MODAL_ENGLISH.matcher(normalized).replaceAll(" ");
        subject = subject.replace("不得不", "必须")
                .replace("不要", "")
                .replace("不得", "")
                .replace("禁止", "")
                .replace("严禁", "")
                .replace("切勿", "")
                .replace("必须", "")
                .replace("始终", "");
        subject = subject.replaceFirst("^(?i:please)\\s+", "");
        subject = WHITESPACE.matcher(subject).replaceAll(" ").strip();
        subject = EDGE_PUNCTUATION.matcher(subject).replaceAll("").strip();
        return subject.isEmpty() ? normalized : subject;
    }

    private static boolean containsChineseProhibition(String value) {
        if (containsAny(value, "不要", "禁止", "严禁", "切勿")) {
            return true;
        }
        int index = value.indexOf("不得");
        while (index >= 0) {
            int following = index + "不得".length();
            if (following >= value.length() || value.charAt(following) != '不') {
                return true;
            }
            index = value.indexOf("不得", following);
        }
        return false;
    }

    private static String stripQuotedSegments(String value) {
        StringBuilder result = new StringBuilder(value);
        for (int index = 0; index < value.length(); index++) {
            char opener = value.charAt(index);
            char closer = switch (opener) {
                case '"' -> '"';
                case '“' -> '”';
                case '‘' -> '’';
                default -> 0;
            };
            if (closer == 0) {
                continue;
            }
            int end = value.indexOf(closer, index + 1);
            if (end < 0) {
                continue;
            }
            for (int position = index; position <= end; position++) {
                result.setCharAt(position, ' ');
            }
            index = end;
        }
        return result.toString().strip();
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String plainMarkdown(String value) {
        String result = LINK.matcher(value).replaceAll("$1");
        result = HTML_TAG.matcher(result).replaceAll(" ");
        return result.replace("**", "")
                .replace("__", "")
                .replace("~~", "")
                .replace("*", "")
                .replace("_", "");
    }

    private static String normalize(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        normalized = WHITESPACE.matcher(normalized).replaceAll(" ").strip();
        normalized = EDGE_PUNCTUATION.matcher(normalized).replaceAll("").strip();
        return normalized;
    }

    private static String stripInlineCode(String line) {
        StringBuilder result = new StringBuilder(line);
        int index = 0;
        while (index < line.length()) {
            if (line.charAt(index) != '`') {
                index++;
                continue;
            }
            int runEnd = index;
            while (runEnd < line.length() && line.charAt(runEnd) == '`') {
                runEnd++;
            }
            int runLength = runEnd - index;
            int close = findBacktickRun(line, runEnd, runLength);
            int blankEnd = close < 0 ? line.length() : close + runLength;
            for (int position = index; position < blankEnd; position++) {
                result.setCharAt(position, ' ');
            }
            index = blankEnd;
        }
        return result.toString();
    }

    private static int findBacktickRun(String line, int from, int length) {
        for (int index = from; index <= line.length() - length; index++) {
            if (line.charAt(index) != '`') {
                continue;
            }
            int end = index;
            while (end < line.length() && line.charAt(end) == '`') {
                end++;
            }
            if (end - index == length) {
                return index;
            }
            index = end - 1;
        }
        return -1;
    }

    private static CommentStrip stripHtmlComments(String line, boolean initialState) {
        StringBuilder result = new StringBuilder(line.length());
        boolean inComment = initialState;
        int index = 0;
        while (index < line.length()) {
            if (inComment) {
                int end = line.indexOf("-->", index);
                if (end < 0) {
                    break;
                }
                inComment = false;
                index = end + 3;
                continue;
            }
            int start = line.indexOf("<!--", index);
            if (start < 0) {
                result.append(line, index, line.length());
                break;
            }
            result.append(line, index, start);
            inComment = true;
            index = start + 4;
        }
        return new CommentStrip(result.toString(), inComment);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record FenceState(char marker, int length) {
    }

    private record CommentStrip(String text, boolean inComment) {
    }
}
