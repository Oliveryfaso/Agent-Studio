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

public final class CliHardeningTests {
    private int passed;

    public static void main(String[] args) throws Exception {
        new CliHardeningTests().runAll();
    }

    private void runAll() throws Exception {
        run("scan JSON reports completion state", this::scanJsonReportsCompletionState);
        run("Git metadata requires an explicit CLI flag", this::gitMetadataRequiresFlag);
        run("unknown CLI option is rejected", this::unknownOptionIsRejected);
        System.out.printf("CLI hardening tests: %d passed%n", passed);
    }

    private void scanJsonReportsCompletionState() throws Exception {
        withTempDirectory(root -> {
            Files.writeString(root.resolve("AGENTS.md"), "metadata only", StandardCharsets.UTF_8);
            Invocation invocation = invoke("scan", root.toString());
            equal(0, invocation.exitCode(), "scan exit");
            check(invocation.stdout().contains("\"completionStatus\": \"COMPLETE\""),
                    "completion status missing");
            check(invocation.stdout().contains("\"stopReason\": \"NONE\""),
                    "stop reason missing");
            check(!invocation.stdout().contains("\"gitMetadata\""),
                    "Git metadata was probed without authorization");
        });
    }

    private void gitMetadataRequiresFlag() throws Exception {
        withTempDirectory(root -> {
            Files.createDirectories(root.resolve(".git"));
            Files.writeString(root.resolve(".git/HEAD"), "ref: refs/heads/main\n", StandardCharsets.UTF_8);

            Invocation withoutFlag = invoke("scan", root.toString());
            Invocation withFlag = invoke("scan", root.toString(), "--git-metadata");

            equal(0, withoutFlag.exitCode(), "default scan exit");
            equal(0, withFlag.exitCode(), "Git metadata scan exit");
            check(!withoutFlag.stdout().contains("\"gitMetadata\""),
                    "default scan exposed Git metadata");
            check(withFlag.stdout().contains("\"gitMetadata\""),
                    "explicit Git metadata missing");
            check(withFlag.stdout().contains("\"value\": \"refs/heads/main\""),
                    "symbolic HEAD missing");
            check(withFlag.stdout().contains("\"worktreeState\": \"UNKNOWN_NOT_PROBED\""),
                    "dirty state must stay unknown");
        });
    }

    private void unknownOptionIsRejected() throws Exception {
        withTempDirectory(root -> {
            Invocation invocation = invoke("scan", root.toString(), "--write");
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
        Path root = Files.createTempDirectory("acw-cli-test-");
        try {
            test.accept(root);
        } finally {
            deleteOwnedTempTree(root);
        }
    }

    private static void deleteOwnedTempTree(Path root) throws IOException {
        if (!root.getFileName().toString().startsWith("acw-cli-test-")
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
