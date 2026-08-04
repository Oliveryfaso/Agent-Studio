package dev.agentconfig.workbench;

import dev.agentconfig.workbench.cli.Cli;
import dev.agentconfig.workbench.context.ContextCompileRequest;
import dev.agentconfig.workbench.context.ContextResolutionStatus;
import dev.agentconfig.workbench.context.ContextSource;
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
import java.util.Optional;

public final class EffectiveContextAdvancedTests {
    private int passed;

    public static void main(String[] args) throws Exception {
        new EffectiveContextAdvancedTests().runAll();
    }

    private void runAll() throws Exception {
        run("Codex snapshot changes fallback and budget", this::codexSnapshotChangesBehavior);
        run("Codex config CLI option is effective", this::codexConfigCliIsEffective);
        run("Claude imports resolve recursively", this::claudeImportsResolveRecursively);
        run("Claude import cycles are partial findings", this::claudeImportCycleIsPartial);
        run("Claude rules react to target file", this::claudeRulesReactToTarget);
        run("Claude path rule without target is partial", this::claudeRuleWithoutTargetIsPartial);
        run("large host files stay semantically active", this::largeFilesStayActive);
        System.out.printf("Advanced effective context tests: %d passed%n", passed);
    }

    private void codexSnapshotChangesBehavior() throws Exception {
        withTempDirectory(base -> {
            Path root = Files.createDirectory(base.resolve("workspace"));
            Files.writeString(root.resolve("TEAM_GUIDE.md"), "1234567890");
            Path config = base.resolve("config.toml");
            Files.writeString(config, "project_doc_fallback_filenames = [\"TEAM_GUIDE.md\"]\n"
                    + "project_doc_max_bytes = 4\n");

            EffectiveInstructionContext result = compiler().compile(new ContextCompileRequest(
                    "codex", root, root, Optional.of(config), Optional.empty()));
            ContextSource fallback = source(result, "TEAM_GUIDE.md");
            equal(ContextSourceState.ACTIVE_TRUNCATED, fallback.state(), "fallback state");
            equal(4L, result.maxCombinedBytes(), "custom max bytes");
            equal(4L, result.includedBytes(), "custom included bytes");
            equal(ContextResolutionStatus.COMPLETE, result.resolutionStatus(), "resolution");
        });
    }

    private void codexConfigCliIsEffective() throws Exception {
        withTempDirectory(base -> {
            Path root = Files.createDirectory(base.resolve("workspace"));
            Files.writeString(root.resolve("TEAM.md"), "team");
            Path config = Files.writeString(base.resolve("codex.toml"),
                    "project_doc_fallback_filenames = [\"TEAM.md\"]\n");
            Invocation result = invoke("context", "codex", root.toString(), root.toString(),
                    "--codex-config", config.toString());
            equal(0, result.exitCode(), "CLI exit");
            check(result.stdout().contains("\"logicalPath\": \"TEAM.md\""),
                    "configured fallback missing");
            check(result.stdout().contains("\"kind\": \"CODEX_FALLBACK\""),
                    "fallback kind missing");
        });
    }

    private void claudeImportsResolveRecursively() throws Exception {
        withTempDirectory(root -> {
            write(root, "CLAUDE.md", "See @docs/shared.md but not `@secret.md`.\n");
            write(root, "docs/shared.md", "Nested @nested.md\n");
            write(root, "docs/nested.md", "done\n");
            write(root, "secret.md", "must not load\n");

            EffectiveInstructionContext result = compiler().compile("claude-code", root, root);
            equal(ContextResolutionStatus.COMPLETE, result.resolutionStatus(), "resolution");
            equal(List.of("CLAUDE.md", "docs/shared.md", "docs/nested.md"),
                    activePaths(result), "import order");
            equal(2, result.relations().size(), "import relation count");
            check(result.relations().stream().anyMatch(relation ->
                    portable(relation.fromLogicalPath()).equals("CLAUDE.md")
                            && portable(relation.toLogicalPath()).equals("docs/shared.md")),
                    "root import provenance missing");
            check(result.sources().stream().noneMatch(
                    value -> portable(value.logicalPath()).equals("secret.md")), "inline code import loaded");
        });
    }

    private void claudeImportCycleIsPartial() throws Exception {
        withTempDirectory(root -> {
            write(root, "CLAUDE.md", "@docs/a.md\n");
            write(root, "docs/a.md", "@../CLAUDE.md\n");
            EffectiveInstructionContext result = compiler().compile("claude-code", root, root);
            equal(ContextResolutionStatus.PARTIAL, result.resolutionStatus(), "resolution");
            check(result.findings().stream().anyMatch(
                    value -> value.code().equals("CLAUDE_IMPORT_CYCLE")), "cycle finding missing");
        });
    }

