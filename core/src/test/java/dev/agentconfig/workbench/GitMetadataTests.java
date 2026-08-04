package dev.agentconfig.workbench;

import dev.agentconfig.workbench.git.GitDirDescriptor;
import dev.agentconfig.workbench.git.GitHead;
import dev.agentconfig.workbench.git.GitMetadata;
import dev.agentconfig.workbench.git.GitMetadataProbe;
import dev.agentconfig.workbench.git.GitProbeFinding;
import dev.agentconfig.workbench.git.GitProbeRequest;
import dev.agentconfig.workbench.git.GitProbeUnknown;
import java.io.IOException;
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
import java.util.Objects;
import java.util.TreeMap;

public final class GitMetadataTests {
    private int passed;
    private int skipped;

    public static void main(String[] args) throws Exception {
        new GitMetadataTests().runAll();
    }

    private void runAll() throws Exception {
        run("missing git entry is explicit", this::missingGitEntryIsExplicit);
        run("directory git entry reports symbolic HEAD", this::directoryReportsSymbolicHead);
        run("detached HEAD reports normalized object ID", this::detachedHeadReportsObjectId);
        run("in-root gitfile pointer is accepted", this::inRootGitFileIsAccepted);
        run("external gitfile requires separate authorization", this::externalGitFileRequiresAuthorization);
        run("bounded linked worktree pointer is accepted", this::boundedLinkedWorktreeIsAccepted);
        run("fake external worktree pointer is rejected", this::fakeExternalWorktreeIsRejected);
        run("git entry symlink is rejected", this::gitEntrySymlinkIsRejected);
        run("HEAD symlink is never opened", this::headSymlinkIsNeverOpened);
        run("probe performs zero workspace writes", this::probePerformsZeroWrites);
        System.out.printf("Git metadata tests: %d passed, %d skipped%n", passed, skipped);
    }

    private void missingGitEntryIsExplicit() throws Exception {
        withTempDirectory(root -> {
            GitMetadata result = probe(root, false);
            check(!result.isGitWorkspace(), "empty directory must not be a Git workspace");
            equal(GitDirDescriptor.Kind.MISSING, result.gitDir().kind(), "gitdir kind");
            check(hasUnknown(result, GitProbeUnknown.Code.NOT_A_GIT_WORKSPACE), "not-git unknown");
            check(hasUnknown(result, GitProbeUnknown.Code.DIRTY_STATE_NOT_PROBED), "dirty unknown");
            equal(GitMetadata.WorktreeState.UNKNOWN_NOT_PROBED,
                    result.worktreeState(), "worktree status");
        });
    }

    private void directoryReportsSymbolicHead() throws Exception {
        withTempDirectory(root -> {
            Path gitDir = Files.createDirectory(root.resolve(".git"));
            Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/main\n", StandardCharsets.UTF_8);
            GitMetadata result = probe(root, false);
            check(result.isGitWorkspace(), "directory .git must be recognized");
            equal(GitDirDescriptor.Kind.DIRECTORY, result.gitDir().kind(), "kind");
            equal(GitDirDescriptor.Location.WITHIN_APPROVED_ROOT,
                    result.gitDir().location(), "location");
            equal(GitHead.Kind.SYMBOLIC_REF, result.head().orElseThrow().kind(), "HEAD kind");
            equal("refs/heads/main", result.head().orElseThrow().value(), "HEAD ref");
            check(result.findings().isEmpty(), "valid direct metadata findings");
        });
    }

    private void detachedHeadReportsObjectId() throws Exception {
        withTempDirectory(root -> {
            String uppercase = "ABCDEF0123456789ABCDEF0123456789ABCDEF01";
            Path gitDir = Files.createDirectory(root.resolve(".git"));
            Files.writeString(gitDir.resolve("HEAD"), uppercase + "\r\n", StandardCharsets.UTF_8);
            GitMetadata result = probe(root, false);
            equal(GitHead.Kind.DETACHED_HASH, result.head().orElseThrow().kind(), "detached kind");
            equal(uppercase.toLowerCase(java.util.Locale.ROOT),
                    result.head().orElseThrow().value(), "normalized hash");
        });
    }

