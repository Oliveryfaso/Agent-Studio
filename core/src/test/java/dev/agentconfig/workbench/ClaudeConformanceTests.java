package dev.agentconfig.workbench;

import dev.agentconfig.workbench.context.ContextCompileRequest;
import dev.agentconfig.workbench.context.ContextRelation;
import dev.agentconfig.workbench.context.ContextRelationKind;
import dev.agentconfig.workbench.context.ContextResolutionStatus;
import dev.agentconfig.workbench.context.ContextSource;
import dev.agentconfig.workbench.context.ContextSourceKind;
import dev.agentconfig.workbench.context.ContextSourceState;
import dev.agentconfig.workbench.context.EffectiveInstructionCompiler;
import dev.agentconfig.workbench.context.EffectiveInstructionContext;
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

/**
 * Versioned conformance fixtures for the deliberately narrow Claude Code project semantics profile.
 * These tests describe the behavior the workbench claims, not every behavior of Claude Code.
 */
public final class ClaudeConformanceTests {
    private static final String PROFILE = "claude-code-project-semantics-v1";

    private int passed;

    public static void main(String[] args) throws Exception {
        new ClaudeConformanceTests().runAll();
    }

    private void runAll() throws Exception {
        run("v1 selects one project memory location", this::selectsOneProjectMemoryLocation);
        run("v1 orders local and nested memory", this::ordersLocalAndNestedMemory);
        run("v1 orders unconditional and matching path rules", this::ordersRules);
        run("v1 marks a nonmatching path rule inactive", this::marksNonmatchingRuleInactive);
        run("v1 requires a target for path rules", this::requiresTargetForPathRules);
        run("v1 resolves recursive imports depth first", this::resolvesRecursiveImports);
        run("v1 preserves every parent of a shared import", this::preservesMultipleImportParents);
        run("v1 reports import cycles", this::reportsImportCycles);
        run("v1 refuses to read external imports", this::refusesExternalImports);
        run("v1 provenance is stable across compilations", this::provenanceIsStable);
        System.out.printf("Claude conformance tests (%s): %d passed%n", PROFILE, passed);
    }

    private void selectsOneProjectMemoryLocation() throws Exception {
        withFixture(root -> {
            write(root, ".claude/CLAUDE.md", "project alternative\n");
            EffectiveInstructionContext alternative = compile(root, root, null);
            assertProfile(alternative);
            equal(List.of(".claude/CLAUDE.md"), activePaths(alternative), "alternative active");
            equal(ContextSourceKind.CLAUDE_PROJECT_MEMORY,
                    source(alternative, ".claude/CLAUDE.md").kind(), "alternative kind");
            equal(ContextResolutionStatus.COMPLETE, alternative.resolutionStatus(),
                    "alternative resolution");

            write(root, "CLAUDE.md", "preferred root memory\n");
            EffectiveInstructionContext ambiguous = compile(root, root, null);
            equal(List.of("CLAUDE.md"), activePaths(ambiguous), "root memory choice");
            equal(ContextSourceState.NOT_EVALUATED,
                    source(ambiguous, ".claude/CLAUDE.md").state(), "alternative state");
            finding(ambiguous, "AMBIGUOUS_CLAUDE_PROJECT_MEMORY");
            equal(ContextResolutionStatus.PARTIAL, ambiguous.resolutionStatus(),
                    "ambiguous resolution");

            Files.write(root.resolve("CLAUDE.md"), new byte[0]);
            EffectiveInstructionContext emptyRoot = compile(root, root, null);
            equal(ContextSourceState.EMPTY, source(emptyRoot, "CLAUDE.md").state(),
                    "empty root state");
            equal(List.of(".claude/CLAUDE.md"), activePaths(emptyRoot),
                    "empty root falls back to project alternative");
        });
    }

