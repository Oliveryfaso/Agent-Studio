package dev.agentconfig.workbench;

import dev.agentconfig.workbench.host.HostRegistry;
import dev.agentconfig.workbench.scan.DiscoveredArtifact;
import dev.agentconfig.workbench.scan.EncodingHint;
import dev.agentconfig.workbench.scan.FindingCode;
import dev.agentconfig.workbench.scan.LineEnding;
import dev.agentconfig.workbench.scan.ReadOnlyWorkspaceScanner;
import dev.agentconfig.workbench.scan.ScanCompletionStatus;
import dev.agentconfig.workbench.scan.ScanLimits;
import dev.agentconfig.workbench.scan.ScanResult;
import dev.agentconfig.workbench.scan.ScanStopReason;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Cross-platform scanner hardening tests with no test-framework dependency. */
public final class PlatformSafetyTests {
    private int passed;
    private int skipped;

    public static void main(String[] args) throws Exception {
        new PlatformSafetyTests().runAll();
    }

    private void runAll() throws Exception {
        run("max depth bounds deep traversal", this::maxDepthBoundsDeepTraversal);
        run("empty directory at max depth remains complete", this::emptyDirectoryAtMaxDepthIsComplete);
        run("entry budget terminates traversal", this::entryBudgetTerminatesTraversal);
        run("encoding BOMs and mixed line endings", this::encodingBomsAndMixedLineEndings);
        run("artifact and finding ordering is deterministic", this::orderingIsDeterministic);
        run("POSIX unreadable artifact is an explicit finding", this::posixUnreadableArtifact);
        run("POSIX read-only workspace remains scannable", this::posixReadOnlyWorkspace);
        System.out.printf("Platform safety tests: %d passed, %d skipped%n", passed, skipped);
    }

    private void maxDepthBoundsDeepTraversal() throws Exception {
        withTempDirectory(root -> {
            Files.writeString(root.resolve("AGENTS.md"), "root", StandardCharsets.UTF_8);
            Path directory = root;
            for (int index = 0; index < 8; index++) {
                directory = Files.createDirectory(directory.resolve("level-" + index));
            }
            Files.writeString(directory.resolve("AGENTS.md"), "too deep", StandardCharsets.UTF_8);

            ScanResult result = scanner(new ScanLimits(3, 1_000, 1024 * 1024, 4096)).scan(root);
            equal(1, result.artifacts().size(), "only the shallow artifact should be scanned");
            equal("AGENTS.md", portable(result.artifacts().get(0).logicalPath()), "shallow artifact");
            equal(ScanCompletionStatus.PARTIAL, result.completionStatus(),
                    "maxDepth truncation must be explicit");
            equal(ScanStopReason.DEPTH_LIMIT_REACHED, result.stopReason(),
                    "maxDepth stop reason");
            check(hasFinding(result, FindingCode.DEPTH_LIMIT_REACHED),
                    "maxDepth finding missing");
            check(result.artifacts().stream()
                    .noneMatch(artifact -> portable(artifact.logicalPath()).contains("level-7")),
                    "artifact beyond maxDepth was visited");
        });
    }

    private void emptyDirectoryAtMaxDepthIsComplete() throws Exception {
        withTempDirectory(root -> {
            Files.writeString(root.resolve("AGENTS.md"), "root", StandardCharsets.UTF_8);
            Files.createDirectories(root.resolve("one/two/three"));

            ScanResult result = scanner(new ScanLimits(3, 1_000, 1024 * 1024, 4096)).scan(root);
            equal(ScanCompletionStatus.COMPLETE, result.completionStatus(),
                    "empty boundary directory should not imply omitted content");
            equal(ScanStopReason.NONE, result.stopReason(), "empty boundary stop reason");
            check(!hasFinding(result, FindingCode.DEPTH_LIMIT_REACHED),
                    "empty boundary produced a depth finding");
        });
    }