    private void claudeRulesReactToTarget() throws Exception {
        withTempDirectory(root -> {
            write(root, ".claude/rules/always.md", "# Always\n");
            write(root, ".claude/rules/api.md", "---\npaths:\n  - \"src/api/**/*.ts\"\n---\n# API\n");

            EffectiveInstructionContext match = compiler().compile(new ContextCompileRequest(
                    "claude-code", root, root, Optional.empty(), Optional.of(Path.of("src/api/v1/user.ts"))));
            equal(ContextSourceState.ACTIVE, source(match, ".claude/rules/always.md").state(),
                    "unconditional state");
            equal(ContextSourceState.ACTIVE, source(match, ".claude/rules/api.md").state(),
                    "matching state");
            equal(ContextResolutionStatus.COMPLETE, match.resolutionStatus(), "matching resolution");

            Invocation cliMatch = invoke("context", "claude-code", root.toString(), root.toString(),
                    "--target-file", "src/api/v1/user.ts");
            equal(0, cliMatch.exitCode(), "target CLI exit");
            check(cliMatch.stdout().contains("Path-scoped rule matches the supplied target file"),
                    "target CLI option was not applied");

            EffectiveInstructionContext noMatch = compiler().compile(new ContextCompileRequest(
                    "claude-code", root, root, Optional.empty(), Optional.of(Path.of("src/ui/view.ts"))));
            equal(ContextSourceState.CONDITIONAL_NO_MATCH,
                    source(noMatch, ".claude/rules/api.md").state(), "nonmatching state");
        });
    }

    private void claudeRuleWithoutTargetIsPartial() throws Exception {
        withTempDirectory(root -> {
            write(root, ".claude/rules/api.md", "---\npaths: [\"src/**\"]\n---\n# API\n");
            Invocation invocation = invoke("context", "claude-code", root.toString(), root.toString());
            equal(3, invocation.exitCode(), "partial CLI exit");
            check(invocation.stdout().contains("\"resolutionStatus\": \"PARTIAL\""),
                    "partial status missing");
            check(invocation.stdout().contains("\"code\": \"CLAUDE_TARGET_FILE_REQUIRED\""),
                    "target finding missing");
        });
    }

    private void largeFilesStayActive() throws Exception {
        withTempDirectory(base -> {
            Path codex = Files.createDirectory(base.resolve("codex"));
            Path claude = Files.createDirectory(base.resolve("claude"));
            byte[] large = new byte[1024 * 1024 + 1];
            Files.write(codex.resolve("AGENTS.md"), large);
            Files.write(claude.resolve("CLAUDE.md"), large);

            EffectiveInstructionContext codexResult = compiler().compile("codex", codex, codex);
            EffectiveInstructionContext claudeResult = compiler().compile("claude-code", claude, claude);
            equal(ContextSourceState.ACTIVE_TRUNCATED, source(codexResult, "AGENTS.md").state(),
                    "large Codex state");
            equal(ContextSourceState.ACTIVE, source(claudeResult, "CLAUDE.md").state(),
                    "large Claude state");
            check(source(claudeResult, "CLAUDE.md").sha256().isEmpty(), "large hash should be omitted");
            equal(ContextResolutionStatus.PARTIAL, claudeResult.resolutionStatus(),
                    "large Claude semantic parsing must be partial");
        });
    }

    private static EffectiveInstructionCompiler compiler() {
        return new EffectiveInstructionCompiler();
    }

    private static List<String> activePaths(EffectiveInstructionContext context) {
        return context.sources().stream().filter(ContextSource::active)
                .map(source -> portable(source.logicalPath())).toList();
    }

    private static ContextSource source(EffectiveInstructionContext context, String logicalPath) {
        return context.sources().stream()
                .filter(value -> portable(value.logicalPath()).equals(logicalPath))
                .findFirst().orElseThrow(() -> new AssertionError("Missing source: " + logicalPath));
    }

    private static Invocation invoke(String... arguments) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = Cli.phaseOneDefaults().run(arguments,
                new PrintWriter(stdout, true, StandardCharsets.UTF_8),
                new PrintWriter(stderr, true, StandardCharsets.UTF_8));
        return new Invocation(exit, stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private static void write(Path root, String relative, String content) throws IOException {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    private void withTempDirectory(ThrowingConsumer<Path> test) throws Exception {
        Path root = Files.createTempDirectory("acw-context-advanced-");
        try {
            test.accept(root);
        } finally {
            deleteOwnedTempTree(root);
        }
    }

    private static void deleteOwnedTempTree(Path root) throws IOException {
        if (!root.getFileName().toString().startsWith("acw-context-advanced-")
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

    private record Invocation(int exitCode, String stdout, String stderr) {}

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T value) throws Exception;
    }
}