    private void inRootGitFileIsAccepted() throws Exception {
        withTempDirectory(root -> {
            Path admin = Files.createDirectory(root.resolve("git-admin"));
            Files.writeString(admin.resolve("HEAD"), "ref: refs/heads/local\n", StandardCharsets.UTF_8);
            Files.writeString(root.resolve(".git"), "gitdir: git-admin\n", StandardCharsets.UTF_8);
            GitMetadata result = probe(root, false);
            check(result.isGitWorkspace(), "in-root pointer must be accepted");
            equal(GitDirDescriptor.Kind.GITFILE_POINTER, result.gitDir().kind(), "pointer kind");
            equal(GitDirDescriptor.Location.WITHIN_APPROVED_ROOT,
                    result.gitDir().location(), "pointer location");
            equal("refs/heads/local", result.head().orElseThrow().value(), "pointer HEAD");
        });
    }

    private void externalGitFileRequiresAuthorization() throws Exception {
        withTempDirectory(base -> {
            Path workspace = Files.createDirectory(base.resolve("workspace"));
            Path external = Files.createDirectory(base.resolve("external.git"));
            Files.writeString(external.resolve("HEAD"), "ref: refs/heads/private\n", StandardCharsets.UTF_8);
            Files.writeString(workspace.resolve(".git"), "gitdir: " + external + "\n", StandardCharsets.UTF_8);
            GitMetadata result = probe(workspace, false);
            check(!result.isGitWorkspace(), "unauthorized external pointer must be rejected");
            equal(GitDirDescriptor.Location.OUTSIDE_APPROVED_ROOT_REJECTED,
                    result.gitDir().location(), "rejected location");
            check(hasFinding(result, GitProbeFinding.Code.EXTERNAL_METADATA_NOT_AUTHORIZED),
                    "authorization finding");
            check(result.head().isEmpty(), "external HEAD must not be read");
        });
    }

    private void boundedLinkedWorktreeIsAccepted() throws Exception {
        withTempDirectory(base -> {
            Path workspace = Files.createDirectory(base.resolve("workspace"));
            Path common = Files.createDirectory(base.resolve("common.git"));
            Path gitDir = Files.createDirectories(common.resolve("worktrees/w1"));
            Path workspaceGitFile = workspace.resolve(".git");
            Files.writeString(workspaceGitFile, "gitdir: " + gitDir + "\n", StandardCharsets.UTF_8);
            Files.writeString(gitDir.resolve("commondir"), "../..\n", StandardCharsets.UTF_8);
            Files.writeString(gitDir.resolve("gitdir"), workspaceGitFile + "\n", StandardCharsets.UTF_8);
            Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/worktree\n", StandardCharsets.UTF_8);

            GitMetadata result = probe(workspace, true);
            check(result.isGitWorkspace(), "bounded linked worktree must be accepted: " + result);
            equal(GitDirDescriptor.Location.BOUNDED_EXTERNAL_WORKTREE,
                    result.gitDir().location(), "bounded location");
            equal("refs/heads/worktree", result.head().orElseThrow().value(), "worktree HEAD");
            check(result.findings().isEmpty(), "bounded worktree findings");
        });
    }

    private void fakeExternalWorktreeIsRejected() throws Exception {
        withTempDirectory(base -> {
            Path workspace = Files.createDirectory(base.resolve("workspace"));
            Path common = Files.createDirectory(base.resolve("common.git"));
            Path gitDir = Files.createDirectories(common.resolve("worktrees/w1"));
            Files.writeString(workspace.resolve(".git"), "gitdir: " + gitDir + "\n", StandardCharsets.UTF_8);
            Files.writeString(gitDir.resolve("commondir"), "../..\n", StandardCharsets.UTF_8);
            Files.writeString(gitDir.resolve("gitdir"), base.resolve("other/.git") + "\n",
                    StandardCharsets.UTF_8);
            Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/must-not-read\n", StandardCharsets.UTF_8);

            GitMetadata result = probe(workspace, true);
            check(!result.isGitWorkspace(), "fake backlink must fail bounded validation");
            check(hasFinding(result, GitProbeFinding.Code.WORKTREE_POINTER_INVALID),
                    "invalid worktree finding");
            check(result.head().isEmpty(), "HEAD behind invalid boundary must not be read");
        });
    }