    private void entryBudgetTerminatesTraversal() throws Exception {
        withTempDirectory(root -> {
            for (int index = 0; index < 20; index++) {
                Files.writeString(root.resolve(String.format("ordinary-%02d.txt", index)), "ignored");
            }
            Files.writeString(root.resolve("AGENTS.md"), "configuration");

            ScanLimits limits = new ScanLimits(64, 4, 1024 * 1024, 4096);
            ScanResult result = scanner(limits).scan(root);
            check(hasFinding(result, FindingCode.ENTRY_LIMIT_REACHED),
                    "entry limit must produce an explicit blocking finding");
            equal(ScanCompletionStatus.PARTIAL, result.completionStatus(), "entry-limited status");
            equal(ScanStopReason.ENTRY_LIMIT_REACHED, result.stopReason(), "entry-limited reason");
            check(result.artifacts().size() <= limits.maxEntries(),
                    "entry-limited scan returned more artifacts than the budget");
        });
    }

    private void encodingBomsAndMixedLineEndings() throws Exception {
        withTempDirectory(root -> {
            byte[] utf8Mixed = concatenate(
                    new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf},
                    "one\r\ntwo\nthree\rfour".getBytes(StandardCharsets.UTF_8));
            byte[] utf16Le = concatenate(
                    new byte[] {(byte) 0xff, (byte) 0xfe},
                    "one\r\ntwo\n".getBytes(StandardCharsets.UTF_16LE));
            byte[] utf16Be = concatenate(
                    new byte[] {(byte) 0xfe, (byte) 0xff},
                    "one\r\ntwo\n".getBytes(StandardCharsets.UTF_16BE));
            write(root, ".claude/rules/utf8-mixed.md", utf8Mixed);
            write(root, ".claude/rules/utf16-le.md", utf16Le);
            write(root, ".claude/rules/utf16-be.md", utf16Be);

            ScanResult result = scanner(ScanLimits.defaults()).scan(root);
            DiscoveredArtifact mixed = artifact(result, ".claude/rules/utf8-mixed.md");
            equal(EncodingHint.UTF8_BOM, mixed.encodingHint(), "UTF-8 BOM hint");
            equal(LineEnding.MIXED, mixed.lineEnding(), "mixed UTF-8 line endings");

            DiscoveredArtifact littleEndian = artifact(result, ".claude/rules/utf16-le.md");
            equal(EncodingHint.UTF16_LE_BOM, littleEndian.encodingHint(), "UTF-16LE BOM hint");
            equal(LineEnding.UNKNOWN, littleEndian.lineEnding(),
                    "Phase 1 does not infer UTF-16 line endings");

            DiscoveredArtifact bigEndian = artifact(result, ".claude/rules/utf16-be.md");
            equal(EncodingHint.UTF16_BE_BOM, bigEndian.encodingHint(), "UTF-16BE BOM hint");
            equal(LineEnding.UNKNOWN, bigEndian.lineEnding(),
                    "Phase 1 does not infer UTF-16 line endings");
        });
    }

    private void orderingIsDeterministic() throws Exception {
        withTempDirectory(root -> {
            write(root, "zeta/CLAUDE.md", "z".getBytes(StandardCharsets.UTF_8));
            write(root, ".github/copilot-instructions.md", "g".getBytes(StandardCharsets.UTF_8));
            write(root, ".codex/config.toml", "c".getBytes(StandardCharsets.UTF_8));
            write(root, ".claude/rules/z.md", "r".getBytes(StandardCharsets.UTF_8));
            Files.writeString(root.resolve("AGENTS.md"), "a", StandardCharsets.UTF_8);
            Files.createDirectories(root.resolve("CLAUDE.local.md"));
            Files.createDirectories(root.resolve(".claude/settings.json"));

            ScanResult first = scanner(ScanLimits.defaults()).scan(root);
            ScanResult second = scanner(ScanLimits.defaults()).scan(root);

            equal(first.artifacts(), second.artifacts(), "repeat-scan artifacts");
            equal(first.findings(), second.findings(), "repeat-scan findings");

            List<String> artifactPaths = first.artifacts().stream()
                    .map(DiscoveredArtifact::logicalPath).map(PlatformSafetyTests::portable).toList();
            equal(sorted(artifactPaths), artifactPaths, "artifact ordering");
            List<String> findingPaths = first.findings().stream()
                    .map(finding -> portable(finding.logicalPath())).toList();
            equal(sorted(findingPaths), findingPaths, "finding ordering");
        });
    }

    private void posixUnreadableArtifact() throws Exception {
        withTempDirectory(root -> {
            requirePosix(root);
            Path artifact = Files.writeString(root.resolve("AGENTS.md"), "unreadable");
            Set<PosixFilePermission> original = Files.getPosixFilePermissions(artifact);
            try {
                Files.setPosixFilePermissions(artifact, Set.of());
                if (!readIsDenied(artifact)) {
                    skip("current user can bypass POSIX read bits");
                }
                ScanResult result = scanner(ScanLimits.defaults()).scan(root);
                check(hasFinding(result, FindingCode.READ_FAILED)
                                || hasFinding(result, FindingCode.UNREADABLE_FILE),
                        "unreadable artifact must be reported");
                check(result.artifacts().isEmpty(), "unreadable bytes must not become an artifact");
            } finally {
                Files.setPosixFilePermissions(artifact, original);
            }
        });
    }

    private void posixReadOnlyWorkspace() throws Exception {
        withTempDirectory(root -> {
            requirePosix(root);
            Path artifact = Files.writeString(root.resolve("AGENTS.md"), "read-only");
            Set<PosixFilePermission> originalRoot = Files.getPosixFilePermissions(root);
            Set<PosixFilePermission> originalArtifact = Files.getPosixFilePermissions(artifact);
            Set<PosixFilePermission> readOnlyDirectory =
                    PosixFilePermissions.fromString("r-xr-xr-x");
            Set<PosixFilePermission> readOnlyFile = PosixFilePermissions.fromString("r--r--r--");
            try {
                Files.setPosixFilePermissions(artifact, readOnlyFile);
                Files.setPosixFilePermissions(root, readOnlyDirectory);
                ScanResult result = scanner(ScanLimits.defaults()).scan(root);
                equal(1, result.artifacts().size(), "read-only artifact count");
                equal(readOnlyDirectory, Files.getPosixFilePermissions(root),
                        "scanner changed root permissions");
                equal(readOnlyFile, Files.getPosixFilePermissions(artifact),
                        "scanner changed file permissions");
            } finally {
                Files.setPosixFilePermissions(root, originalRoot);
                Files.setPosixFilePermissions(artifact, originalArtifact);
            }
        });
    }

    private static ReadOnlyWorkspaceScanner scanner(ScanLimits limits) {
        return new ReadOnlyWorkspaceScanner(HostRegistry.phaseOneDefaults(), limits);
    }

    private static DiscoveredArtifact artifact(ScanResult result, String relative) {
        return result.artifacts().stream()
                .filter(candidate -> portable(candidate.logicalPath()).equals(relative))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing artifact: " + relative));
    }

    private static boolean hasFinding(ScanResult result, FindingCode code) {
        return result.findings().stream().anyMatch(finding -> finding.code() == code);
    }

    private static List<String> sorted(List<String> input) {
        return input.stream().sorted().toList();
    }

    private static void write(Path root, String relative, byte[] bytes) throws IOException {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private void requirePosix(Path root) throws IOException {
        if (!Files.getFileStore(root).supportsFileAttributeView("posix")) {
            skip("POSIX attributes are unavailable");
        }
    }

    private static boolean readIsDenied(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            input.read();
            return false;
        } catch (AccessDeniedException exception) {
            return true;
        } catch (IOException exception) {
            return true;
        }
    }

    private void withTempDirectory(ThrowingConsumer<Path> test) throws Exception {
        Path root = Files.createTempDirectory("acw-platform-");
        try {
            test.accept(root);
        } finally {
            deleteOwnedTempTree(root);
        }
    }

    private static void deleteOwnedTempTree(Path root) throws IOException {
        String fileName = root.getFileName().toString();
        if (!fileName.startsWith("acw-platform-")
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
        try {
            test.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (SkipTest skip) {
            skipped++;
            System.out.println("SKIP " + name + ": " + skip.getMessage());
        }
    }

    private void skip(String reason) {
        throw new SkipTest(reason);
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
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

    private static final class SkipTest extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private SkipTest(String message) {
            super(message);
        }
    }
}
