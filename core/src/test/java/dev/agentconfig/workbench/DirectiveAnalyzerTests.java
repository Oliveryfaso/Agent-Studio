package dev.agentconfig.workbench;

import dev.agentconfig.workbench.analyze.DirectiveAnalysis;
import dev.agentconfig.workbench.analyze.DirectiveAnalyzer;
import dev.agentconfig.workbench.analyze.DirectiveAnalyzerLimits;
import dev.agentconfig.workbench.analyze.DirectiveFindingClassification;
import dev.agentconfig.workbench.analyze.DirectiveFindingType;
import dev.agentconfig.workbench.analyze.DirectivePolarity;
import dev.agentconfig.workbench.analyze.DirectiveSourceInput;
import dev.agentconfig.workbench.analyze.DirectiveSourceMetadata;
import dev.agentconfig.workbench.analyze.DirectiveUnit;
import java.util.List;
import java.util.Objects;

public final class DirectiveAnalyzerTests {
    private int passed;

    public static void main(String[] args) throws Exception {
        new DirectiveAnalyzerTests().runAll();
    }

    private void runAll() throws Exception {
        run("list directives are extracted with redacted metadata", this::listItemsAreExtracted);
        run("normalized duplicates are deterministic to reproduce", this::normalizedDuplicatesAreReproducible);
        run("English polarity conflict is a heuristic candidate", this::englishConflict);
        run("Chinese polarity conflict is a heuristic candidate", this::chineseConflict);
        run("code comments and prose do not create directives", this::falsePositiveSyntaxIsIgnored);
        run("keyword substrings and examples stay neutral", this::falsePositiveWordsStayNeutral);
        run("source order and starting lines are deterministic", this::orderingAndStartingLine);
        run("analysis limits are explicit", this::limitsAreExplicit);
        run("duplicate source ids are rejected", this::duplicateSourceIdsAreRejected);
        System.out.printf("Directive analyzer tests: %d passed%n", passed);
    }

    private void listItemsAreExtracted() {
        DirectiveAnalysis result = analyze("source-a", 1, 1,
                "# Rules\n"
                        + "- Must run tests\n"
                        + "1. Never commit secrets\n"
                        + "  * background information\n"
                        + "- [ ] Use pnpm\n"
                        + "- `must not count`\n"
                        + "- <!-- empty -->\n");
        equal(4, result.units().size(), "unit count");
        equal(List.of(
                        DirectivePolarity.REQUIRE,
                        DirectivePolarity.PROHIBIT,
                        DirectivePolarity.NEUTRAL,
                        DirectivePolarity.REQUIRE),
                result.units().stream().map(DirectiveUnit::polarity).toList(),
                "polarities");
        DirectiveUnit first = result.units().get(0);
        equal(2, first.line(), "line");
        check(first.id().startsWith("du_"), "stable id prefix");
        equal(64, first.normalizedSha256().length(), "normalized hash size");
        equal(64, first.subjectHash().length(), "subject hash size");
        check(!first.toString().contains("run tests"), "unit must not retain source text");
    }

    private void normalizedDuplicatesAreReproducible() {
        DirectiveAnalyzer analyzer = new DirectiveAnalyzer();
        List<DirectiveSourceInput> sources = List.of(
                source("later", 2, 1, "- use   pnpm"),
                source("first", 1, 1, "- Use pnpm."));
        DirectiveAnalysis first = analyzer.analyze(sources);
        DirectiveAnalysis second = analyzer.analyze(List.of(sources.get(1), sources.get(0)));
        equal(first, second, "analysis must not depend on caller list order");
        equal(1, first.findings().size(), "finding count");
        equal(DirectiveFindingType.NORMALIZED_DIRECTIVE_DUPLICATE,
                first.findings().get(0).type(), "type");
        equal(DirectiveFindingClassification.HEURISTIC_CANDIDATE,
                first.findings().get(0).classification(), "classification");
        equal(List.of("first", "later"), first.findings().get(0).references().stream()
                .map(reference -> reference.sourceId()).toList(), "reference order");
    }

    private void englishConflict() {
        DirectiveAnalysis result = new DirectiveAnalyzer().analyze(List.of(
                source("team", 1, 10, "- Always use tabs"),
                source("local", 2, 20, "- Never use tabs")));
        equal(1, result.findings().size(), "finding count");
        equal(DirectiveFindingType.DIRECT_POLARITY_CONFLICT,
                result.findings().get(0).type(), "type");
        equal(DirectiveFindingClassification.HEURISTIC_CANDIDATE,
                result.findings().get(0).classification(), "classification");
        equal(List.of(10, 20), result.findings().get(0).references().stream()
                .map(reference -> reference.line()).toList(), "lines");
    }