    private void gitEntrySymlinkIsRejected() throws Exception {
        withTempDirectory(base -> {
            Path workspace = Files.createDirectory(base.resolve("workspace"));
            Path external = Files.createDirectory(base.resolve("external.git"));
            Files.writeString(external.resolve("HEAD"), "ref: refs/heads/private\n", StandardCharsets.UTF_8);
            if (!tryCreateSymlink(workspace.resolve(".git"), external)) {
                skip("symlinks unsupported on this platform");
                return;
            }
            GitMetadata result = probe(workspace, true);
            check(!result.isGitWorkspace(), "symlink .git must be rejected even with worktree authorization");
            equal(GitDirDescriptor.Kind.SYMLINK_REJECTED, result.gitDir().kind(), "symlink kind");
            check(hasFinding(result, GitProbeFinding.Code.GIT_ENTRY_SYMLINK_REJECTED),
                    "symlink finding");
        });
    }

    private void headSymlinkIsNeverOpened() throws Exception {
        withTempDirectory(base -> {
            Path workspace = Files.createDirectory(base.resolve("workspace"));
            Path gitDir = Files.createDirectory(workspace.resolve(".git"));
            Path externalHead = Files.writeString(base.resolve("outside-head"),
                    "ref: refs/heads/private\n", StandardCharsets.UTF_8);
            if (!tryCreateSymlink(gitDir.resolve("HEAD"), externalHead)) {
                skip("symlinks unsupported on this platform");
                return;
            }
            GitMetadata result = probe(workspace, false);
            check(result.isGitWorkspace(), "administrative directory remains recognizable");
            check(result.head().isEmpty(), "symlink HEAD must not be opened");
            check(hasFinding(result, GitProbeFinding.Code.METADATA_SYMLINK_REJECTED),
                    "HEAD symlink finding");
            check(hasUnknown(result, GitProbeUnknown.Code.HEAD_UNAVAILABLE), "HEAD unknown");
        });
    }

    private void probePerformsZeroWrites() throws Exception {
        withTempDirectory(root -> {
            Path gitDir = Files.createDirectory(root.resolve(".git"));
            Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/main\n", StandardCharsets.UTF_8);
            Files.writeString(root.resolve("user-file.txt"), "untouched", StandardCharsets.UTF_8);
            Map<String, String> before = fingerprint(root);
            GitMetadata first = probe(root, false);
            Map<String, String> afterFirst = fingerprint(root);
            GitMetadata second = probe(root, false);
            Map<String, String> afterSecond = fingerprint(root);
            check(first.isGitWorkspace() && second.isGitWorkspace(), "repeat probes");
            equal(before, afterFirst, "tree changed after first probe");
            equal(before, afterSecond, "tree changed after second probe");
        });
    }

    private static GitMetadata probe(Path root, boolean allowExternalWorktree) {
        return new GitMetadataProbe().probe(new GitProbeRequest(root, allowExternalWorktree));
    }

    private static boolean hasFinding(GitMetadata result, GitProbeFinding.Code code) {
        return result.findings().stream().anyMatch(finding -> finding.code() == code);
    }

    private static boolean hasUnknown(GitMetadata result, GitProbeUnknown.Code code) {
        return result.unknowns().stream().anyMatch(unknown -> unknown.code() == code);
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
                BasicFileAttributes attributes = Files.readAttributes(
                        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (attributes.isDirectory()) {
                    fingerprint.put(relative, "D");
                } else if (attributes.isSymbolicLink()) {
                    fingerprint.put(relative, "L:" + Files.readSymbolicLink(path));
                } else if (attributes.isRegularFile()) {
                    String hash = HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
                    fingerprint.put(relative, "F:" + attributes.size() + ":" + hash + ":"
                            + attributes.lastModifiedTime());
                } else {
                    fingerprint.put(relative, "O");
                }
            }
        }
        return fingerprint;
    }

    private void withTempDirectory(ThrowingConsumer<Path> test) throws Exception {
        Path root = Files.createTempDirectory("acw-git-test-");
        try {
            test.accept(root);
        } finally {
            deleteOwnedTempTree(root);
        }
    }

    private static void deleteOwnedTempTree(Path root) throws IOException {
        if (!root.getFileName().toString().startsWith("acw-git-test-")
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
        } catch (SkipTest exception) {
            skipped++;
            System.out.println("SKIP " + name + ": " + exception.getMessage());
        }
    }

    private void skip(String reason) {
        throw new SkipTest(reason);
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

    private static final class SkipTest extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private SkipTest(String message) {
            super(message);
        }
    }
}