    private void ordersLocalAndNestedMemory() throws Exception {
        withFixture(root -> {
            Path current = Files.createDirectories(root.resolve("packages/api"));
            write(root, "CLAUDE.md", "root\n");
            write(root, "CLAUDE.local.md", "root local\n");
            write(root, "packages/CLAUDE.md", "package\n");
            write(root, "packages/CLAUDE.local.md", "package local\n");
            write(root, "packages/api/CLAUDE.md", "api\n");
            write(root, "packages/api/CLAUDE.local.md", "api local\n");

            EffectiveInstructionContext result = compile(root, current, null);
            assertProfile(result);
            equal(List.of(
                            "CLAUDE.md",
                            "CLAUDE.local.md",
                            "packages/CLAUDE.md",
                            "packages/CLAUDE.local.md",
                            "packages/api/CLAUDE.md",
                            "packages/api/CLAUDE.local.md"),
                    activePaths(result), "memory order");
            equal(List.of(1, 2, 3, 4, 5, 6), activePrecedence(result), "memory precedence");
            equal(ContextResolutionStatus.COMPLETE, result.resolutionStatus(), "resolution");
        });
    }

    private void ordersRules() throws Exception {
        withFixture(root -> {
            write(root, "CLAUDE.md", "memory\n");
            write(root, ".claude/rules/00-always.md", "# Always\n");
            write(root, ".claude/rules/10-docs.md",
                    "---\npaths: [\"docs/**/*.md\"]\n---\n# Docs\n");
            write(root, ".claude/rules/nested/20-api.md",
                    "---\npaths: [\"src/api/**/*.ts\"]\n---\n# API\n");

            EffectiveInstructionContext result = compile(root, root, "src/api/v1/user.ts");
            equal(List.of(
                            "CLAUDE.md",
                            ".claude/rules/00-always.md",
                            ".claude/rules/nested/20-api.md"),
                    activePaths(result), "active memory/rule order");
            equal(List.of(1, 2, 3), activePrecedence(result), "rule precedence");
            equal(ContextSourceState.CONDITIONAL_NO_MATCH,
                    source(result, ".claude/rules/10-docs.md").state(), "nonmatching sibling");
            equal(ContextSourceKind.CLAUDE_RULE,
                    source(result, ".claude/rules/nested/20-api.md").kind(), "rule kind");
            equal(ContextResolutionStatus.COMPLETE, result.resolutionStatus(), "resolution");
        });
    }

    private void marksNonmatchingRuleInactive() throws Exception {
        withFixture(root -> {
            write(root, "CLAUDE.md", "memory\n");
            write(root, ".claude/rules/api.md",
                    "---\npaths:\n  - \"src/api/**/*.ts\"\n---\n# API\n");

            EffectiveInstructionContext result = compile(root, root, "docs/guide.md");
            equal(List.of("CLAUDE.md"), activePaths(result), "active sources");
            ContextSource rule = source(result, ".claude/rules/api.md");
            equal(ContextSourceState.CONDITIONAL_NO_MATCH, rule.state(), "rule state");
            equal(0, rule.precedence(), "inactive rule precedence");
            equal(0L, rule.includedBytes(), "inactive rule bytes");
            equal(ContextResolutionStatus.COMPLETE, result.resolutionStatus(), "resolution");
        });
    }

    private void requiresTargetForPathRules() throws Exception {
        withFixture(root -> {
            write(root, "CLAUDE.md", "memory\n");
            write(root, ".claude/rules/00-always.md", "# Always\n");
            write(root, ".claude/rules/10-api.md",
                    "---\npaths: [\"src/api/**/*.ts\"]\n---\n# API\n");

            EffectiveInstructionContext result = compile(root, root, null);
            equal(List.of("CLAUDE.md", ".claude/rules/00-always.md"),
                    activePaths(result), "active sources without target");
            equal(ContextSourceState.NOT_EVALUATED,
                    source(result, ".claude/rules/10-api.md").state(), "path rule state");
            finding(result, "CLAUDE_TARGET_FILE_REQUIRED");
            equal(ContextResolutionStatus.PARTIAL, result.resolutionStatus(), "resolution");
        });
    }