    private void chineseConflict() {
        DirectiveAnalysis result = new DirectiveAnalyzer().analyze(List.of(
                source("root", 1, 1, "- 必须使用 pnpm"),
                source("nested", 2, 8, "- 不得使用 pnpm")));
        equal(List.of(DirectivePolarity.REQUIRE, DirectivePolarity.PROHIBIT),
                result.units().stream().map(DirectiveUnit::polarity).toList(), "polarities");
        equal(DirectiveFindingType.DIRECT_POLARITY_CONFLICT,
                result.findings().get(0).type(), "type");
    }

    private void falsePositiveSyntaxIsIgnored() {
        String markdown = "Never use prose is not a list item.\n"
                + "<!--\n- Never use comments\n-->\n"
                + "```md\n"
                + "- Must use fenced code\n"
                + "```not-a-close\n"
                + "- Never use still-fenced code\n"
                + "```\n"
                + "- `Never use inline code`\n"
                + "> - Must use quoted examples\n"
                + "- ordinary item\n";
        DirectiveAnalysis result = analyze("syntax", 1, 1, markdown);
        equal(1, result.units().size(), "only ordinary item extracted");
        equal(DirectivePolarity.NEUTRAL, result.units().get(0).polarity(), "ordinary polarity");
        check(result.findings().isEmpty(), "no findings");
    }

    private void falsePositiveWordsStayNeutral() {
        DirectiveAnalysis result = analyze("words", 1, 1,
                "- mustard is yellow\n"
                        + "- unused imports are checked\n"
                        + "- Example: never use this phrase\n"
                        + "- 例如：必须使用这个短语\n"
                        + "- You can use either formatter\n");
        equal(5, result.units().size(), "unit count");
        check(result.units().stream().allMatch(unit -> unit.polarity() == DirectivePolarity.NEUTRAL),
                "all examples must stay neutral");
        check(result.findings().isEmpty(), "no false findings");
    }

    private void orderingAndStartingLine() {
        DirectiveAnalysis result = new DirectiveAnalyzer().analyze(List.of(
                source("z-source", 1, 30, "- Must do z"),
                source("a-source", 1, 10, "- Must do a"),
                source("first", 0, 50, "- Must do first")));
        equal(List.of("first", "a-source", "z-source"),
                result.units().stream().map(DirectiveUnit::sourceId).toList(), "ordered ids");
        equal(List.of(50, 10, 30),
                result.units().stream().map(DirectiveUnit::line).toList(), "starting lines");
    }

    private void limitsAreExplicit() {
        DirectiveAnalyzer analyzer = new DirectiveAnalyzer(new DirectiveAnalyzerLimits(60, 10, 12, 1));
        DirectiveAnalysis result = analyzer.analyze(List.of(source(
                "bounded", 1, 1,
                "- Must do one\n- Must do a very long thing\n- Must do two\n- Must do three")));
        equal(1, result.units().size(), "unit limit");
        check(result.notices().stream().anyMatch(notice -> notice.code().equals("INPUT_TRUNCATED")),
                "input truncation notice");
        check(result.notices().stream().anyMatch(notice -> notice.code().equals("ITEM_TOO_LONG")),
                "long item notice");
        check(result.notices().stream().anyMatch(notice -> notice.code().equals("DIRECTIVE_LIMIT_REACHED")),
                "unit limit notice");

        try {
            new DirectiveAnalyzer(new DirectiveAnalyzerLimits(10, 10, 10, 10, 1)).analyze(List.of(
                    source("one", 1, 1, ""),
                    source("two", 2, 1, "")));
            throw new AssertionError("Expected source count rejection");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("Source count"), "source limit diagnostic");
        }
    }

    private void duplicateSourceIdsAreRejected() {
        DirectiveAnalyzer analyzer = new DirectiveAnalyzer();
        try {
            analyzer.analyze(List.of(
                    source("same", 1, 1, "- one"),
                    source("same", 2, 1, "- two")));
            throw new AssertionError("Expected duplicate source id rejection");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("Duplicate sourceId"), "diagnostic");
        }
    }

    private static DirectiveAnalysis analyze(String sourceId, int order, int line, String markdown) {
        return new DirectiveAnalyzer().analyze(List.of(source(sourceId, order, line, markdown)));
    }

    private static DirectiveSourceInput source(String sourceId, int order, int line, String markdown) {
        return new DirectiveSourceInput(sourceId, markdown, new DirectiveSourceMetadata(order, line));
    }

    private void run(String name, ThrowingRunnable test) throws Exception {
        test.run();
        passed++;
        System.out.println("PASS " + name);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
