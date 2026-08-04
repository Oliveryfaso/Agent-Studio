package dev.agentconfig.workbench;

import dev.agentconfig.workbench.cli.Cli;
import dev.agentconfig.workbench.context.ContextSource;
import dev.agentconfig.workbench.context.ContextResolutionStatus;
import dev.agentconfig.workbench.context.ContextSourceState;
import dev.agentconfig.workbench.context.EffectiveInstructionCompiler;
import dev.agentconfig.workbench.context.EffectiveInstructionContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class EffectiveContextTests {
    private int passed;

    public static void main(String[] args) throws Exception {
        new EffectiveContextTests().runAll();
    }

    private void runAll() throws Exception {
        run("Codex orders root and nested instructions", this::codexOrdersRootAndNestedInstructions);
        run("Codex override shadows same-directory base", this::codexOverrideShadowsBase);
        run("empty Codex override falls back to base", this::emptyOverrideFallsBack);
        run("Codex reports the default byte limit", this::codexReportsByteLimit);
        run("Claude orders project, local, and nested memory", this::claudeOrdersMemory);
        run("Claude project memory alternative and rules are visible", this::claudeAlternativeAndRules);
        run("current directory cannot escape authorized root", this::currentDirectoryCannotEscape);
        run("context CLI does not expose instruction content", this::cliDoesNotExposeContent);
        System.out.printf("Effective context tests: %d passed%n", passed);
    }

    private void codexOrdersRootAndNestedInstructions() throws Exception {
        withTempDirectory(base -> {
            Path root = Files.createDirectory(base.resolve("workspace"));
            Path nested = Files.createDirectories(root.resolve("services/payments"));
            Files.writeString(root.resolve("AGENTS.md"), "root");
            Files.writeString(nested.resolve("AGENTS.md"), "nested");

            EffectiveInstructionContext result = compiler().compile("codex", root, nested);
            equal(List.of("AGENTS.md", "services/payments/AGENTS.md"), activePaths(result), "active order");
            equal(List.of(1, 2), result.sources().stream().filter(ContextSource::active)
                    .map(ContextSource::precedence).toList(), "precedence");
            equal("EXPERIMENTAL_PROJECT_SEMANTICS", result.supportLevel(), "support level");
            equal("codex-project-semantics-v1", result.semanticProfile(), "semantic profile");
        });
    }

    private void codexOverrideShadowsBase() throws Exception {
        withTempDirectory(root -> {
            Files.writeString(root.resolve("AGENTS.md"), "base");
            Files.writeString(root.resolve("AGENTS.override.md"), "override");
            EffectiveInstructionContext result = compiler().compile("codex", root, root);

            equal(List.of("AGENTS.override.md"), activePaths(result), "override active");
            equal(ContextSourceState.SHADOWED, source(result, "AGENTS.md").state(), "base state");
            check(result.relations().stream().anyMatch(relation ->
                    portable(relation.fromLogicalPath()).equals("AGENTS.override.md")
                            && portable(relation.toLogicalPath()).equals("AGENTS.md")),
                    "shadow provenance missing");
        });
    }

    private void emptyOverrideFallsBack() throws Exception {
        withTempDirectory(root -> {
            Files.write(root.resolve("AGENTS.override.md"), new byte[0]);
            Files.writeString(root.resolve("AGENTS.md"), "base");
            EffectiveInstructionContext result = compiler().compile("codex", root, root);

            equal(ContextSourceState.EMPTY, source(result, "AGENTS.override.md").state(), "override state");
            equal(List.of("AGENTS.md"), activePaths(result), "fallback active");
        });
    }

    private void codexReportsByteLimit() throws Exception {
        withTempDirectory(root -> {
            Path nested = Files.createDirectory(root.resolve("nested"));
            Files.write(root.resolve("AGENTS.md"), new byte[33_000]);
            Files.writeString(nested.resolve("AGENTS.md"), "later");
            EffectiveInstructionContext result = compiler().compile("codex", root, nested);

            equal(32L * 1024L, result.maxCombinedBytes(), "default limit");
            equal(32L * 1024L, result.includedBytes(), "included bytes");
            equal(ContextSourceState.ACTIVE_TRUNCATED, source(result, "AGENTS.md").state(), "root state");
            equal(ContextSourceState.SKIPPED_LIMIT, source(result, "nested/AGENTS.md").state(), "nested state");
        });
    }

    private void claudeOrdersMemory() throws Exception {
        withTempDirectory(root -> {
            Path nested = Files.createDirectories(root.resolve("src/api"));
            Files.writeString(root.resolve("CLAUDE.md"), "root");
            Files.writeString(root.resolve("CLAUDE.local.md"), "local");
            Files.writeString(nested.resolve("CLAUDE.md"), "nested");
            EffectiveInstructionContext result = compiler().compile("claude-code", root, nested);

            equal(List.of("CLAUDE.md", "CLAUDE.local.md", "src/api/CLAUDE.md"),
                    activePaths(result), "Claude active order");
        });
    }

    private void claudeAlternativeAndRules() throws Exception {
        withTempDirectory(root -> {
            write(root, ".claude/CLAUDE.md", "project");
            write(root, ".claude/rules/testing.md", "---\npaths: [\"**/*.java\"]\n---\nrule");
            EffectiveInstructionContext result = compiler().compile("claude-code", root, root);

            equal(List.of(".claude/CLAUDE.md"), activePaths(result), "project alternative");
            equal(ContextSourceState.NOT_EVALUATED,
                    source(result, ".claude/rules/testing.md").state(), "rule state");
            check(result.findings().stream().anyMatch(
                    value -> value.code().equals("CLAUDE_TARGET_FILE_REQUIRED")),
                    "target-file finding missing");
            equal(ContextResolutionStatus.PARTIAL, result.resolutionStatus(), "rule resolution");

            Files.writeString(root.resolve("CLAUDE.md"), "root takes precedence in this version");
            EffectiveInstructionContext ambiguous = compiler().compile("claude-code", root, root);
            equal(List.of("CLAUDE.md"), activePaths(ambiguous), "ambiguous root choice");
            equal(ContextSourceState.NOT_EVALUATED,
                    source(ambiguous, ".claude/CLAUDE.md").state(), "alternative ambiguity state");
        });
    }

    private void currentDirectoryCannotEscape() throws Exception {
        withTempDirectory(base -> {
            Path root = Files.createDirectory(base.resolve("workspace"));
            Path outside = Files.createDirectory(base.resolve("outside"));
            try {
                compiler().compile("codex", root, outside);
                throw new AssertionError("outside current directory was accepted");
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().contains("authorized workspace"), "boundary error missing");
            }
        });
    }

    private void cliDoesNotExposeContent() throws Exception {
        withTempDirectory(root -> {
            String secret = "context-secret-must-not-leak";
            Files.writeString(root.resolve("AGENTS.md"), secret);
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            int exit = Cli.phaseOneDefaults().run(
                    new String[] {"context", "codex", root.toString(), root.toString()},
                    new PrintWriter(stdout, true, StandardCharsets.UTF_8),
                    new PrintWriter(stderr, true, StandardCharsets.UTF_8));
            equal(0, exit, "CLI exit");
            String json = stdout.toString(StandardCharsets.UTF_8);
            check(json.contains("\"supportLevel\": \"EXPERIMENTAL_PROJECT_SEMANTICS\""),
                    "support level missing");
            check(json.contains("\"state\": \"ACTIVE\""), "active source missing");
            check(!json.contains(secret), "raw instruction content leaked");
            equal("", stderr.toString(StandardCharsets.UTF_8), "stderr");
        });
    }

    private static EffectiveInstructionCompiler compiler() {
        return new EffectiveInstructionCompiler();
    }

    private static List<String> activePaths(EffectiveInstructionContext context) {
        return context.sources().stream().filter(ContextSource::active)
                .map(source -> portable(source.logicalPath())).toList();
    }

    private static ContextSource source(EffectiveInstructionContext context, String path) {
        return context.sources().stream()
                .filter(candidate -> portable(candidate.logicalPath()).equals(path))
                .findFirst().orElseThrow(() -> new AssertionError("Missing source: " + path));
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static void write(Path root, String relative, String content) throws IOException {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private void withTempDirectory(ThrowingConsumer<Path> test) throws Exception {
        Path root = Files.createTempDirectory("acw-context-test-");
        try {
            test.accept(root);
        } finally {
            deleteOwnedTempTree(root);
        }
    }

    private static void deleteOwnedTempTree(Path root) throws IOException {
        if (!root.getFileName().toString().startsWith("acw-context-test-")
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing to clean an unexpected test path: " + root);
        }
        try (var paths = Files.walk(root)) {
            List<Path> ownedPaths = new ArrayList<>(paths.toList());
            ownedPaths.sort(Comparator.reverseOrder());
            for (Path path : ownedPaths) {
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
