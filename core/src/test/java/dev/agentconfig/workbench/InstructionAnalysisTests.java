package dev.agentconfig.workbench;

import dev.agentconfig.workbench.analyze.AnalysisCertainty;
import dev.agentconfig.workbench.analyze.AnalysisFinding;
import dev.agentconfig.workbench.analyze.AnalysisFindingType;
import dev.agentconfig.workbench.analyze.InstructionAnalysisEngine;
import dev.agentconfig.workbench.analyze.InstructionAnalysisReport;
import dev.agentconfig.workbench.context.ContextCompileRequest;
import dev.agentconfig.workbench.context.EffectiveInstructionCompiler;
import dev.agentconfig.workbench.context.EffectiveInstructionContext;
import dev.agentconfig.workbench.ir.InstructionSource;
import dev.agentconfig.workbench.ir.ProvenanceKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class InstructionAnalysisTests {
    private int passed;

    public static void main(String[] args) throws Exception {
        new InstructionAnalysisTests().runAll();
    }

    private void runAll() throws Exception {
        run("effective payload duplicates are deterministic", this::effectivePayloadDuplicate);
        run("Codex truncation hashes only effective bytes", this::truncatedPayloadDuplicate);
        run("shadowed sources are excluded from duplicates", this::shadowedSourceExcluded);
        run("normalized directive duplicates remain heuristic", this::normalizedDuplicateIsHeuristic);
        run("English and Chinese polarity conflicts are candidates", this::polarityConflicts);
        run("fenced examples and comments are suppressed", this::examplesAreSuppressed);
        run("repeated Claude import keeps provenance without duplicate nodes", this::repeatedImportProvenance);
        run("external Claude imports degrade to a partial portable IR", this::externalImportIsPartial);
        System.out.printf("Instruction analysis tests: %d passed%n", passed);
    }

    private void effectivePayloadDuplicate() throws Exception {
        withTempDirectory(root -> {
            Path nested = Files.createDirectories(root.resolve("service"));
            write(root, "AGENTS.md", "- Must run tests\n");
            write(root, "service/AGENTS.md", "- Must run tests\n");
            InstructionAnalysisReport report = analyze("codex", root, nested);

            AnalysisFinding finding = onlyFinding(report, AnalysisFindingType.EXACT_EFFECTIVE_DUPLICATE);
            equal(AnalysisCertainty.DETERMINISTIC, finding.certainty(), "certainty");
            equal(2, finding.references().size(), "source references");
            equal(1, report.summary().deterministicFindingCount(), "deterministic count");
        });
    }

    private void truncatedPayloadDuplicate() throws Exception {
        withTempDirectory(base -> {
            Path root = Files.createDirectory(base.resolve("workspace"));
            Path nested = Files.createDirectory(root.resolve("nested"));
            write(root, "AGENTS.md", "same");
            write(root, "nested/AGENTS.md", "sameDIFFERENT");
            Path config = Files.writeString(base.resolve("codex.toml"),
                    "project_doc_max_bytes = 8\n", StandardCharsets.UTF_8);
            EffectiveInstructionContext context = new EffectiveInstructionCompiler().compile(
                    new ContextCompileRequest("codex", root, nested,
                            Optional.of(config), Optional.empty()));
            InstructionAnalysisReport report = new InstructionAnalysisEngine().analyze(context);

            AnalysisFinding finding = onlyFinding(report, AnalysisFindingType.EXACT_EFFECTIVE_DUPLICATE);
            List<InstructionSource> referenced = report.instructionIr().sources().stream()
                    .filter(source -> finding.references().stream().anyMatch(
                            reference -> reference.sourceId().equals(source.identity().sourceId())))
                    .toList();
            equal(2, referenced.size(), "referenced sources");
            equal(List.of(4L, 4L), referenced.stream().map(InstructionSource::includedBytes).toList(),
                    "effective byte sizes");
            equal(2L, referenced.stream().map(InstructionSource::revisionSha256).distinct().count(),
                    "full revisions must differ");
            equal(1L, referenced.stream().map(InstructionSource::effectiveSha256).distinct().count(),
                    "effective hashes must match");
        });
    }

    private void shadowedSourceExcluded() throws Exception {
        withTempDirectory(root -> {
            write(root, "AGENTS.md", "identical");
            write(root, "AGENTS.override.md", "identical");
            InstructionAnalysisReport report = analyze("codex", root, root);

            check(report.findings().stream().noneMatch(
                    finding -> finding.type() == AnalysisFindingType.EXACT_EFFECTIVE_DUPLICATE),
                    "shadowed source created a duplicate");
            check(report.instructionIr().provenance().stream().anyMatch(
                    edge -> edge.kind() == ProvenanceKind.SHADOWS), "shadow edge missing");
        });
    }

    private void normalizedDuplicateIsHeuristic() throws Exception {
        withTempDirectory(root -> {
            Path nested = Files.createDirectory(root.resolve("nested"));
            write(root, "AGENTS.md", "# Team\n- Use pnpm.\n");
            write(root, "nested/AGENTS.md", "# Local\n- use   pnpm\n");
            InstructionAnalysisReport report = analyze("codex", root, nested);

            AnalysisFinding finding = onlyFinding(
                    report, AnalysisFindingType.NORMALIZED_DIRECTIVE_DUPLICATE);
            equal(AnalysisCertainty.HEURISTIC_CANDIDATE, finding.certainty(), "certainty");
            equal(0, report.summary().deterministicFindingCount(), "deterministic count");
            equal(1, report.summary().heuristicFindingCount(), "heuristic count");
        });
    }

    private void polarityConflicts() throws Exception {
        withTempDirectory(base -> {
            Path english = Files.createDirectory(base.resolve("english"));
            Path englishNested = Files.createDirectory(english.resolve("nested"));
            write(english, "AGENTS.md", "- Always use tabs\n");
            write(english, "nested/AGENTS.md", "- Never use tabs\n");
            check(hasHeuristicConflict(analyze("codex", english, englishNested)),
                    "English conflict missing");

            Path chinese = Files.createDirectory(base.resolve("chinese"));
            Path chineseNested = Files.createDirectory(chinese.resolve("nested"));
            write(chinese, "AGENTS.md", "- 必须使用 pnpm\n");
            write(chinese, "nested/AGENTS.md", "- 不得使用 pnpm\n");
            check(hasHeuristicConflict(analyze("codex", chinese, chineseNested)),
                    "Chinese conflict missing");
        });
    }

    private void examplesAreSuppressed() throws Exception {
        withTempDirectory(root -> {
            write(root, "AGENTS.md", "<!-- - Never expose comments -->\n"
                    + "```md\n- Must use fenced examples\n```\n"
                    + "Prose must not be treated as a directive.\n"
                    + "- Example: never run this\n");
            InstructionAnalysisReport report = analyze("codex", root, root);
            check(report.findings().isEmpty(), "example produced a finding");
            equal(1, report.instructionIr().directives().size(), "only explicit example list is metadata");
        });
    }

    private void repeatedImportProvenance() throws Exception {
        withTempDirectory(root -> {
            write(root, "CLAUDE.md", "@docs/a.md\n@docs/b.md\n");
            write(root, "docs/a.md", "A parent imports @shared.md\n");
            write(root, "docs/b.md", "B parent also imports @shared.md\n");
            write(root, "docs/shared.md", "- Must run tests\n");
            InstructionAnalysisReport report = analyze("claude-code", root, root);

            long sharedNodes = report.instructionIr().sources().stream()
                    .filter(source -> source.logicalPath().equals("docs/shared.md")).count();
            equal(1L, sharedNodes, "shared source node count");
            String sharedId = report.instructionIr().sources().stream()
                    .filter(source -> source.logicalPath().equals("docs/shared.md"))
                    .findFirst().orElseThrow().identity().sourceId();
            long incomingImports = report.instructionIr().provenance().stream()
                    .filter(edge -> edge.kind() == ProvenanceKind.IMPORTS
                            && edge.to().id().equals(sharedId)).count();
            equal(2L, incomingImports, "shared import parent count");
            check(report.findings().stream().noneMatch(
                    finding -> finding.type() == AnalysisFindingType.EXACT_EFFECTIVE_DUPLICATE),
                    "repeated import became a duplicate");
        });
    }

    private void externalImportIsPartial() throws Exception {
        withTempDirectory(base -> {
            Path root = Files.createDirectory(base.resolve("workspace"));
            write(root, "CLAUDE.md", "@../outside.md\n");
            Files.writeString(base.resolve("outside.md"), "outside", StandardCharsets.UTF_8);

            InstructionAnalysisReport report = analyze("claude-code", root, root);
            equal(dev.agentconfig.workbench.ir.IrResolutionStatus.PARTIAL,
                    report.instructionIr().resolutionStatus(), "IR status");
            check(report.instructionIr().limitations().contains("non-portable-source-path"),
                    "portable-path limitation missing");
            check(report.instructionIr().sources().stream().allMatch(
                    source -> !source.logicalPath().contains("..")), "unsafe path entered IR");
        });
    }

    private static boolean hasHeuristicConflict(InstructionAnalysisReport report) {
        return report.findings().stream().anyMatch(finding ->
                finding.type() == AnalysisFindingType.DIRECT_POLARITY_CONFLICT
                        && finding.certainty() == AnalysisCertainty.HEURISTIC_CANDIDATE);
    }

    private static AnalysisFinding onlyFinding(
            InstructionAnalysisReport report, AnalysisFindingType type) {
        List<AnalysisFinding> matches = report.findings().stream()
                .filter(finding -> finding.type() == type).toList();
        equal(1, matches.size(), type + " finding count");
        return matches.getFirst();
    }

    private static InstructionAnalysisReport analyze(String host, Path root, Path cwd) throws IOException {
        EffectiveInstructionContext context = new EffectiveInstructionCompiler().compile(host, root, cwd);
        return new InstructionAnalysisEngine().analyze(context);
    }

    private static void write(Path root, String relative, String content) throws IOException {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private void withTempDirectory(ThrowingConsumer<Path> test) throws Exception {
        Path root = Files.createTempDirectory("acw-analysis-test-");
        try {
            test.accept(root);
        } finally {
            deleteOwnedTempTree(root);
        }
    }

    private static void deleteOwnedTempTree(Path root) throws IOException {
        if (!root.getFileName().toString().startsWith("acw-analysis-test-")
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing to clean an unexpected test path: " + root);
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
        if (!java.util.Objects.equals(expected, actual)) {
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
