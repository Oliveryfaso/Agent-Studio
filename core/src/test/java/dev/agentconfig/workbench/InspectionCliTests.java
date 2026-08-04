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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class InspectionCliTests {
    private int passed;

    public static void main(String[] args) throws Exception {
        new InspectionCliTests().runAll();
    }

    private void runAll() throws Exception {
        run("inspect explains the active Codex instruction without leaking content",
                this::activeInstructionSummary);
        run("inspect has an explicit empty state", this::emptyState);
        run("inspect separates heuristic suggestions", this::heuristicSuggestion);
        run("inspect keeps exact duplicates in the confirmed group", this::exactDuplicate);
        run("inspect reports partial analysis and exits three", this::partialInspection);
        run("inspect performs zero workspace writes", this::zeroWorkspaceWrites);
        run("inspect rejects unsupported hosts", this::unsupportedHost);
        System.out.printf("Inspection CLI tests: %d passed%n", passed);
    }

    private void activeInstructionSummary() throws Exception {
        withTempDirectory(root -> {
            String privateText = "private-inspection-content-42";
            Files.writeString(root.resolve("AGENTS.md"), "- " + privateText + "\n",
                    StandardCharsets.UTF_8);

            Invocation invocation = invoke("inspect", "codex", root.toString(), root.toString());

            equal(0, invocation.exitCode(), "exit");
            check(invocation.stdout().contains("检查状态：完整"), "complete state missing");
            check(invocation.stdout().contains("1. AGENTS.md"), "active source missing");
            check(invocation.stdout().contains("没有修改项目文件"), "zero-write message missing");
            check(!invocation.stdout().contains(privateText), "raw instruction content leaked");
            check(!invocation.stdout().contains(root.toString()), "physical root leaked");
            check(!invocation.stdout().contains("sha256"), "hash detail leaked");
        });
    }

    private void emptyState() throws Exception {
        withTempDirectory(root -> {
            Invocation invocation = invoke("inspect", "codex", root.toString(), root.toString());

            equal(0, invocation.exitCode(), "exit");
            check(invocation.stdout().contains("没有生效的 Codex 项目指令文件"),
                    "empty state missing");
            check(invocation.stdout().contains("未发现重复内容或冲突候选"),
                    "empty findings missing");
        });
    }

    private void heuristicSuggestion() throws Exception {
        withTempDirectory(root -> {
            Path nested = Files.createDirectories(root.resolve("nested"));
            Files.writeString(root.resolve("AGENTS.md"), "- Always run tests.\n",
                    StandardCharsets.UTF_8);
            Files.writeString(nested.resolve("AGENTS.md"), "- always   run tests\n",
                    StandardCharsets.UTF_8);

            Invocation invocation = invoke("inspect", "codex", root.toString(), nested.toString());

            equal(0, invocation.exitCode(), "exit");
            check(invocation.stdout().contains("需要确认（启发式建议，不会自动修改）"),
                    "heuristic heading missing");
            check(invocation.stdout().contains("AGENTS.md:1"), "root reference missing");
            check(invocation.stdout().contains("nested/AGENTS.md:1"), "nested reference missing");
            check(!invocation.stdout().contains("确定问题\n-"),
                    "heuristic result was presented as confirmed");
        });
    }

    private void partialInspection() throws Exception {
        withTempDirectory(root -> {
            Path config = root.resolve("config.toml");
            Files.writeString(config, "project_doc_max_bytes = 'not-a-number'\n",
                    StandardCharsets.UTF_8);

            Invocation invocation = invoke("inspect", "codex", root.toString(), root.toString(),
                    "--codex-config", config.toString());

            equal(3, invocation.exitCode(), "partial exit");
            check(invocation.stdout().contains("检查状态：未完整完成"), "partial state missing");
            check(invocation.stdout().contains("检查限制"), "limits missing");
            check(!invocation.stdout().contains("未发现重复内容或冲突候选"),
                    "partial result must not claim no findings");
            check(!invocation.stdout().contains("当前没有需要立即处理"),
                    "partial result must not claim no problems");
        });
    }

    private void exactDuplicate() throws Exception {
        withTempDirectory(root -> {
            Path nested = Files.createDirectories(root.resolve("nested"));
            Files.writeString(root.resolve("AGENTS.md"), "- Run tests.\n", StandardCharsets.UTF_8);
            Files.writeString(nested.resolve("AGENTS.md"), "- Run tests.\n", StandardCharsets.UTF_8);

            Invocation invocation = invoke("inspect", "codex", root.toString(), nested.toString());

            equal(0, invocation.exitCode(), "exit");
            check(invocation.stdout().contains("确定问题"), "confirmed heading missing");
            check(invocation.stdout().contains("相同的有效指令内容被重复加载"),
                    "exact duplicate missing");
        });
    }

    private void zeroWorkspaceWrites() throws Exception {
        withTempDirectory(root -> {
            Files.writeString(root.resolve("AGENTS.md"), "- Run tests.\n", StandardCharsets.UTF_8);
            Map<String, byte[]> before = snapshot(root);

            Invocation invocation = invoke("inspect", "codex", root.toString(), root.toString());

            equal(0, invocation.exitCode(), "exit");
            Map<String, byte[]> after = snapshot(root);
            equal(before.keySet(), after.keySet(), "workspace paths");
            for (String path : before.keySet()) {
                check(java.util.Arrays.equals(before.get(path), after.get(path)),
                        "workspace bytes changed: " + path);
            }
        });
    }

    private void unsupportedHost() throws Exception {
        withTempDirectory(root -> {
            Invocation invocation = invoke(
                    "inspect", "claude-code", root.toString(), root.toString());
            equal(2, invocation.exitCode(), "unsupported host exit");
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

    private static Map<String, byte[]> snapshot(Path root) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                result.put(root.relativize(path).toString(), Files.readAllBytes(path));
            }
        }
        return result;
    }

    private void withTempDirectory(ThrowingConsumer<Path> test) throws Exception {
        Path root = Files.createTempDirectory("acw-inspection-cli-");
        try {
            test.accept(root);
        } finally {
            deleteOwnedTempTree(root);
        }
    }

    private static void deleteOwnedTempTree(Path root) throws IOException {
        if (!root.getFileName().toString().startsWith("acw-inspection-cli-")
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
