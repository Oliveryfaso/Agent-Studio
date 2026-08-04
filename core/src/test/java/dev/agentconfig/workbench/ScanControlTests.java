package dev.agentconfig.workbench;

import dev.agentconfig.workbench.host.HostRegistry;
import dev.agentconfig.workbench.scan.FindingCode;
import dev.agentconfig.workbench.scan.ReadOnlyWorkspaceScanner;
import dev.agentconfig.workbench.scan.ScanCancellation;
import dev.agentconfig.workbench.scan.ScanCompletionStatus;
import dev.agentconfig.workbench.scan.ScanLimits;
import dev.agentconfig.workbench.scan.ScanResult;
import dev.agentconfig.workbench.scan.ScanStopReason;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ScanControlTests {
    private int passed;

    public static void main(String[] args) throws Exception {
        new ScanControlTests().runAll();
    }

    private void runAll() throws Exception {
        run("complete scan reports explicit state", this::completeScanReportsExplicitState);
        run("aggregate byte limit returns deterministic prefix", this::byteLimitReturnsDeterministicPrefix);
        run("pre-cancelled scan returns partial result", this::preCancelledScanReturnsPartialResult);
        run("cancellation is observed during streaming", this::cancellationIsObservedDuringStreaming);
        run("entry limit reports partial reason", this::entryLimitReportsPartialReason);
        System.out.printf("Scan control tests: %d passed%n", passed);
    }

    private void completeScanReportsExplicitState() throws Exception {
        withTempDirectory(root -> {
            Files.writeString(root.resolve("AGENTS.md"), "complete", StandardCharsets.UTF_8);
            ScanResult result = scanner(ScanLimits.defaults()).scan(root);
            equal(ScanCompletionStatus.COMPLETE, result.completionStatus(), "completion status");
            equal(ScanStopReason.NONE, result.stopReason(), "completion reason");
            check(result.complete(), "complete convenience accessor");
        });
    }

    private void byteLimitReturnsDeterministicPrefix() throws Exception {
        withTempDirectory(root -> {
            Files.writeString(root.resolve("CLAUDE.md"), "2222", StandardCharsets.UTF_8);
            Files.writeString(root.resolve("AGENTS.md"), "1111", StandardCharsets.UTF_8);
            ScanLimits limits = new ScanLimits(16, 100, 1024, 4, 16);
            ReadOnlyWorkspaceScanner scanner = scanner(limits);

            ScanResult first = scanner.scan(root);
            ScanResult second = scanner.scan(root);

            equal(List.of("AGENTS.md"), artifactPaths(first), "first deterministic prefix");
            equal(artifactPaths(first), artifactPaths(second), "repeat deterministic prefix");
            equal(ScanCompletionStatus.PARTIAL, first.completionStatus(), "byte status");
            equal(ScanStopReason.TOTAL_BYTE_LIMIT_REACHED, first.stopReason(), "byte reason");
            check(hasFinding(first, FindingCode.TOTAL_BYTE_LIMIT_REACHED), "byte limit finding");
        });
    }

    private void preCancelledScanReturnsPartialResult() throws Exception {
        withTempDirectory(root -> {
            Files.writeString(root.resolve("AGENTS.md"), "not read", StandardCharsets.UTF_8);
            ScanResult result = scanner(ScanLimits.defaults()).scan(root, () -> true);

            check(result.artifacts().isEmpty(), "cancelled scan must not add an artifact");
            equal(ScanCompletionStatus.PARTIAL, result.completionStatus(), "cancel status");
            equal(ScanStopReason.CANCELLED, result.stopReason(), "cancel reason");
            check(hasFinding(result, FindingCode.SCAN_CANCELLED), "cancel finding");
        });
    }

    private void cancellationIsObservedDuringStreaming() throws Exception {
        withTempDirectory(root -> {
            Files.writeString(root.resolve("AGENTS.md"), "streamed content", StandardCharsets.UTF_8);
            CancelOnCheck cancellation = new CancelOnCheck(5);
            ScanResult result = scanner(ScanLimits.defaults()).scan(root, cancellation);

            equal(5, cancellation.checks(), "cancellation check count");
            check(result.artifacts().isEmpty(), "partially streamed artifact must be discarded");
            equal(ScanStopReason.CANCELLED, result.stopReason(), "streaming cancel reason");
            check(hasFinding(result, FindingCode.SCAN_CANCELLED), "streaming cancel finding");
        });
    }

    private void entryLimitReportsPartialReason() throws Exception {
        withTempDirectory(root -> {
            Files.writeString(root.resolve("AGENTS.md"), "entry", StandardCharsets.UTF_8);
            ScanLimits limits = new ScanLimits(16, 1, 1024, 1024, 16);
            ScanResult result = scanner(limits).scan(root);

            equal(ScanCompletionStatus.PARTIAL, result.completionStatus(), "entry status");
            equal(ScanStopReason.ENTRY_LIMIT_REACHED, result.stopReason(), "entry reason");
            check(hasFinding(result, FindingCode.ENTRY_LIMIT_REACHED), "entry finding");
        });
    }

    private static ReadOnlyWorkspaceScanner scanner(ScanLimits limits) {
        return new ReadOnlyWorkspaceScanner(HostRegistry.phaseOneDefaults(), limits);
    }

    private static List<String> artifactPaths(ScanResult result) {
        return result.artifacts().stream()
                .map(artifact -> portable(artifact.logicalPath()))
                .toList();
    }

    private static boolean hasFinding(ScanResult result, FindingCode code) {
        return result.findings().stream().anyMatch(finding -> finding.code() == code);
    }

    private void withTempDirectory(ThrowingConsumer<Path> test) throws Exception {
        Path root = Files.createTempDirectory("acw-scan-control-");
        try {
            test.accept(root);
        } finally {
            deleteOwnedTempTree(root);
        }
    }

    private static void deleteOwnedTempTree(Path root) throws IOException {
        String name = root.getFileName().toString();
        if (!name.startsWith("acw-scan-control-")
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
        try {
            test.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (AssertionError error) {
            throw new AssertionError("FAIL " + name + ": " + error.getMessage(), error);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T value) throws Exception;
    }

    private static final class CancelOnCheck implements ScanCancellation {
        private final int cancelAt;
        private int checks;

        CancelOnCheck(int cancelAt) {
            this.cancelAt = cancelAt;
        }

        @Override
        public boolean isCancellationRequested() {
            checks++;
            return checks >= cancelAt;
        }

        int checks() {
            return checks;
        }
    }
}
