package dev.agentconfig.workbench;

import dev.agentconfig.workbench.cli.Cli;
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
import java.util.Objects;

public final class AnalysisCliTests {
    private int passed;

    public static void main(String[] args) throws Exception {
        new AnalysisCliTests().runAll();
    }

    private void runAll() throws Exception {
        run("analyze emits schema v1 without instruction content", this::contentFreeSchema);
        run("heuristic findings do not change the exit code", this::heuristicsExitSuccessfully);
        run("partial IR exits with code three", this::partialIrExit);
        run("analyze options remain host-specific", this::hostSpecificOptions);
        System.out.printf("Analysis CLI tests: %d passed%n", passed);
    }

    private void contentFreeSchema() throws Exception {
        withTempDirectory(root -> {
            String secret = "cli-analysis-secret-9f21";
            Files.writeString(root.resolve("AGENTS.md"), "- Must preserve " + secret + "\n",
                    StandardCharsets.UTF_8);
            Invocation invocation = invoke("analyze", "codex", root.toString(), root.toString());

            equal(0, invocation.exitCode(), "exit");
            check(invocation.stdout().contains("\"schemaVersion\": 1"), "analysis schema missing");
            check(invocation.stdout().contains("\"contextSchemaVersion\": 2"),
                    "context schema reference missing");
            check(invocation.stdout().contains(
                    "\"semanticProfile\": \"codex-project-semantics-v1\""),
                    "semantic profile missing");
            check(invocation.stdout().contains("\"instructionIr\""), "IR missing");
            check(invocation.stdout().contains("\"summary\""), "summary missing");
            check(!invocation.stdout().contains(secret), "raw instruction content leaked");
            check(!invocation.stdout().contains("\"realPath\""), "physical path field leaked");
        });
    }

    private void heuristicsExitSuccessfully() throws Exception {
        withTempDirectory(root -> {
            Path nested = Files.createDirectories(root.resolve("nested"));
            Files.writeString(root.resolve("AGENTS.md"), "- Always run tests.\n",
                    StandardCharsets.UTF_8);
            Files.writeString(nested.resolve("AGENTS.md"), "# Nested\n- always   run tests\n",
                    StandardCharsets.UTF_8);

            Invocation invocation = invoke("analyze", "codex", root.toString(), nested.toString());
            equal(0, invocation.exitCode(), "heuristic-only exit");
            check(invocation.stdout().contains("\"type\": \"NORMALIZED_DIRECTIVE_DUPLICATE\""),
                    "heuristic duplicate missing");
            check(invocation.stdout().contains("\"certainty\": \"HEURISTIC_CANDIDATE\""),
                    "heuristic certainty missing");
            check(invocation.stdout().contains("\"heuristicFindingCount\": 1"),
                    "heuristic summary count missing");
        });
    }

    private void partialIrExit() throws Exception {
        withTempDirectory(root -> {
            Path rules = Files.createDirectories(root.resolve(".claude/rules"));
            Files.writeString(root.resolve("CLAUDE.md"), "- Must run tests before commit\n",
                    StandardCharsets.UTF_8);
            Files.writeString(rules.resolve("api.md"),
                    "---\npaths:\n  - src/api/**\n---\n- Must validate API responses\n",
                    StandardCharsets.UTF_8);

            Invocation invocation = invoke("analyze", "claude-code", root.toString(), root.toString());
            equal(3, invocation.exitCode(), "partial exit");
            check(invocation.stdout().contains("\"resolutionStatus\": \"PARTIAL\""),
                    "partial IR status missing");
        });
    }

    private void hostSpecificOptions() throws Exception {
        withTempDirectory(root -> {
            Invocation invocation = invoke(
                    "analyze", "claude-code", root.toString(), root.toString(),
                    "--codex-config", root.resolve("snapshot.toml").toString());
            equal(2, invocation.exitCode(), "invalid option exit");
            check(invocation.stderr().contains("Usage:"), "usage missing");
        });
    }

    private static Invocation invoke(String... arguments) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = Cli.phaseOneDefaults().run(
                arguments,
                new PrintWriter(stdout, true, StandardCharsets.UTF_8),
                new PrintWriter(stderr, true, StandardCharsets.UTF_8));
        return new Invocation(
                exit,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private void withTempDirectory(ThrowingConsumer<Path> test) throws Exception {
        Path root = Files.createTempDirectory("acw-analysis-cli-");
        try {
            test.accept(root);
        } finally {
            deleteOwnedTempTree(root);
        }
    }

    private static void deleteOwnedTempTree(Path root) throws IOException {
        if (!root.getFileName().toString().startsWith("acw-analysis-cli-")
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
        if (!Objects.equals(expected, actual)) {
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