    private void resolvesRecursiveImports() throws Exception {
        withFixture(root -> {
            write(root, "CLAUDE.md", "Start with @docs/a.md\n");
            write(root, "docs/a.md", "Continue with @nested/b.md\n");
            write(root, "docs/nested/b.md", "Done\n");

            EffectiveInstructionContext result = compile(root, root, null);
            equal(List.of("CLAUDE.md", "docs/a.md", "docs/nested/b.md"),
                    activePaths(result), "recursive import order");
            equal(List.of(
                            "IMPORTS|CLAUDE.md|docs/a.md",
                            "IMPORTS|docs/a.md|docs/nested/b.md"),
                    relationFingerprints(result), "recursive provenance");
            equal(ContextResolutionStatus.COMPLETE, result.resolutionStatus(), "resolution");
        });
    }

    private void preservesMultipleImportParents() throws Exception {
        withFixture(root -> {
            write(root, "CLAUDE.md", "@docs/a.md\n@docs/b.md\n");
            write(root, "docs/a.md", "@shared.md\n");
            write(root, "docs/b.md", "@shared.md\n");
            write(root, "docs/shared.md", "shared\n");

            EffectiveInstructionContext result = compile(root, root, null);
            equal(List.of("CLAUDE.md", "docs/a.md", "docs/shared.md", "docs/b.md"),
                    activePaths(result), "shared import source order");
            equal(1L, result.sources().stream()
                    .filter(ContextSource::active)
                    .filter(value -> portable(value.logicalPath()).equals("docs/shared.md"))
                    .count(), "shared source count");
            equal(List.of(
                            "IMPORTS|CLAUDE.md|docs/a.md",
                            "IMPORTS|docs/a.md|docs/shared.md",
                            "IMPORTS|CLAUDE.md|docs/b.md",
                            "IMPORTS|docs/b.md|docs/shared.md"),
                    relationFingerprints(result), "all import parents");
            equal(ContextResolutionStatus.COMPLETE, result.resolutionStatus(), "resolution");
        });
    }

    private void reportsImportCycles() throws Exception {
        withFixture(root -> {
            write(root, "CLAUDE.md", "@docs/a.md\n");
            write(root, "docs/a.md", "@../CLAUDE.md\n");

            EffectiveInstructionContext result = compile(root, root, null);
            equal(List.of("CLAUDE.md", "docs/a.md"), activePaths(result), "active sources");
            equal(2L, result.sources().stream()
                    .filter(value -> portable(value.logicalPath()).equals("CLAUDE.md"))
                    .count(), "active and rejected cycle nodes");
            check(result.sources().stream()
                            .filter(value -> portable(value.logicalPath()).equals("CLAUDE.md"))
                            .anyMatch(value -> value.state() == ContextSourceState.INVALID),
                    "cycle target must be invalid");
            equal(List.of(
                            "IMPORTS|CLAUDE.md|docs/a.md",
                            "IMPORTS|docs/a.md|CLAUDE.md"),
                    relationFingerprints(result), "cycle provenance");
            finding(result, "CLAUDE_IMPORT_CYCLE");
            equal(ContextResolutionStatus.PARTIAL, result.resolutionStatus(), "resolution");
        });
    }

    private void refusesExternalImports() throws Exception {
        withFixture(root -> {
            write(root, "CLAUDE.md", "@../outside.md\n");

            EffectiveInstructionContext result = compile(root, root, null);
            ContextSource external = source(result, "../outside.md");
            equal(ContextSourceState.EXTERNAL_APPROVAL_REQUIRED, external.state(), "external state");
            equal(null, external.realPath(), "external real path");
            equal("", external.sha256(), "external hash");
            equal(List.of("IMPORTS|CLAUDE.md|../outside.md"),
                    relationFingerprints(result), "external provenance");
            finding(result, "CLAUDE_EXTERNAL_IMPORT_APPROVAL_REQUIRED");
            equal(ContextResolutionStatus.PARTIAL, result.resolutionStatus(), "resolution");
        });
    }

