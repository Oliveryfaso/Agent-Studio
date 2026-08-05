package dev.agentconfig.workbench;

import dev.agentconfig.workbench.blueprint.BlueprintPreviewService;
import dev.agentconfig.workbench.skilldraft.CodexSkillDraftPreview;
import dev.agentconfig.workbench.skilldraft.CodexSkillDraftService;
import dev.agentconfig.workbench.transaction.FixtureSkillApplyReceipt;
import dev.agentconfig.workbench.transaction.FixtureSkillChangePlan;
import dev.agentconfig.workbench.transaction.FixtureSkillRollbackReceipt;
import dev.agentconfig.workbench.transaction.FixtureSkillTransactionService;
import dev.agentconfig.workbench.transaction.PreparedFixtureSkillChange;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class FixtureSkillTransactionTests {
    private int passed;
    private int skipped;

    public static void main(String[] args) throws Exception {
        new FixtureSkillTransactionTests().runAll();
    }

    private void runAll() throws Exception {
        run("absent target preview is real and read-only", this::absentPreview);
        run("identical target is a no-op", this::identicalTarget);
        run("fixture create applies and rolls back to absent", this::createAndRollback);
        run("fixture replace restores byte-identical preimage", this::replaceAndRollback);
        run("stale existing preimage is rejected", this::staleExisting);
        run("stale absent preimage is rejected", this::staleAbsent);
        run("approval metadata tampering is rejected", this::approvalTampering);
        run("same-path fixture replacement invalidates approval", this::rootReplacement);
        run("missing parent and unsafe marker are blocked", this::blockedTopology);
        run("ancestor and target links are blocked", this::linkedTopology);
        run("faults before and after move preserve preimage", this::failureInjection);
        run("prepared transaction cannot roll back a later matching edit",
                this::preparedTransactionCannotRollback);
        run("rollback hash guard preserves later edits", this::rollbackHashGuard);
        run("corrupt snapshot blocks rollback", this::corruptSnapshot);
        System.out.printf("Fixture Skill transaction tests: %d passed, %d skipped%n", passed, skipped);
    }

    private void absentPreview() throws Exception {
        withFixture(fixture -> {
            Map<String, String> before = fingerprint(fixture.workspace());
            PreparedFixtureSkillChange preview = fixture.service().prepare(
                    fixture.workspace(), fixture.draft());
            equal(FixtureSkillChangePlan.Status.READY_CREATE, preview.plan().status(), "status");
            contains(preview.exactReplacementDiff().orElseThrow(),
                    "diffMode=REAL_TARGET_EXACT_REPLACEMENT targetState=PROBED");
            contains(preview.exactReplacementDiff().orElseThrow(), "--- /dev/null");
            check(preview.plan().applyEligible(), "ready plan not apply eligible");
            check(!preview.plan().workspaceContentIncluded(), "preview metadata includes content");
            check(!preview.plan().writesPerformed(), "preview claimed a write");
            equal(before, fingerprint(fixture.workspace()), "workspace fingerprint");
            equal(List.of(FixtureSkillTransactionService.STATE_MARKER),
                    relativeEntries(fixture.state()), "state entries");
        });
    }

    private void identicalTarget() throws Exception {
        withFixture(fixture -> {
            Files.write(fixture.target(), fixture.candidateBytes());
            PreparedFixtureSkillChange preview = fixture.service().prepare(
                    fixture.workspace(), fixture.draft());
            equal(FixtureSkillChangePlan.Status.NO_CHANGE, preview.plan().status(), "status");
            check(preview.exactReplacementDiff().isEmpty(), "no-op produced diff");
            check(!preview.plan().applyEligible(), "no-op became apply eligible");
        });
    }

    private void createAndRollback() throws Exception {
        withFixture(fixture -> {
            PreparedFixtureSkillChange preview = fixture.preview();
            FixtureSkillApplyReceipt applied = fixture.apply(preview,
                    FixtureSkillTransactionService.FailurePoint.NONE);
            equal(FixtureSkillApplyReceipt.Status.VERIFIED_APPLIED, applied.status(), "apply");
            check(java.util.Arrays.equals(fixture.candidateBytes(), Files.readAllBytes(fixture.target())),
                    "candidate bytes not written");
            String tx = applied.transactionId().orElseThrow();
            FixtureSkillRollbackReceipt rolledBack = fixture.service().rollback(
                    fixture.workspace(), fixture.state(), tx);
            equal(FixtureSkillRollbackReceipt.Status.ROLLED_BACK, rolledBack.status(), "rollback");
            check(!Files.exists(fixture.target(), LinkOption.NOFOLLOW_LINKS),
                    "new file was not removed to restore absent state");
            FixtureSkillRollbackReceipt repeated = fixture.service().rollback(
                    fixture.workspace(), fixture.state(), tx);
            equal(FixtureSkillRollbackReceipt.Status.ALREADY_ROLLED_BACK,
                    repeated.status(), "repeat rollback");
        });
    }

    private void replaceAndRollback() throws Exception {
        withFixture(fixture -> {
            byte[] original = "---\nname: review-api-change\ndescription: 'Old Skill.'\n---\n\n# Old\n"
                    .getBytes(StandardCharsets.UTF_8);
            Files.write(fixture.target(), original);
            Set<PosixFilePermission> beforeMode = posixPermissions(fixture.target());
            PreparedFixtureSkillChange preview = fixture.preview();
            equal(FixtureSkillChangePlan.Status.READY_REPLACE, preview.plan().status(), "status");
            contains(preview.exactReplacementDiff().orElseThrow(), "-# Old");
            FixtureSkillApplyReceipt applied = fixture.apply(preview,
                    FixtureSkillTransactionService.FailurePoint.NONE);
            FixtureSkillRollbackReceipt rollback = fixture.service().rollback(
                    fixture.workspace(), fixture.state(), applied.transactionId().orElseThrow());
            equal(FixtureSkillRollbackReceipt.Status.ROLLED_BACK, rollback.status(), "rollback");
            check(java.util.Arrays.equals(original, Files.readAllBytes(fixture.target())),
                    "preimage was not restored byte-for-byte");
            if (beforeMode != null) equal(beforeMode, posixPermissions(fixture.target()), "mode");
        });
    }

    private void staleExisting() throws Exception {
        withFixture(fixture -> {
            Files.writeString(fixture.target(), "old\n", StandardCharsets.UTF_8);
            PreparedFixtureSkillChange preview = fixture.preview();
            Files.writeString(fixture.target(), "external edit\n", StandardCharsets.UTF_8);
            FixtureSkillApplyReceipt result = fixture.apply(preview,
                    FixtureSkillTransactionService.FailurePoint.NONE);
            equal(FixtureSkillApplyReceipt.Status.STALE_PREIMAGE, result.status(), "status");
            equal("external edit\n", Files.readString(fixture.target()), "external content");
            equal(List.of(FixtureSkillTransactionService.STATE_MARKER),
                    relativeEntries(fixture.state()), "state entries");
        });
    }

    private void staleAbsent() throws Exception {
        withFixture(fixture -> {
            PreparedFixtureSkillChange preview = fixture.preview();
            Files.writeString(fixture.target(), "external create\n", StandardCharsets.UTF_8);
            FixtureSkillApplyReceipt result = fixture.apply(preview,
                    FixtureSkillTransactionService.FailurePoint.NONE);
            equal(FixtureSkillApplyReceipt.Status.STALE_PREIMAGE, result.status(), "status");
            equal("external create\n", Files.readString(fixture.target()), "external content");
        });
    }

    private void approvalTampering() throws Exception {
        withFixture(fixture -> {
            FixtureSkillChangePlan plan = fixture.preview().plan();
            FixtureSkillChangePlan tampered = new FixtureSkillChangePlan(
                    plan.schemaVersion(), plan.id(), plan.status(), plan.rootIdentitySha256(),
                    plan.logicalPath(), plan.candidateSha256(), plan.preimageSha256(),
                    plan.preimageBytes(), plan.preimageIdentity(), Optional.of("0".repeat(64)),
                    plan.approvalToken(), plan.blockedReason(), plan.fixtureOnly(),
                    plan.workspaceContentIncluded(), plan.writesPerformed(), plan.applyEligible());
            FixtureSkillApplyReceipt result = fixture.service().apply(fixture.workspace(),
                    fixture.state(), fixture.draft(), tampered,
                    FixtureSkillTransactionService.FailurePoint.NONE);
            equal(FixtureSkillApplyReceipt.Status.APPROVAL_MISMATCH,
                    result.status(), "status");
            check(!Files.exists(fixture.target()), "tampered plan wrote target");
        });
    }

    private void rootReplacement() throws Exception {
        withFixture(fixture -> {
            PreparedFixtureSkillChange preview = fixture.preview();
            deleteWithin(fixture.base(), fixture.workspace());
            Files.createDirectory(fixture.workspace());
            Files.writeString(fixture.workspace().resolve(
                    FixtureSkillTransactionService.WORKSPACE_MARKER),
                    FixtureSkillTransactionService.WORKSPACE_MARKER_CONTENT);
            Files.createDirectories(fixture.target().getParent());
            FixtureSkillApplyReceipt result = fixture.apply(preview,
                    FixtureSkillTransactionService.FailurePoint.NONE);
            equal(FixtureSkillApplyReceipt.Status.APPROVAL_MISMATCH,
                    result.status(), "status");
            check(!Files.exists(fixture.target()), "old-root plan wrote replacement root");
        });
    }

    private void blockedTopology() throws Exception {
        withFixture(fixture -> {
            Files.delete(fixture.workspace().resolve(FixtureSkillTransactionService.WORKSPACE_MARKER));
            PreparedFixtureSkillChange invalidMarker = fixture.service().prepare(
                    fixture.workspace(), fixture.draft());
            equal(FixtureSkillChangePlan.Status.BLOCKED, invalidMarker.plan().status(), "marker");
        });
        withFixture(fixture -> {
            Files.delete(fixture.target().getParent());
            PreparedFixtureSkillChange missingParent = fixture.service().prepare(
                    fixture.workspace(), fixture.draft());
            equal(FixtureSkillChangePlan.Status.BLOCKED, missingParent.plan().status(), "parent");
            contains(missingParent.plan().blockedReason().orElseThrow(), "PARENT");
        });
    }

    private void linkedTopology() throws Exception {
        withFixture(fixture -> {
            Path outside = Files.createDirectory(fixture.base().resolve("outside"));
            Files.delete(fixture.target().getParent());
            if (!trySymlink(fixture.target().getParent(), outside)) {
                skip("symlink unsupported");
                return;
            }
            PreparedFixtureSkillChange preview = fixture.service().prepare(
                    fixture.workspace(), fixture.draft());
            equal(FixtureSkillChangePlan.Status.BLOCKED, preview.plan().status(), "ancestor link");
        });
        withFixture(fixture -> {
            Path outside = Files.writeString(fixture.base().resolve("outside.md"), "secret\n");
            if (!trySymlink(fixture.target(), outside)) {
                skip("symlink unsupported");
                return;
            }
            PreparedFixtureSkillChange preview = fixture.service().prepare(
                    fixture.workspace(), fixture.draft());
            equal(FixtureSkillChangePlan.Status.BLOCKED, preview.plan().status(), "target link");
            check(!preview.plan().toString().contains("secret"), "linked content leaked");
        });
    }

    private void failureInjection() throws Exception {
        for (FixtureSkillTransactionService.FailurePoint point : List.of(
                FixtureSkillTransactionService.FailurePoint.AFTER_SNAPSHOT,
                FixtureSkillTransactionService.FailurePoint.AFTER_STAGE,
                FixtureSkillTransactionService.FailurePoint.BEFORE_MOVE,
                FixtureSkillTransactionService.FailurePoint.AFTER_MOVE)) {
            withFixture(fixture -> {
                byte[] original = "old\n".getBytes(StandardCharsets.UTF_8);
                Files.write(fixture.target(), original);
                FixtureSkillApplyReceipt result = fixture.apply(fixture.preview(), point);
                check(result.status() == FixtureSkillApplyReceipt.Status.FAILED_BEFORE_WRITE
                                || result.status() == FixtureSkillApplyReceipt.Status.AUTO_ROLLED_BACK,
                        "unexpected failure status: " + result.status());
                if (point == FixtureSkillTransactionService.FailurePoint.AFTER_MOVE) {
                    check(result.writesPerformed(), "post-move failure hid target write");
                    check(result.atomicMoveUsed(), "post-move failure hid atomic move");
                } else {
                    check(!result.writesPerformed(), "pre-move failure claimed target write");
                }
                check(java.util.Arrays.equals(original, Files.readAllBytes(fixture.target())),
                        point + " changed preimage");
                check(relativeEntries(fixture.workspace()).stream()
                                .noneMatch(path -> path.contains(".acw-s3-stage-")),
                        point + " left staging residue");
            });
        }
    }

    private void preparedTransactionCannotRollback() throws Exception {
        withFixture(fixture -> {
            FixtureSkillApplyReceipt failed = fixture.apply(fixture.preview(),
                    FixtureSkillTransactionService.FailurePoint.AFTER_SNAPSHOT);
            String tx = failed.transactionId().orElseThrow();
            Files.write(fixture.target(), fixture.candidateBytes());
            FixtureSkillRollbackReceipt rollback = fixture.service().rollback(
                    fixture.workspace(), fixture.state(), tx);
            equal(FixtureSkillRollbackReceipt.Status.SNAPSHOT_INVALID,
                    rollback.status(), "status");
            check(Files.exists(fixture.target()), "later matching file was deleted");
        });
    }

    private void rollbackHashGuard() throws Exception {
        withFixture(fixture -> {
            Files.writeString(fixture.target(), "old\n", StandardCharsets.UTF_8);
            FixtureSkillApplyReceipt applied = fixture.apply(fixture.preview(),
                    FixtureSkillTransactionService.FailurePoint.NONE);
            Files.writeString(fixture.target(), "user edit after apply\n", StandardCharsets.UTF_8);
            FixtureSkillRollbackReceipt rollback = fixture.service().rollback(
                    fixture.workspace(), fixture.state(), applied.transactionId().orElseThrow());
            equal(FixtureSkillRollbackReceipt.Status.CURRENT_HASH_MISMATCH,
                    rollback.status(), "status");
            equal("user edit after apply\n", Files.readString(fixture.target()), "user edit");
        });
    }

    private void corruptSnapshot() throws Exception {
        withFixture(fixture -> {
            Files.writeString(fixture.target(), "old\n", StandardCharsets.UTF_8);
            FixtureSkillApplyReceipt applied = fixture.apply(fixture.preview(),
                    FixtureSkillTransactionService.FailurePoint.NONE);
            Path snapshot = fixture.state().resolve(applied.transactionId().orElseThrow())
                    .resolve("preimage.bin");
            Files.writeString(snapshot, "corrupt\n", StandardCharsets.UTF_8);
            FixtureSkillRollbackReceipt rollback = fixture.service().rollback(
                    fixture.workspace(), fixture.state(), applied.transactionId().orElseThrow());
            equal(FixtureSkillRollbackReceipt.Status.SNAPSHOT_INVALID,
                    rollback.status(), "status");
            check(java.util.Arrays.equals(fixture.candidateBytes(), Files.readAllBytes(fixture.target())),
                    "candidate changed after corrupt snapshot refusal");
            check(!rollback.toString().contains("old"), "receipt leaked snapshot content");
        });
    }

    private static CodexSkillDraftPreview draft() throws IOException {
        String request = "repeated-workflow: true\nclear-trigger: true\nsuccess-criteria: true\n"
                + "confirmed-artifact: skill\nconfirmed-scope: project\n"
                + "name: review-api-change\n"
                + "description: Review API changes.\n"
                + "goal: Produce a bounded review.\ninput: Changed files\noutput: Findings\n"
                + "trigger: Use for API changes.\nexclusion: Do not use for UI changes.\n"
                + "boundary-example: A CSS edit is outside scope.\n"
                + "should-trigger: Review endpoint\nshould-trigger: Review schema\n"
                + "should-trigger: Review migration\nshould-not-trigger: Review CSS\n"
                + "should-not-trigger: Draft marketing\nshould-not-trigger: Rename image\n"
                + "step: Inspect contracts\ncompletion: Every contract has a result.\n"
                + "validation: Confirm findings cite inputs.\npermission: NONE\nrisk: LOW\n";
        var blueprint = new BlueprintPreviewService().preview(new ByteArrayInputStream(
                request.getBytes(StandardCharsets.UTF_8)));
        return new CodexSkillDraftService().draft(blueprint);
    }

    private void withFixture(ThrowingConsumer<Fixture> test) throws Exception {
        Path base = Files.createTempDirectory("acw-s3-fixture-");
        try {
            Path workspace = Files.createDirectory(base.resolve("workspace"));
            Path state = Files.createDirectory(base.resolve("state"));
            Files.writeString(workspace.resolve(FixtureSkillTransactionService.WORKSPACE_MARKER),
                    FixtureSkillTransactionService.WORKSPACE_MARKER_CONTENT);
            Files.writeString(state.resolve(FixtureSkillTransactionService.STATE_MARKER),
                    FixtureSkillTransactionService.STATE_MARKER_CONTENT);
            Path targetParent = Files.createDirectories(
                    workspace.resolve(".agents/skills/review-api-change"));
            CodexSkillDraftPreview draft = draft();
            test.accept(new Fixture(base, workspace, state, targetParent.resolve("SKILL.md"), draft,
                    new FixtureSkillTransactionService()));
        } finally {
            deleteOwned(base);
        }
    }

    private record Fixture(Path base, Path workspace, Path state, Path target,
            CodexSkillDraftPreview draft, FixtureSkillTransactionService service) {
        byte[] candidateBytes() { return draft.candidate().orElseThrow().bytes(); }
        PreparedFixtureSkillChange preview() throws IOException {
            return service.prepare(workspace, draft);
        }
        FixtureSkillApplyReceipt apply(PreparedFixtureSkillChange preview,
                FixtureSkillTransactionService.FailurePoint point) throws IOException {
            return service.apply(workspace, state, draft, preview.plan(), point);
        }
    }

    private static Map<String, String> fingerprint(Path root) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                String relative = root.relativize(path).toString();
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    result.put(relative, sha256(Files.readAllBytes(path)));
                } else {
                    result.put(relative, "DIRECTORY");
                }
            }
        }
        return result;
    }

    private static List<String> relativeEntries(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> !path.equals(root)).map(root::relativize)
                    .map(Path::toString).sorted().toList();
        }
    }

    private static Set<PosixFilePermission> posixPermissions(Path path) {
        try {
            return Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | UnsupportedOperationException exception) {
            return null;
        }
    }

    private static boolean trySymlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            return false;
        }
    }

    private static void deleteOwned(Path root) throws IOException {
        if (!root.getFileName().toString().startsWith("acw-s3-fixture-")
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing cleanup outside owned fixture: " + root);
        }
        try (var paths = Files.walk(root)) {
            List<Path> owned = new ArrayList<>(paths.toList());
            owned.sort(Comparator.reverseOrder());
            for (Path path : owned) Files.deleteIfExists(path);
        }
    }

    private static void deleteWithin(Path owner, Path target) throws IOException {
        Path normalizedOwner = owner.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedOwner) || normalizedTarget.equals(normalizedOwner)
                || !Files.isDirectory(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing cleanup outside fixture owner: " + target);
        }
        try (var paths = Files.walk(normalizedTarget)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private void run(String name, ThrowingRunnable test) throws Exception {
        test.run();
        passed++;
        System.out.println("PASS " + name);
    }

    private void skip(String reason) {
        skipped++;
        System.out.println("SKIP " + reason);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void contains(String value, String marker) {
        check(value.contains(marker), "missing: " + marker);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String name) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(name + ": expected " + expected + " but got " + actual);
        }
    }

    @FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }
    @FunctionalInterface private interface ThrowingConsumer<T> { void accept(T value) throws Exception; }
}
