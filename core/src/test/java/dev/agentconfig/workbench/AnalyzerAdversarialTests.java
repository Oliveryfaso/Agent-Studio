package dev.agentconfig.workbench;

import dev.agentconfig.workbench.analyze.AnalysisCertainty;
import dev.agentconfig.workbench.analyze.AnalysisFindingType;
import dev.agentconfig.workbench.analyze.DirectiveAnalysis;
import dev.agentconfig.workbench.analyze.DirectiveAnalyzer;
import dev.agentconfig.workbench.analyze.DirectiveFindingClassification;
import dev.agentconfig.workbench.analyze.DirectiveFindingType;
import dev.agentconfig.workbench.analyze.DirectivePolarity;
import dev.agentconfig.workbench.analyze.DirectiveSourceInput;
import dev.agentconfig.workbench.analyze.DirectiveSourceMetadata;
import dev.agentconfig.workbench.analyze.InstructionAnalysisEngine;
import dev.agentconfig.workbench.analyze.InstructionAnalysisReport;
import dev.agentconfig.workbench.context.ContextCompileRequest;
import dev.agentconfig.workbench.context.EffectiveInstructionCompiler;
import dev.agentconfig.workbench.ir.IrResolutionStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class AnalyzerAdversarialTests {
    private int passed;

    public static void main(String[] args) throws Exception {
        new AnalyzerAdversarialTests().runAll();
    }

    private void runAll() throws Exception {
        run("shadowed source cannot overlap active analysis", this::shadowedSourceDoesNotOverlap);
        run("no-match Claude rule cannot conflict with active scope", this::noMatchRuleDoesNotOverlap);
        run("case punctuation and Chinese modality normalize predictably", this::normalizationFixtures);
        run("negative substrings do not imply prohibition", this::negativeSubstringFixtures);
        run("quotes examples fences and comments suppress modality", this::quotedAndMarkdownExamples);
        run("similar content is not reported as equal", this::similarIsNotEqual);
        run("finding certainty stays separated", this::certaintySeparation);
        run("heuristics alone preserve COMPLETE", this::heuristicsPreserveComplete);
        System.out.printf("Analyzer adversarial tests: %d passed%n", passed);
    }

    private void shadowedSourceDoesNotOverlap() throws Exception {
        withTempDirectory(root -> {
            write(root, "AGENTS.md", "# Base\n- Use pnpm\n");
            write(root, "AGENTS.override.md", "# Override\n- Use pnpm\n");
            InstructionAnalysisReport report = analyzeCodex(root, root);
            check(report.findings().stream().noneMatch(finding ->
                    finding.type() == AnalysisFindingType.NORMALIZED_DIRECTIVE_DUPLICATE),
                    "shadowed directive participated in analysis");
            equal(1, report.summary().activeSourceCount(), "active source count");
        });
    }

    private void noMatchRuleDoesNotOverlap() throws Exception {
        withTempDirectory(root -> {
            write(root, "CLAUDE.md", "- Always use tabs\n");
            write(root, ".claude/rules/docs.md",
                    "---\npaths: [\"docs/**\"]\n---\n- Never use tabs\n");
            InstructionAnalysisReport report = analyzeClaude(root, Path.of("src/Main.java"));
            check(report.findings().stream().noneMatch(finding ->
                    finding.type() == AnalysisFindingType.DIRECT_POLARITY_CONFLICT),
                    "no-match rule participated in conflict analysis");
            equal(1, report.instructionIr().directives().size(), "only active directive count");
        });
    }

    private void normalizationFixtures() {
        DirectiveAnalysis result = directives(
                source("one", 1, "- Use PNPM!\n- 必须使用 Java。"),
                source("two", 2, "- use   pnpm\n- 必须使用 Java"));
        equal(2L, result.findings().stream().filter(finding ->
                finding.type() == DirectiveFindingType.NORMALIZED_DIRECTIVE_DUPLICATE).count(),
                "normalized duplicate count");
        check(result.units().stream().allMatch(unit -> unit.polarity() == DirectivePolarity.REQUIRE),
                "required polarities");
        check(result.findings().stream().allMatch(finding ->
                finding.classification() == DirectiveFindingClassification.HEURISTIC_CANDIDATE),
                "normalized findings are heuristic");
    }

    private void negativeSubstringFixtures() {
        DirectiveAnalysis result = directives(source("words", 1,
                "- nevermore is a fictional word\n"
                        + "- mustard is a condiment\n"
                        + "- 这个迁移不得不使用 Java\n"));
        equal(List.of(
                        DirectivePolarity.NEUTRAL,
                        DirectivePolarity.NEUTRAL,
                        DirectivePolarity.REQUIRE),
                result.units().stream().map(unit -> unit.polarity()).toList(),
                "substring polarities");
        check(result.findings().isEmpty(), "substrings produced findings");
    }

    private void quotedAndMarkdownExamples() {
        DirectiveAnalysis result = directives(source("examples", 1,
                "- \"Never use tabs\" is an example\n"
                        + "- The phrase ‘必须使用 pnpm’ is quoted\n"
                        + "- Example: do not deploy\n"
                        + "- `Must use npm` is inline code\n"
                        + "<!-- - Never expose comments -->\n"
                        + "```md\n- Must use fenced examples\n```\n"));
        equal(4, result.units().size(), "visible list item count");
        equal(List.of(DirectivePolarity.NEUTRAL, DirectivePolarity.NEUTRAL,
                        DirectivePolarity.NEUTRAL, DirectivePolarity.NEUTRAL),
                result.units().stream().map(unit -> unit.polarity()).toList(),
                "quoted/example polarities");
        check(result.findings().isEmpty(), "examples produced findings");
    }

    private void similarIsNotEqual() {
        DirectiveAnalysis result = directives(
                source("one", 1, "- Must run unit tests"),
                source("two", 2, "- Must run all tests"),
                source("three", 3, "- Must run tests before commit"));
        check(result.findings().isEmpty(), "similar directives were treated as equal");
    }

    private void certaintySeparation() throws Exception {
        withTempDirectory(root -> {
            Path nested = Files.createDirectories(root.resolve("nested"));
            write(root, "AGENTS.md", "- Use pnpm\n");
            write(root, "nested/AGENTS.md", "- Use pnpm\n");
            InstructionAnalysisReport report = analyzeCodex(root, nested);
            check(report.findings().stream().anyMatch(finding ->
                            finding.type() == AnalysisFindingType.EXACT_EFFECTIVE_DUPLICATE
                                    && finding.certainty() == AnalysisCertainty.DETERMINISTIC),
                    "deterministic payload duplicate missing");
            check(report.findings().stream().anyMatch(finding ->
                            finding.type() == AnalysisFindingType.NORMALIZED_DIRECTIVE_DUPLICATE
                                    && finding.certainty() == AnalysisCertainty.HEURISTIC_CANDIDATE),
                    "heuristic directive duplicate missing");
        });
    }

    private void heuristicsPreserveComplete() throws Exception {
        withTempDirectory(root -> {
            Path nested = Files.createDirectories(root.resolve("nested"));
            write(root, "AGENTS.md", "# Root\n- Use pnpm.\n");
            write(root, "nested/AGENTS.md", "# Nested\n- use  pnpm\n");
            InstructionAnalysisReport report = analyzeCodex(root, nested);
            equal(IrResolutionStatus.COMPLETE, report.instructionIr().resolutionStatus(),
                    "heuristic finding must not reduce completeness");
            equal(0, report.summary().deterministicFindingCount(), "deterministic count");
            check(report.summary().heuristicFindingCount() > 0, "heuristic finding missing");
        });
    }

    private static DirectiveAnalysis directives(DirectiveSourceInput... sources) {
        return new DirectiveAnalyzer().analyze(List.of(sources));
    }

    private static DirectiveSourceInput source(String id, int order, String markdown) {
        return new DirectiveSourceInput(id, markdown, new DirectiveSourceMetadata(order, 1));
    }

    private static InstructionAnalysisReport analyzeCodex(Path root, Path cwd) throws IOException {
        return new InstructionAnalysisEngine().analyze(
                new EffectiveInstructionCompiler().compile("codex", root, cwd));
    }

    private static InstructionAnalysisReport analyzeClaude(Path root, Path target) throws IOException {
        var request = new ContextCompileRequest(
                "claude-code", root, root, Optional.empty(), Optional.of(target));
        return new InstructionAnalysisEngine().analyze(
                new EffectiveInstructionCompiler().compile(request));
    }

    private static void write(Path root, String relative, String content) throws IOException {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private void withTempDirectory(ThrowingConsumer<Path> test) throws Exception {
        Path root = Files.createTempDirectory("acw-analyzer-adversarial-");
        try {
            test.accept(root);
        } finally {
            deleteOwnedTempTree(root);
        }
    }

    private static void deleteOwnedTempTree(Path root) throws IOException {
        if (!root.getFileName().toString().startsWith("acw-analyzer-adversarial-")
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing to clean unexpected test path: " + root);
        }
        try (var paths = Files.walk(root)) {
            List<Path> owned = new ArrayList<>(paths.toList());
            owned.sort(Comparator.reverseOrder());
            for (Path path : owned) {
                Files.deleteIfExists(path);
            }
        }
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

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T value) throws Exception;
    }
}
