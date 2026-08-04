package dev.agentconfig.workbench;

import dev.agentconfig.workbench.cli.Cli;
import dev.agentconfig.workbench.host.AdapterMaturity;
import dev.agentconfig.workbench.host.HostRegistry;
import dev.agentconfig.workbench.host.RoadmapTier;
import dev.agentconfig.workbench.scan.DiscoveredArtifact;
import dev.agentconfig.workbench.scan.EncodingHint;
import dev.agentconfig.workbench.scan.FindingCode;
import dev.agentconfig.workbench.scan.LineEnding;
import dev.agentconfig.workbench.scan.ReadOnlyWorkspaceScanner;
import dev.agentconfig.workbench.scan.ScanLimits;
import dev.agentconfig.workbench.scan.ScanResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class PhaseOneTests {
    private int passed;
    private int skipped;

    public static void main(String[] args) throws Exception {
        new PhaseOneTests().runAll();
    }

    private void runAll() throws Exception {
        run("registry separates roadmap from maturity", this::registrySeparatesRoadmapFromMaturity);
        run("allowlist and metadata scan", this::allowlistAndMetadataScan);
        run("scanner performs zero workspace writes", this::scannerPerformsZeroWorkspaceWrites);
        run("discovered content is inert and absent from JSON", this::discoveredContentIsInert);
        run("outside symlink is blocked", this::outsideSymlinkIsBlocked);
        run("broken and cyclic symlinks are findings", this::brokenAndCyclicSymlinksAreFindings);
        run("special allowlisted directory is blocked", this::specialAllowlistedDirectoryIsBlocked);
        System.out.printf("Phase 1 tests: %d passed, %d skipped%n", passed, skipped);
    }

    private void registrySeparatesRoadmapFromMaturity() {
        HostRegistry registry = HostRegistry.phaseOneDefaults();
        equal(5, registry.hosts().size(), "host count");
        equal(RoadmapTier.CORE, registry.find("codex").orElseThrow().roadmapTier(), "Codex roadmap");
        equal(RoadmapTier.BETA_ADAPTER,
                registry.find("cursor").orElseThrow().roadmapTier(), "Cursor roadmap");
        for (var host : registry.hosts()) {
            equal(AdapterMaturity.INVENTORY, host.adapterMaturity(), host.id() + " maturity");
            check(!host.evidence().isEmpty(), host.id() + " evidence is required");
        }
        check(!registry.supports("codex", AdapterMaturity.READ), "Inventory must not imply READ");
        check(!registry.supports("cursor", AdapterMaturity.APPLY), "Roadmap tier must not imply APPLY");
    }

    private void allowlistAndMetadataScan() throws Exception {
        withTempDirectory(root -> {
            Files.write(root.resolve("AGENTS.md"), "abc".getBytes(StandardCharsets.UTF_8));
            write(root, ".claude/rules/api.md", "first\r\nsecond\r\n".getBytes(StandardCharsets.UTF_8));
            write(root, ".cursor/rules/ui.mdc", "---\nalwaysApply: true\n---\n".getBytes(StandardCharsets.UTF_8));
            write(root, ".github/instructions/java.instructions.md", "Use Java 21\n".getBytes(StandardCharsets.UTF_8));
            write(root, ".agents/skills/release/SKILL.md", "---\nname: release\n---\n".getBytes(StandardCharsets.UTF_8));
            Files.writeString(root.resolve("README.md"), "not allowlisted");
            write(root, ".claude/rules/not-a-rule.txt", "ignored".getBytes(StandardCharsets.UTF_8));

            ScanResult result = scanner().scan(root);
            equal(5, result.artifacts().size(), "artifact count");
            check(result.artifacts().stream().noneMatch(a -> portable(a.logicalPath()).equals("README.md")),
                    "README must not be read as agent configuration");
            check(result.artifacts().stream().noneMatch(a -> portable(a.logicalPath()).endsWith(".txt")),
                    "wrong rule extension must not match");

            DiscoveredArtifact agents = artifact(result, "AGENTS.md");
            equal("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                    agents.sha256(), "raw SHA-256");
            equal(3L, agents.byteSize(), "raw byte size");
            equal(EncodingHint.UTF8, agents.encodingHint(), "UTF-8 hint");
            equal(LineEnding.NONE, agents.lineEnding(), "no line ending hint");
            check(agents.hostIds().contains("codex"), "AGENTS must match Codex");
            check(agents.hostIds().contains("cursor"), "AGENTS must match Cursor compatibility");
            check(agents.hostIds().contains("windsurf-devin"), "AGENTS must match Windsurf/Devin");

            DiscoveredArtifact claudeRule = artifact(result, ".claude/rules/api.md");
            equal(LineEnding.CRLF, claudeRule.lineEnding(), "CRLF hint");
            check(result.findings().isEmpty(), "happy-path scan should have no findings");
        });
    }

    private void scannerPerformsZeroWorkspaceWrites() throws Exception {
        withTempDirectory(root -> {
            write(root, ".codex/config.toml", "sandbox_mode = \"read-only\"\n".getBytes(StandardCharsets.UTF_8));
            write(root, ".claude/settings.json", "{\"permissions\":{}}\n".getBytes(StandardCharsets.UTF_8));
            Map<String, String> before = fingerprint(root);
            ScanResult first = scanner().scan(root);
            Map<String, String> afterFirst = fingerprint(root);
            ScanResult second = scanner().scan(root);
            Map<String, String> afterSecond = fingerprint(root);

            equal(before, afterFirst, "tree changed after first scan");
            equal(before, afterSecond, "tree changed after second scan");
            equal(first.artifacts().stream().map(DiscoveredArtifact::sha256).toList(),
                    second.artifacts().stream().map(DiscoveredArtifact::sha256).toList(),
                    "repeat scan hashes");
        });
    }

    private void discoveredContentIsInert() throws Exception {
        withTempDirectory(root -> {
            Path sentinel = root.getParent().resolve(root.getFileName() + "-must-not-exist");
            String secret = "phase-one-secret-that-must-not-be-logged";
            String inertCommand = "Run: /usr/bin/touch " + sentinel + "\nToken: " + secret;
            Files.writeString(root.resolve("AGENTS.md"), inertCommand, StandardCharsets.UTF_8);

            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            int exit = Cli.phaseOneDefaults().run(
                    new String[] {"scan", root.toString()},
                    new PrintWriter(stdout, true, StandardCharsets.UTF_8),
                    new PrintWriter(stderr, true, StandardCharsets.UTF_8));
            equal(0, exit, "CLI exit");
            check(!Files.exists(sentinel), "discovered command was executed");
            String json = stdout.toString(StandardCharsets.UTF_8);
            check(!json.contains(secret), "raw file content leaked into JSON");
            check(json.contains("\"sha256\""), "JSON must contain metadata hash");
            check(stderr.size() == 0, "happy-path CLI wrote stderr");
        });
    }

    private void outsideSymlinkIsBlocked() throws Exception {
        withTempDirectory(base -> {
            Path root = Files.createDirectory(base.resolve("workspace"));
            Path outside = Files.writeString(base.resolve("outside.md"), "outside");
            Path link = root.resolve(".claude/rules/escape.md");
            Files.createDirectories(link.getParent());
            if (!tryCreateSymlink(link, outside)) {
                skip("outside symlink unsupported on this platform");
                return;
            }
            ScanResult result = scanner().scan(root);
            check(hasFinding(result, FindingCode.SYMLINK_ESCAPE), "outside symlink finding");
            check(result.artifacts().isEmpty(), "outside target must not become an artifact");
        });
    }

    private void brokenAndCyclicSymlinksAreFindings() throws Exception {
        withTempDirectory(root -> {
            Path broken = root.resolve(".claude/rules/broken.md");
            Files.createDirectories(broken.getParent());
            if (!tryCreateSymlink(broken, Path.of("missing-target"))) {
                skip("symlinks unsupported on this platform");
                return;
            }
            Path cycle = root.resolve(".claude/rules/cycle.md");
            tryCreateSymlink(cycle, Path.of("cycle.md"));
            ScanResult result = scanner().scan(root);
            check(hasFinding(result, FindingCode.SYMLINK_BROKEN), "broken symlink finding");
            check(hasFinding(result, FindingCode.SYMLINK_CYCLE), "cyclic symlink finding");
        });
    }

    private void specialAllowlistedDirectoryIsBlocked() throws Exception {
        withTempDirectory(root -> {
            Path fakeFile = Files.createDirectory(root.resolve("AGENTS.md"));
            Files.writeString(fakeFile.resolve("payload"), "must not be visited");
            ScanResult result = scanner().scan(root);
            check(hasFinding(result, FindingCode.UNSUPPORTED_SPECIAL_FILE),
                    "directory at allowlisted file path must be a finding");
            check(result.artifacts().isEmpty(), "directory cannot become an artifact");
        });
    }

    private ReadOnlyWorkspaceScanner scanner() {
        return new ReadOnlyWorkspaceScanner(HostRegistry.phaseOneDefaults(), ScanLimits.defaults());
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

    private static void write(Path root, String relative, byte[] bytes) throws IOException {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    private static boolean tryCreateSymlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException | SecurityException | IOException exception) {
            return false;
        }
    }

    private static Map<String, String> fingerprint(Path root) throws Exception {
        Map<String, String> fingerprint = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                if (path.equals(root)) {
                    continue;
                }
                String relative = portable(root.relativize(path));
                BasicFileAttributes attributes = Files.readAttributes(
                        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink()) {
                    fingerprint.put(relative, "L:" + Files.readSymbolicLink(path));
                } else if (attributes.isDirectory()) {
                    fingerprint.put(relative, "D");
                } else if (attributes.isRegularFile()) {
                    String hash = HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
                    String lastModified = attributes.lastModifiedTime().toString();
                    fingerprint.put(relative, "F:" + attributes.size() + ":" + hash + ":" + lastModified);
                } else {
                    fingerprint.put(relative, "O");
                }
            }
        }
        return fingerprint;
    }

    private void withTempDirectory(ThrowingConsumer<Path> test) throws Exception {
        Path root = Files.createTempDirectory("acw-phase1-");
        try {
            test.accept(root);
        } finally {
            deleteOwnedTempTree(root);
        }
    }

    private static void deleteOwnedTempTree(Path root) throws IOException {
        String fileName = root.getFileName().toString();
        if (!fileName.startsWith("acw-phase1-") || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
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
