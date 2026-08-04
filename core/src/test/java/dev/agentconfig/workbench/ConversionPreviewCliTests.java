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

public final class ConversionPreviewCliTests {
    private int passed;

    public static void main(String[] args) throws Exception {
        new ConversionPreviewCliTests().runAll();
    }

    private void runAll() throws Exception {
        run("Codex to Claude preview is content-free and write-disabled", this::codexToClaude);
        run("Claude to Codex preview reports assisted metadata plan", this::claudeToCodex);
        run("existing target is represented by hash metadata only", this::existingTarget);
        run("identical target is a metadata no-op", this::identicalTarget);
        run("partial source IR exits three without a plan", this::partialSource);
        run("unsafe symlink target exits three", this::symlinkTarget);
        run("same-host conversion is rejected", this::sameHostRejected);
        System.out.printf("Conversion preview CLI tests: %d passed%n", passed);
    }

    private void codexToClaude() throws Exception {
        withTempDirectory(root -> {
            String secret = "conversion-secret-codex-741";
            Files.writeString(root.resolve("AGENTS.md"), "- Preserve " + secret + "\n");
            Invocation result = invoke("convert-preview", "codex", "claude-code",
                    root.toString(), root.toString());
            equal(0, result.exitCode(), "exit");
            check(result.stdout().contains("\"schemaVersion\": 2"), "schema version missing");
            check(result.stdout().contains("\"version\": 2"), "recipe version missing");
            check(result.stdout().contains("\"operation\": \"CONVERSION_PREVIEW\""),
                    "operation missing");
            check(result.stdout().contains("\"writesPerformed\": false"), "write flag missing");
            check(result.stdout().contains("\"applyEligible\": false"), "apply flag missing");
            check(result.stdout().contains("\"logicalPath\": \"CLAUDE.md\""),
                    "target missing");
            check(result.stdout().contains("\"renderState\": \"RENDERED\""),
                    "wrapper render state missing");
            check(result.stdout().contains("\"targetValidation\": \"PASSED\""),
                    "target validation missing");
            check(result.stdout().contains("\"semanticRoundTrip\": \"PASSED\""),
                    "round-trip validation missing");
            check(result.stdout().contains("\"targetValidationSubjectSha256\":"),
                    "validation subject hash missing");
            check(!result.stdout().contains(secret), "source content leaked");
            check(!result.stdout().contains("realPath"), "physical path leaked");
            check(!Files.exists(root.resolve("CLAUDE.md")), "preview wrote target file");
        });
    }

    private void claudeToCodex() throws Exception {
        withTempDirectory(root -> {
            Files.writeString(root.resolve("CLAUDE.md"), "- Run the tests\n");
            Invocation result = invoke("convert-preview", "claude-code", "codex",
                    root.toString(), root.toString());
            equal(0, result.exitCode(), "exit");
            check(result.stdout().contains("\"targetSemanticProfile\": "
                    + "\"codex-project-semantics-v1\""), "target profile missing");
            check(result.stdout().contains("\"grade\": \"ASSISTED\""), "grade missing");
            check(result.stdout().contains("\"renderState\": \"METADATA_ONLY\""),
                    "metadata-only marker missing");
            check(result.stdout().contains("\"semanticRoundTrip\": \"NOT_RUN\""),
                    "round-trip state missing");
        });
    }

    private void existingTarget() throws Exception {
        withTempDirectory(root -> {
            String targetSecret = "existing-target-secret-941";
            Files.writeString(root.resolve("AGENTS.md"), "- Source\n");
            Files.writeString(root.resolve("CLAUDE.md"), targetSecret);
            Invocation result = invoke("convert-preview", "codex", "claude-code",
                    root.toString(), root.toString());
            equal(0, result.exitCode(), "exit");
            check(result.stdout().contains("\"conflictState\": \"EXISTING_TARGET_CONFLICT\""),
                    "conflict state missing");
            check(result.stdout().contains("\"existingTargetSha256\":"), "hash metadata missing");
            check(result.stdout().contains("\"threeWayReview\": \"FAILED\""),
                    "conflict review missing");
            check(!result.stdout().contains(targetSecret), "existing target content leaked");
        });
    }

    private void identicalTarget() throws Exception {
        withTempDirectory(root -> {
            Files.writeString(root.resolve("AGENTS.md"), "- Source\n");
            Path target = root.resolve("CLAUDE.md");
            Files.writeString(target, "@AGENTS.md\n");
            byte[] before = Files.readAllBytes(target);
            var modified = Files.getLastModifiedTime(target);
            Invocation result = invoke("convert-preview", "codex", "claude-code",
                    root.toString(), root.toString());
            equal(0, result.exitCode(), "exit");
            check(result.stdout().contains(
                    "\"conflictState\": \"EXISTING_TARGET_IDENTICAL\""),
                    "identical state missing");
            check(result.stdout().contains("\"threeWayReview\": \"PASSED\""),
                    "metadata review missing");
            check(result.stdout().contains("\"applyEligible\": false"),
                    "preview gained apply authority");
            check(java.util.Arrays.equals(before, Files.readAllBytes(target)),
                    "identical target bytes changed");
            equal(modified, Files.getLastModifiedTime(target), "target mtime");
        });
    }

    private void partialSource() throws Exception {
        withTempDirectory(root -> {
            Path rules = Files.createDirectories(root.resolve(".claude/rules"));
            Files.writeString(root.resolve("CLAUDE.md"), "- Root\n");
            Files.writeString(rules.resolve("api.md"),
                    "---\npaths:\n  - src/api/**\n---\n- Validate API\n");
            Invocation result = invoke("convert-preview", "claude-code", "codex",
                    root.toString(), root.toString());
            equal(3, result.exitCode(), "exit");
            check(result.stdout().isEmpty(), "partial source emitted a plan");
            check(result.stderr().contains("complete source IR"), "partial explanation missing");
        });
    }

    private void symlinkTarget() throws Exception {
        withTempDirectory(root -> {
            Files.writeString(root.resolve("AGENTS.md"), "- Root\n");
            Files.createSymbolicLink(root.resolve("CLAUDE.md"), root.resolve("outside.md"));
            Invocation result = invoke("convert-preview", "codex", "claude-code",
                    root.toString(), root.toString());
            equal(3, result.exitCode(), "exit");
            check(result.stdout().contains("\"conflictState\": \"OUTSIDE_SCOPE\""),
                    "unsafe target state missing");
        });
    }

    private void sameHostRejected() throws Exception {
        withTempDirectory(root -> {
            Invocation result = invoke("convert-preview", "codex", "codex",
                    root.toString(), root.toString());
            equal(2, result.exitCode(), "exit");
            check(result.stderr().contains("Usage:"), "usage missing");
        });
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

    private void withTempDirectory(ThrowingConsumer<Path> test) throws Exception {
        Path root = Files.createTempDirectory("acw-convert-cli-");
        try {
            test.accept(root);
        } finally {
            deleteOwnedTempTree(root);
        }
    }

    private static void deleteOwnedTempTree(Path root) throws IOException {
        if (!root.getFileName().toString().startsWith("acw-convert-cli-")
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

    private static void equal(Object expected, Object actual, String name) {
        if (!expected.equals(actual)) {
            throw new AssertionError(name + ": expected " + expected + " but got " + actual);
        }
    }

    private record Invocation(int exitCode, String stdout, String stderr) {}

    @FunctionalInterface
    private interface ThrowingRunnable { void run() throws Exception; }

    @FunctionalInterface
    private interface ThrowingConsumer<T> { void accept(T value) throws Exception; }
}