    private void provenanceIsStable() throws Exception {
        withFixture(root -> {
            write(root, "CLAUDE.md", "@docs/a.md\n@docs/b.md\n");
            write(root, "docs/a.md", "@shared.md\n");
            write(root, "docs/b.md", "@shared.md\n");
            write(root, "docs/shared.md", "shared\n");
            write(root, ".claude/rules/00-always.md", "# Always\n");
            write(root, ".claude/rules/10-api.md",
                    "---\npaths: [\"src/api/**/*.ts\"]\n---\n# API\n");

            EffectiveInstructionContext first = compile(root, root, "src/api/v1/user.ts");
            EffectiveInstructionContext second = compile(root, root, "src/api/v1/user.ts");
            equal(sourceFingerprints(first), sourceFingerprints(second), "source provenance stability");
            equal(relationFingerprints(first), relationFingerprints(second),
                    "relation provenance stability");
            equal(first.findings(), second.findings(), "finding stability");
        });
    }

    private static EffectiveInstructionContext compile(Path root, Path cwd, String target)
            throws IOException {
        return new EffectiveInstructionCompiler().compile(new ContextCompileRequest(
                "claude-code", root, cwd, Optional.empty(),
                target == null ? Optional.empty() : Optional.of(Path.of(target))));
    }

    private static void assertProfile(EffectiveInstructionContext result) {
        equal(PROFILE, result.semanticProfile(), "semantic profile");
        equal("EXPERIMENTAL_PROJECT_SEMANTICS", result.supportLevel(), "support level");
    }

    private static List<String> activePaths(EffectiveInstructionContext context) {
        return context.sources().stream()
                .filter(ContextSource::active)
                .map(value -> portable(value.logicalPath()))
                .toList();
    }

    private static List<Integer> activePrecedence(EffectiveInstructionContext context) {
        return context.sources().stream()
                .filter(ContextSource::active)
                .map(ContextSource::precedence)
                .toList();
    }

    private static ContextSource source(EffectiveInstructionContext context, String logicalPath) {
        return context.sources().stream()
                .filter(value -> portable(value.logicalPath()).equals(logicalPath))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing source: " + logicalPath));
    }

    private static void finding(EffectiveInstructionContext context, String code) {
        check(context.findings().stream().anyMatch(value -> value.code().equals(code)),
                "Missing finding: " + code);
    }

    private static List<String> sourceFingerprints(EffectiveInstructionContext context) {
        return context.sources().stream()
                .map(value -> String.join("|",
                        portable(value.logicalPath()),
                        value.kind().name(),
                        value.state().name(),
                        Integer.toString(value.precedence()),
                        Long.toString(value.byteSize()),
                        Long.toString(value.includedBytes()),
                        value.sha256(),
                        value.detail()))
                .toList();
    }

    private static List<String> relationFingerprints(EffectiveInstructionContext context) {
        return context.relations().stream()
                .map(ClaudeConformanceTests::relationFingerprint)
                .toList();
    }

    private static String relationFingerprint(ContextRelation relation) {
        equal(ContextRelationKind.IMPORTS, relation.kind(), "Claude relation kind");
        return relation.kind().name() + "|" + portable(relation.fromLogicalPath()) + "|"
                + portable(relation.toLogicalPath());
    }

    private static void write(Path root, String relative, String content) throws IOException {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    private void withFixture(ThrowingConsumer<Path> test) throws Exception {
        Path base = Files.createTempDirectory("acw-claude-conformance-v1-");
        Path root = Files.createDirectory(base.resolve(PROFILE));
        try {
            test.accept(root);
        } finally {
            deleteOwnedFixture(base);
        }
    }

    private static void deleteOwnedFixture(Path base) throws IOException {
        if (!base.getFileName().toString().startsWith("acw-claude-conformance-v1-")
                || !Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing to clean an unexpected fixture path: " + base);
        }
        try (var paths = Files.walk(base)) {
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
