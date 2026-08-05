package dev.agentconfig.workbench.transaction;

import dev.agentconfig.workbench.skilldraft.CodexSkillDraftPreview;
import dev.agentconfig.workbench.skilldraft.CodexSkillDraftPreview.Candidate;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFileAttributeView;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Fixture-only S3 single-file transaction. It refuses ordinary workspaces and has no CLI entry.
 * This proves the transaction protocol; it is not a production recovery store.
 */
public final class FixtureSkillTransactionService {
    public static final String WORKSPACE_MARKER = ".agent-config-workbench-fixture";
    public static final String WORKSPACE_MARKER_CONTENT =
            "agent-config-workbench-s3-fixture-v1\n";
    public static final String STATE_MARKER = ".agent-config-workbench-s3-state";
    public static final String STATE_MARKER_CONTENT =
            "agent-config-workbench-s3-state-v1\n";
    private static final int MAX_BYTES = 128 * 1024;
    private static final String MANIFEST = "manifest.bin";
    private static final String SNAPSHOT = "preimage.bin";

    public PreparedFixtureSkillChange prepare(
            Path authorizedFixtureRoot, CodexSkillDraftPreview draft) throws IOException {
        Candidate candidate = readyCandidate(draft);
        Path root;
        String rootIdentity;
        try {
            root = fixtureRoot(authorizedFixtureRoot);
            rootIdentity = rootIdentity(root);
            Path target = target(root, candidate);
            TargetView view = inspect(root, target);
            if (view.blockedReason().isPresent()) {
                return new PreparedFixtureSkillChange(blocked(rootIdentity, candidate,
                        view.blockedReason().orElseThrow()), Optional.empty());
            }
            if (view.present() && view.sha256().equals(candidate.sha256())) {
                return new PreparedFixtureSkillChange(noChange(rootIdentity, candidate, view),
                        Optional.empty());
            }
            String diff = exactDiff(candidate, view);
            String diffHash = hash(diff);
            FixtureSkillChangePlan.Status status = view.present()
                    ? FixtureSkillChangePlan.Status.READY_REPLACE
                    : FixtureSkillChangePlan.Status.READY_CREATE;
            String planId = planId(rootIdentity, candidate, view, diffHash, status);
            String approval = approval(planId, rootIdentity, candidate, view, diffHash);
            FixtureSkillChangePlan plan = new FixtureSkillChangePlan(1, planId, status,
                    rootIdentity, candidate.logicalPath(), candidate.sha256(),
                    view.present() ? Optional.of(view.sha256()) : Optional.empty(),
                    view.present() ? view.bytes().length : -1,
                    view.present() ? Optional.of(view.identity()) : Optional.empty(),
                    Optional.of(diffHash), Optional.of(approval), Optional.empty(),
                    true, false, false, true);
            return new PreparedFixtureSkillChange(plan, Optional.of(diff));
        } catch (Blocked blocked) {
            rootIdentity = hash(authorizedFixtureRoot.toAbsolutePath().normalize().toString());
            return new PreparedFixtureSkillChange(blocked(rootIdentity, candidate, blocked.code),
                    Optional.empty());
        }
    }

    public FixtureSkillApplyReceipt apply(
            Path authorizedFixtureRoot,
            Path fixtureStateRoot,
            CodexSkillDraftPreview draft,
            FixtureSkillChangePlan approvedPlan,
            FailurePoint failurePoint) throws IOException {
        Candidate candidate = readyCandidate(draft);
        Path root;
        try {
            root = fixtureRoot(authorizedFixtureRoot);
            Path stateRoot = stateRoot(fixtureStateRoot, root);
            if (!validApproval(root, candidate, approvedPlan)) {
                return failure(FixtureSkillApplyReceipt.Status.APPROVAL_MISMATCH,
                        approvedPlan, candidate, Optional.empty(), "APPROVAL_MISMATCH");
            }
            Path target = target(root, candidate);
            TargetView current = inspect(root, target);
            if (!matches(current, approvedPlan)) {
                return failure(FixtureSkillApplyReceipt.Status.STALE_PREIMAGE,
                        approvedPlan, candidate, Optional.empty(), "STALE_PREIMAGE");
            }
            String actualDiffSha256 = hash(exactDiff(candidate, current));
            if (!approvedPlan.diffSha256().orElse("").equals(actualDiffSha256)) {
                return failure(FixtureSkillApplyReceipt.Status.APPROVAL_MISMATCH,
                        approvedPlan, candidate, Optional.empty(), "ACTUAL_DIFF_MISMATCH");
            }

            String transactionId = UUID.randomUUID().toString();
            Path transactionDirectory = stateRoot.resolve(transactionId);
            Files.createDirectory(transactionDirectory);
            Snapshot snapshot = snapshot(transactionDirectory, current);
            String targetPermissions = current.present()
                    ? current.permissions() : newTargetPermissions(target.getParent());
            Manifest manifest = new Manifest(transactionId, approvedPlan.id(),
                    approvedPlan.rootIdentitySha256(), candidate.logicalPath(), current.present(),
                    current.present() ? current.sha256() : "",
                    current.present() ? current.identity() : "", candidate.sha256(),
                    targetPermissions, ManifestState.PREPARED);
            writeManifest(transactionDirectory, manifest, false);
            if (failurePoint == FailurePoint.AFTER_SNAPSHOT) {
                return failure(FixtureSkillApplyReceipt.Status.FAILED_BEFORE_WRITE,
                        approvedPlan, candidate, Optional.of(transactionId), "FAULT_AFTER_SNAPSHOT");
            }

            Path stage = target.getParent().resolve(stageLeaf(transactionId));
            boolean moved = false;
            boolean preserveStageForRecovery = false;
            boolean commitIntentPersisted = false;
            try {
                writeForcedNew(stage, candidate.bytes());
                setPermissions(stage, targetPermissions);
                if (!hash(readBounded(stage)).equals(candidate.sha256())) {
                    return failure(FixtureSkillApplyReceipt.Status.FAILED_BEFORE_WRITE,
                            approvedPlan, candidate, Optional.of(transactionId),
                            "STAGE_HASH_MISMATCH");
                }
                if (failurePoint == FailurePoint.AFTER_STAGE) {
                    return failure(FixtureSkillApplyReceipt.Status.FAILED_BEFORE_WRITE,
                            approvedPlan, candidate, Optional.of(transactionId), "FAULT_AFTER_STAGE");
                }
                TargetView beforeMove = inspect(root, target);
                if (!matches(beforeMove, approvedPlan)) {
                    return failure(FixtureSkillApplyReceipt.Status.STALE_PREIMAGE,
                            approvedPlan, candidate, Optional.of(transactionId),
                            "STALE_BEFORE_MOVE");
                }
                if (failurePoint == FailurePoint.BEFORE_MOVE) {
                    return failure(FixtureSkillApplyReceipt.Status.FAILED_BEFORE_WRITE,
                            approvedPlan, candidate, Optional.of(transactionId), "FAULT_BEFORE_MOVE");
                }
                manifest = manifest.withState(ManifestState.COMMIT_INTENT);
                writeManifest(transactionDirectory, manifest, true);
                commitIntentPersisted = true;
                if (failurePoint == FailurePoint.AFTER_COMMIT_INTENT) {
                    preserveStageForRecovery = true;
                    return recoveryRequired(approvedPlan, candidate, transactionId,
                            snapshot.sha256(), false, "FAULT_AFTER_COMMIT_INTENT");
                }
                moveAtomic(stage, target, current.present());
                moved = true;
                if (failurePoint == FailurePoint.AFTER_MOVE_BEFORE_APPLIED_MANIFEST) {
                    return recoveryRequired(approvedPlan, candidate, transactionId,
                            snapshot.sha256(), true,
                            "FAULT_AFTER_MOVE_BEFORE_APPLIED_MANIFEST");
                }
                manifest = manifest.withState(ManifestState.APPLIED);
                writeManifest(transactionDirectory, manifest, true);
                if (failurePoint == FailurePoint.AFTER_MOVE) {
                    throw new IOException("injected failure after move");
                }
                TargetView written = inspect(root, target);
                if (!written.present() || !written.sha256().equals(candidate.sha256())
                        || !written.permissions().equals(targetPermissions)) {
                    throw new IOException("post-write verification failed");
                }
                return new FixtureSkillApplyReceipt(1,
                        FixtureSkillApplyReceipt.Status.VERIFIED_APPLIED,
                        Optional.of(transactionId), approvedPlan.id(), candidate.logicalPath(),
                        candidate.sha256(), snapshot.sha256(), true, true, true, true,
                        "FIXTURE_APPLY_VERIFIED");
            } catch (AtomicMoveNotSupportedException exception) {
                if (commitIntentPersisted) {
                    preserveStageForRecovery = true;
                    return recoveryRequired(approvedPlan, candidate, transactionId,
                            snapshot.sha256(), false, "ATOMIC_MOVE_REQUIRES_RECOVERY");
                }
                return failure(FixtureSkillApplyReceipt.Status.FAILED_BEFORE_WRITE,
                        approvedPlan, candidate, Optional.of(transactionId),
                        "ATOMIC_MOVE_UNSUPPORTED");
            } catch (IOException exception) {
                if (moved) {
                    boolean restored = restore(root, target, manifest, transactionDirectory);
                    if (restored) {
                        writeManifest(transactionDirectory,
                                manifest.withState(ManifestState.ROLLED_BACK), true);
                        return postMoveFailure(FixtureSkillApplyReceipt.Status.AUTO_ROLLED_BACK,
                                approvedPlan, candidate, transactionId, snapshot.sha256(),
                                "POST_MOVE_FAILURE_AUTO_ROLLED_BACK");
                    }
                    return postMoveFailure(FixtureSkillApplyReceipt.Status.RECOVERY_REQUIRED,
                            approvedPlan, candidate, transactionId, snapshot.sha256(),
                            "POST_MOVE_FAILURE_RECOVERY_REQUIRED");
                }
                if (commitIntentPersisted) {
                    preserveStageForRecovery = true;
                    return recoveryRequired(approvedPlan, candidate, transactionId,
                            snapshot.sha256(), false, "COMMIT_INTENT_REQUIRES_RECOVERY");
                }
                return failure(FixtureSkillApplyReceipt.Status.FAILED_BEFORE_WRITE,
                        approvedPlan, candidate, Optional.of(transactionId),
                        "WRITE_FAILED");
            } finally {
                if (!moved && !preserveStageForRecovery) Files.deleteIfExists(stage);
            }
        } catch (Blocked blocked) {
            return failure(FixtureSkillApplyReceipt.Status.APPROVAL_MISMATCH,
                    approvedPlan, candidate, Optional.empty(), blocked.code);
        }
    }

    public FixtureSkillRollbackReceipt rollback(
            Path authorizedFixtureRoot, Path fixtureStateRoot, String transactionId)
            throws IOException {
        if (transactionId == null || !transactionId.matches("[0-9a-f-]{36}")) {
            throw new IllegalArgumentException("invalid transaction id");
        }
        Path root;
        Path stateRoot;
        try {
            root = fixtureRoot(authorizedFixtureRoot);
            stateRoot = stateRoot(fixtureStateRoot, root);
        } catch (Blocked blocked) {
            return rollbackReceipt(transactionId, "unknown", false,
                    FixtureSkillRollbackReceipt.Status.SNAPSHOT_INVALID, blocked.code);
        }
        Path transactionDirectory = stateRoot.resolve(transactionId);
        if (Files.isSymbolicLink(transactionDirectory)
                || !Files.isDirectory(transactionDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return rollbackReceipt(transactionId, "unknown", false,
                    FixtureSkillRollbackReceipt.Status.SNAPSHOT_INVALID, "MANIFEST_MISSING");
        }
        Manifest manifest;
        try {
            manifest = readManifest(transactionDirectory);
        } catch (IOException | IllegalArgumentException exception) {
            return rollbackReceipt(transactionId, "unknown", false,
                    FixtureSkillRollbackReceipt.Status.SNAPSHOT_INVALID, "MANIFEST_INVALID");
        }
        if (!manifest.transactionId.equals(transactionId)
                || !manifest.rootIdentity.equals(rootIdentity(root))) {
            return rollbackReceipt(transactionId, manifest.logicalPath, false,
                    FixtureSkillRollbackReceipt.Status.SNAPSHOT_INVALID, "MANIFEST_SCOPE_MISMATCH");
        }
        if (manifest.state == ManifestState.PREPARED
                || manifest.state == ManifestState.COMMIT_INTENT) {
            return rollbackReceipt(transactionId, manifest.logicalPath, false,
                    FixtureSkillRollbackReceipt.Status.RECOVERY_REQUIRED,
                    "TRANSACTION_REQUIRES_RECOVERY");
        }
        if (manifest.state == ManifestState.ABORTED) {
            return rollbackReceipt(transactionId, manifest.logicalPath, false,
                    FixtureSkillRollbackReceipt.Status.SNAPSHOT_INVALID,
                    "TRANSACTION_ABORTED_WITHOUT_APPLY");
        }
        Path target;
        try {
            target = targetFromManifest(root, manifest.logicalPath);
        } catch (Blocked blocked) {
            return rollbackReceipt(transactionId, manifest.logicalPath, false,
                    FixtureSkillRollbackReceipt.Status.SNAPSHOT_INVALID, blocked.code);
        }
        if (manifest.state == ManifestState.ROLLED_BACK) {
            TargetView current;
            try {
                current = inspect(root, target);
            } catch (Blocked blocked) {
                return rollbackReceipt(transactionId, manifest.logicalPath, false,
                        FixtureSkillRollbackReceipt.Status.RECOVERY_REQUIRED, blocked.code);
            }
            boolean restored = manifest.existedBefore
                    ? current.present() && current.sha256().equals(manifest.preimageSha256)
                    : !current.present() && current.blockedReason().isEmpty();
            return rollbackReceipt(transactionId, manifest.logicalPath, false,
                    restored ? FixtureSkillRollbackReceipt.Status.ALREADY_ROLLED_BACK
                            : FixtureSkillRollbackReceipt.Status.RECOVERY_REQUIRED,
                    restored ? "ALREADY_ROLLED_BACK" : "ROLLED_BACK_STATE_CHANGED");
        }
        TargetView current;
        try {
            current = inspect(root, target);
        } catch (Blocked blocked) {
            return rollbackReceipt(transactionId, manifest.logicalPath, false,
                    FixtureSkillRollbackReceipt.Status.RECOVERY_REQUIRED, blocked.code);
        }
        if (!current.present() || !current.sha256().equals(manifest.candidateSha256)) {
            return rollbackReceipt(transactionId, manifest.logicalPath, false,
                    FixtureSkillRollbackReceipt.Status.CURRENT_HASH_MISMATCH,
                    "CURRENT_HASH_MISMATCH");
        }
        boolean restored = restore(root, target, manifest, transactionDirectory);
        if (!restored) {
            return rollbackReceipt(transactionId, manifest.logicalPath, false,
                    FixtureSkillRollbackReceipt.Status.SNAPSHOT_INVALID, "SNAPSHOT_INVALID");
        }
        writeManifest(transactionDirectory, manifest.withState(ManifestState.ROLLED_BACK), true);
        return rollbackReceipt(transactionId, manifest.logicalPath, true,
                FixtureSkillRollbackReceipt.Status.ROLLED_BACK, "ROLLBACK_VERIFIED");
    }

    public FixtureSkillRecoveryReceipt recoverTransaction(
            Path authorizedFixtureRoot, Path fixtureStateRoot, String transactionId)
            throws IOException {
        if (transactionId == null || !transactionId.matches("[0-9a-f-]{36}")) {
            throw new IllegalArgumentException("invalid transaction id");
        }
        Path root;
        Path stateRoot;
        try {
            root = fixtureRoot(authorizedFixtureRoot);
            stateRoot = stateRoot(fixtureStateRoot, root);
        } catch (Blocked blocked) {
            return recoveryReceipt(transactionId, "unknown",
                    FixtureSkillRecoveryReceipt.Status.INVALID_TRANSACTION,
                    false, false, false, blocked.code);
        }
        Path transactionDirectory = stateRoot.resolve(transactionId);
        if (Files.isSymbolicLink(transactionDirectory)
                || !Files.isDirectory(transactionDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return recoveryReceipt(transactionId, "unknown",
                    FixtureSkillRecoveryReceipt.Status.INVALID_TRANSACTION,
                    false, false, false, "TRANSACTION_DIRECTORY_INVALID");
        }
        Manifest manifest;
        try {
            manifest = readManifest(transactionDirectory);
        } catch (IOException | IllegalArgumentException exception) {
            return recoveryReceipt(transactionId, "unknown",
                    FixtureSkillRecoveryReceipt.Status.INVALID_TRANSACTION,
                    false, false, false, "MANIFEST_INVALID");
        }
        if (!manifest.transactionId.equals(transactionId)
                || !manifest.rootIdentity.equals(rootIdentity(root))) {
            return recoveryReceipt(transactionId, manifest.logicalPath,
                    FixtureSkillRecoveryReceipt.Status.INVALID_TRANSACTION,
                    false, false, false, "MANIFEST_SCOPE_MISMATCH");
        }
        Path target;
        try {
            target = targetFromManifest(root, manifest.logicalPath);
        } catch (Blocked blocked) {
            return recoveryReceipt(transactionId, manifest.logicalPath,
                    FixtureSkillRecoveryReceipt.Status.INVALID_TRANSACTION,
                    false, false, false, blocked.code);
        }
        TargetView current;
        try {
            current = inspect(root, target);
        } catch (Blocked blocked) {
            return recoveryReceipt(transactionId, manifest.logicalPath,
                    FixtureSkillRecoveryReceipt.Status.RECOVERY_REQUIRED,
                    false, false, false, blocked.code);
        }
        Path stage = target.getParent().resolve(stageLeaf(transactionId));
        StageState stageState = stageState(
                stage, manifest.candidateSha256, manifest.permissions);
        return switch (manifest.state) {
            case PREPARED -> recoverPrepared(transactionDirectory, manifest, current,
                    stage, stageState);
            case COMMIT_INTENT -> recoverCommitIntent(root, transactionDirectory, manifest,
                    target, current, stage, stageState);
            case APPLIED -> recoverApplied(transactionDirectory, manifest, current);
            case ABORTED -> matchesPreimage(current, manifest)
                    ? recoveryReceipt(transactionId, manifest.logicalPath,
                            FixtureSkillRecoveryReceipt.Status.ALREADY_ABORTED,
                            false, false, false, "ALREADY_ABORTED")
                    : recoveryReceipt(transactionId, manifest.logicalPath,
                            FixtureSkillRecoveryReceipt.Status.RECOVERY_REQUIRED,
                            false, false, false, "ABORTED_TARGET_CHANGED");
            case ROLLED_BACK -> matchesRestoredPreimage(current, manifest)
                    ? recoveryReceipt(transactionId, manifest.logicalPath,
                            FixtureSkillRecoveryReceipt.Status.ALREADY_ROLLED_BACK,
                            false, false, false, "ALREADY_ROLLED_BACK")
                    : recoveryReceipt(transactionId, manifest.logicalPath,
                            FixtureSkillRecoveryReceipt.Status.RECOVERY_REQUIRED,
                            false, false, false, "ROLLED_BACK_TARGET_CHANGED");
        };
    }

    public enum FailurePoint {
        NONE, AFTER_SNAPSHOT, AFTER_STAGE, BEFORE_MOVE, AFTER_COMMIT_INTENT,
        AFTER_MOVE_BEFORE_APPLIED_MANIFEST, AFTER_MOVE
    }

    private static boolean restore(Path root, Path target, Manifest manifest,
            Path transactionDirectory) throws IOException {
        TargetView current;
        try {
            current = inspect(root, target);
        } catch (Blocked blocked) {
            return false;
        }
        if (!current.present() || !current.sha256().equals(manifest.candidateSha256)) return false;
        if (!manifest.existedBefore) {
            Files.delete(target);
            return !Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        }
        Path snapshotPath = transactionDirectory.resolve(SNAPSHOT);
        if (Files.isSymbolicLink(snapshotPath)
                || !Files.isRegularFile(snapshotPath, LinkOption.NOFOLLOW_LINKS)) return false;
        byte[] original = readBounded(snapshotPath);
        if (!hash(original).equals(manifest.preimageSha256)) return false;
        Path stage = Files.createTempFile(target.getParent(), ".acw-s3-rollback-", ".tmp");
        try {
            writeForced(stage, original);
            setPermissions(stage, manifest.permissions);
            moveAtomic(stage, target, true);
        } finally {
            Files.deleteIfExists(stage);
        }
        TargetView restored;
        try {
            restored = inspect(root, target);
        } catch (Blocked blocked) {
            return false;
        }
        return restored.present() && restored.sha256().equals(manifest.preimageSha256);
    }

    private static FixtureSkillRecoveryReceipt recoverPrepared(Path transactionDirectory,
            Manifest manifest, TargetView current, Path stage, StageState stageState)
            throws IOException {
        if (!matchesPreimage(current, manifest) || stageState == StageState.INVALID) {
            return recoveryReceipt(manifest.transactionId, manifest.logicalPath,
                    FixtureSkillRecoveryReceipt.Status.RECOVERY_REQUIRED,
                    false, false, false, "PREPARED_STATE_AMBIGUOUS");
        }
        if (stageState == StageState.CANDIDATE) Files.delete(stage);
        writeManifest(transactionDirectory, manifest.withState(ManifestState.ABORTED), true);
        return recoveryReceipt(manifest.transactionId, manifest.logicalPath,
                FixtureSkillRecoveryReceipt.Status.ABORTED_PREPARED,
                false, true, false, "PREPARED_ABORTED");
    }

    private static FixtureSkillRecoveryReceipt recoverCommitIntent(Path root,
            Path transactionDirectory, Manifest manifest, Path target, TargetView current,
            Path stage, StageState stageState) throws IOException {
        if (manifest.existedBefore && !validSnapshot(transactionDirectory, manifest)) {
            return recoveryReceipt(manifest.transactionId, manifest.logicalPath,
                    FixtureSkillRecoveryReceipt.Status.RECOVERY_REQUIRED,
                    false, false, false, "SNAPSHOT_INVALID");
        }
        if (current.present() && current.sha256().equals(manifest.candidateSha256)
                && current.permissions().equals(manifest.permissions)
                && stageState == StageState.ABSENT) {
            writeManifest(transactionDirectory, manifest.withState(ManifestState.APPLIED), true);
            return recoveryReceipt(manifest.transactionId, manifest.logicalPath,
                    FixtureSkillRecoveryReceipt.Status.FINALIZED_APPLY,
                    false, true, true, "APPLY_MANIFEST_FINALIZED");
        }
        if (matchesPreimage(current, manifest) && stageState == StageState.CANDIDATE) {
            moveAtomic(stage, target, manifest.existedBefore);
            TargetView written;
            try {
                written = inspect(root, target);
            } catch (Blocked blocked) {
                return recoveryReceipt(manifest.transactionId, manifest.logicalPath,
                        FixtureSkillRecoveryReceipt.Status.RECOVERY_REQUIRED,
                        true, false, false, blocked.code);
            }
            if (!written.present() || !written.sha256().equals(manifest.candidateSha256)
                    || !written.permissions().equals(manifest.permissions)) {
                return recoveryReceipt(manifest.transactionId, manifest.logicalPath,
                        FixtureSkillRecoveryReceipt.Status.RECOVERY_REQUIRED,
                        true, false, false, "RECOVERED_TARGET_INVALID");
            }
            writeManifest(transactionDirectory, manifest.withState(ManifestState.APPLIED), true);
            return recoveryReceipt(manifest.transactionId, manifest.logicalPath,
                    FixtureSkillRecoveryReceipt.Status.COMPLETED_APPLY,
                    true, true, true, "APPLY_COMPLETED_FROM_INTENT");
        }
        return recoveryReceipt(manifest.transactionId, manifest.logicalPath,
                FixtureSkillRecoveryReceipt.Status.RECOVERY_REQUIRED,
                false, false, false, "COMMIT_INTENT_AMBIGUOUS");
    }

    private static boolean matchesPreimage(TargetView current, Manifest manifest) {
        if (current.blockedReason().isPresent()) return false;
        if (!manifest.existedBefore) return !current.present();
        return current.present() && current.sha256().equals(manifest.preimageSha256)
                && current.identity().equals(manifest.preimageIdentity);
    }

    private static boolean matchesRestoredPreimage(TargetView current, Manifest manifest) {
        if (current.blockedReason().isPresent()) return false;
        if (!manifest.existedBefore) return !current.present();
        return current.present() && current.sha256().equals(manifest.preimageSha256);
    }

    private static FixtureSkillRecoveryReceipt recoverApplied(Path transactionDirectory,
            Manifest manifest, TargetView current) throws IOException {
        boolean snapshotValid = !manifest.existedBefore
                || validSnapshot(transactionDirectory, manifest);
        if (current.present() && current.sha256().equals(manifest.candidateSha256)
                && current.permissions().equals(manifest.permissions) && snapshotValid) {
            return recoveryReceipt(manifest.transactionId, manifest.logicalPath,
                    FixtureSkillRecoveryReceipt.Status.ALREADY_APPLIED,
                    false, false, true, "ALREADY_APPLIED");
        }
        return recoveryReceipt(manifest.transactionId, manifest.logicalPath,
                FixtureSkillRecoveryReceipt.Status.RECOVERY_REQUIRED,
                false, false, false, snapshotValid
                        ? "APPLIED_TARGET_CHANGED" : "SNAPSHOT_INVALID");
    }

    private static boolean validSnapshot(Path transactionDirectory, Manifest manifest) {
        try {
            Path snapshot = transactionDirectory.resolve(SNAPSHOT);
            if (Files.isSymbolicLink(snapshot)
                    || !Files.isRegularFile(snapshot, LinkOption.NOFOLLOW_LINKS)) return false;
            return hash(readBounded(snapshot)).equals(manifest.preimageSha256);
        } catch (IOException exception) {
            return false;
        }
    }

    private static StageState stageState(
            Path stage, String candidateSha256, String expectedPermissions) {
        try {
            if (!Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) return StageState.ABSENT;
            if (Files.isSymbolicLink(stage)
                    || !Files.isRegularFile(stage, LinkOption.NOFOLLOW_LINKS)
                    || unsafeLink(stage)) return StageState.INVALID;
            return hash(readBounded(stage)).equals(candidateSha256)
                            && permissions(stage).equals(expectedPermissions)
                    ? StageState.CANDIDATE : StageState.INVALID;
        } catch (IOException exception) {
            return StageState.INVALID;
        }
    }

    private static String stageLeaf(String transactionId) {
        if (transactionId == null || !transactionId.matches("[0-9a-f-]{36}")) {
            throw new IllegalArgumentException("invalid transaction id");
        }
        return ".acw-s3-stage-" + transactionId + ".tmp";
    }

    private static Snapshot snapshot(Path transactionDirectory, TargetView current)
            throws IOException {
        if (!current.present()) return new Snapshot(Optional.empty());
        Path path = transactionDirectory.resolve(SNAPSHOT);
        writeForcedNew(path, current.bytes());
        byte[] verify = readBounded(path);
        if (!hash(verify).equals(current.sha256())) throw new IOException("snapshot verification");
        return new Snapshot(Optional.of(current.sha256()));
    }

    private static TargetView inspect(Path root, Path target) throws IOException, Blocked {
        safeParentChain(root, target.getParent());
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return TargetView.absent();
        if (unsafeLink(target)) return TargetView.blocked("TARGET_LINK_OR_REPARSE");
        BasicFileAttributes before = Files.readAttributes(
                target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.isOther() || before.size() > MAX_BYTES) {
            return TargetView.blocked("TARGET_NOT_BOUNDED_REGULAR_FILE");
        }
        byte[] bytes = readBounded(target);
        BasicFileAttributes after = Files.readAttributes(
                target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        String permissions = permissions(target);
        if (!same(before, after)) return TargetView.blocked("TARGET_CHANGED_DURING_PROBE");
        if (bytes.length > 0 && !strictSkillText(bytes)) {
            return TargetView.blocked("TARGET_NOT_STRICT_UTF8_LF");
        }
        String sha = hash(bytes);
        String identity = hash(tuple(Long.toString(after.size()),
                Long.toString(after.lastModifiedTime().toMillis()),
                String.valueOf(after.fileKey()), permissions));
        return new TargetView(true, bytes, sha, identity, permissions, Optional.empty());
    }

    private static boolean strictSkillText(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            return !text.startsWith("\ufeff") && !text.contains("\r") && text.endsWith("\n");
        } catch (CharacterCodingException exception) {
            return false;
        }
    }

    private static Path fixtureRoot(Path supplied) throws IOException, Blocked {
        Path absolute = supplied.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute)) throw new Blocked("FIXTURE_ROOT_IS_LINK");
        Path root = absolute.toRealPath();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || unsafeLink(root)) {
            throw new Blocked("FIXTURE_ROOT_INVALID");
        }
        marker(root.resolve(WORKSPACE_MARKER), WORKSPACE_MARKER_CONTENT);
        return root;
    }

    private static Path stateRoot(Path supplied, Path fixtureRoot) throws IOException, Blocked {
        Path absolute = supplied.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute)) throw new Blocked("STATE_ROOT_IS_LINK");
        Path root = absolute.toRealPath();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || unsafeLink(root)
                || root.startsWith(fixtureRoot) || fixtureRoot.startsWith(root)) {
            throw new Blocked("STATE_ROOT_INVALID_OR_NOT_SEPARATE");
        }
        marker(root.resolve(STATE_MARKER), STATE_MARKER_CONTENT);
        return root;
    }

    private static void marker(Path marker, String expected) throws IOException, Blocked {
        if (Files.isSymbolicLink(marker)
                || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                || Files.size(marker) > 128
                || !Files.readString(marker, StandardCharsets.UTF_8).equals(expected)) {
            throw new Blocked("FIXTURE_MARKER_INVALID");
        }
    }

    private static Path target(Path root, Candidate candidate) throws IOException, Blocked {
        if (!candidate.logicalPath().matches(
                "\\.agents/skills/[a-z0-9]+(?:-[a-z0-9]+)*/SKILL\\.md")
                || candidate.logicalPath().contains(":")) {
            throw new Blocked("TARGET_PATH_INVALID");
        }
        Path target = root.resolve(candidate.logicalPath()).normalize();
        if (!target.startsWith(root)) throw new Blocked("TARGET_OUTSIDE_ROOT");
        safeParentChain(root, target.getParent());
        return target;
    }

    private static Path targetFromManifest(Path root, String logicalPath)
            throws IOException, Blocked {
        if (logicalPath == null || !logicalPath.matches(
                "\\.agents/skills/[a-z0-9]+(?:-[a-z0-9]+)*/SKILL\\.md")
                || logicalPath.contains(":")) {
            throw new Blocked("MANIFEST_TARGET_PATH_INVALID");
        }
        Path target = root.resolve(logicalPath).normalize();
        if (!target.startsWith(root)) throw new Blocked("MANIFEST_TARGET_OUTSIDE_ROOT");
        safeParentChain(root, target.getParent());
        return target;
    }

    private static void safeParentChain(Path root, Path parent) throws IOException, Blocked {
        if (parent == null || !parent.startsWith(root)) throw new Blocked("PARENT_OUTSIDE_ROOT");
        Path cursor = root;
        for (Path segment : root.relativize(parent)) {
            cursor = cursor.resolve(segment);
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)
                    || unsafeLink(cursor)) {
                throw new Blocked("PARENT_MISSING_OR_UNSAFE");
            }
        }
    }

    private static boolean unsafeLink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) return true;
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isOther()) return true;
        Path followed = path.toRealPath();
        Path noFollow = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        return !followed.equals(noFollow);
    }

    private static boolean validApproval(
            Path root, Candidate candidate, FixtureSkillChangePlan plan) throws IOException {
        if (!plan.applyEligible() || !plan.rootIdentitySha256().equals(rootIdentity(root))
                || !plan.logicalPath().equals(candidate.logicalPath())
                || !plan.candidateSha256().equals(candidate.sha256())) return false;
        TargetView expected = plan.preimageSha256().isPresent()
                ? new TargetView(true, new byte[Math.toIntExact(plan.preimageBytes())],
                        plan.preimageSha256().orElseThrow(),
                        plan.preimageIdentity().orElseThrow(), "", Optional.empty())
                : TargetView.absent();
        String expectedId = planId(plan.rootIdentitySha256(), candidate, expected,
                plan.diffSha256().orElseThrow(), plan.status());
        String expectedApproval = approval(expectedId, plan.rootIdentitySha256(), candidate,
                expected, plan.diffSha256().orElseThrow());
        return plan.id().equals(expectedId)
                && plan.approvalToken().orElse("").equals(expectedApproval);
    }

    private static boolean matches(TargetView current, FixtureSkillChangePlan plan) {
        if (current.blockedReason().isPresent()) return false;
        if (plan.status() == FixtureSkillChangePlan.Status.READY_CREATE) return !current.present();
        return current.present()
                && plan.preimageSha256().orElse("").equals(current.sha256())
                && plan.preimageBytes() == current.bytes().length
                && plan.preimageIdentity().orElse("").equals(current.identity());
    }

    private static String exactDiff(Candidate candidate, TargetView target) {
        StringBuilder diff = new StringBuilder();
        diff.append("# diffMode=REAL_TARGET_EXACT_REPLACEMENT targetState=PROBED applyEligible=true\n")
                .append("diff --git a/").append(candidate.logicalPath()).append(" b/")
                .append(candidate.logicalPath()).append('\n');
        List<String> oldLines = lines(target.present()
                ? new String(target.bytes(), StandardCharsets.UTF_8) : "");
        List<String> newLines = lines(candidate.content());
        if (!target.present()) diff.append("new file mode 100644\n--- /dev/null\n");
        else diff.append("--- a/").append(candidate.logicalPath()).append('\n');
        diff.append("+++ b/").append(candidate.logicalPath()).append('\n')
                .append("@@ -").append(target.present() ? "1," + oldLines.size() : "0,0")
                .append(" +1,").append(newLines.size()).append(" @@\n");
        for (String line : oldLines) diff.append('-').append(line).append('\n');
        for (String line : newLines) diff.append('+').append(line).append('\n');
        return diff.toString();
    }

    private static List<String> lines(String text) {
        if (text.isEmpty()) return List.of();
        String[] values = text.split("\n", -1);
        List<String> lines = new ArrayList<>(List.of(values));
        if (lines.getLast().isEmpty()) lines.removeLast();
        return List.copyOf(lines);
    }

    private static FixtureSkillChangePlan blocked(
            String rootIdentity, Candidate candidate, String reason) {
        String id = "scp_" + hash(tuple("blocked:v1", rootIdentity,
                candidate.id(), candidate.sha256(), reason));
        return new FixtureSkillChangePlan(1, id, FixtureSkillChangePlan.Status.BLOCKED,
                rootIdentity, candidate.logicalPath(), candidate.sha256(), Optional.empty(), -1,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(reason),
                true, false, false, false);
    }

    private static FixtureSkillChangePlan noChange(
            String rootIdentity, Candidate candidate, TargetView view) {
        String id = "scp_" + hash(tuple("no-change:v1", rootIdentity,
                candidate.id(), candidate.sha256(), view.identity()));
        return new FixtureSkillChangePlan(1, id, FixtureSkillChangePlan.Status.NO_CHANGE,
                rootIdentity, candidate.logicalPath(), candidate.sha256(),
                Optional.of(view.sha256()), view.bytes().length, Optional.of(view.identity()),
                Optional.empty(), Optional.empty(), Optional.empty(), true, false, false, false);
    }

    private static String planId(String rootIdentity, Candidate candidate, TargetView view,
            String diffHash, FixtureSkillChangePlan.Status status) {
        return "scp_" + hash(tuple("skill-change-plan:v1", rootIdentity, status.name(),
                candidate.id(), candidate.logicalPath(), candidate.sha256(),
                view.present() ? view.sha256() : "ABSENT",
                view.present() ? Long.toString(view.bytes().length) : "-1",
                view.present() ? view.identity() : "ABSENT", diffHash,
                "codex-project-skill-static-v1"));
    }

    private static String approval(String planId, String rootIdentity, Candidate candidate,
            TargetView view, String diffHash) {
        return "acw1_" + hash(tuple("approval:v1", planId, rootIdentity,
                candidate.logicalPath(), candidate.sha256(),
                view.present() ? view.sha256() : "ABSENT",
                view.present() ? view.identity() : "ABSENT", diffHash));
    }

    private static FixtureSkillApplyReceipt failure(FixtureSkillApplyReceipt.Status status,
            FixtureSkillChangePlan plan, Candidate candidate, Optional<String> transactionId,
            String detail) {
        return new FixtureSkillApplyReceipt(1, status, transactionId, plan.id(),
                candidate.logicalPath(), candidate.sha256(), Optional.empty(), true,
                false, false, false, detail);
    }

    private static FixtureSkillApplyReceipt postMoveFailure(
            FixtureSkillApplyReceipt.Status status, FixtureSkillChangePlan plan,
            Candidate candidate, String transactionId, Optional<String> snapshotSha256,
            String detail) {
        return new FixtureSkillApplyReceipt(1, status, Optional.of(transactionId), plan.id(),
                candidate.logicalPath(), candidate.sha256(), snapshotSha256, true,
                true, true, false, detail);
    }

    private static FixtureSkillApplyReceipt recoveryRequired(FixtureSkillChangePlan plan,
            Candidate candidate, String transactionId, Optional<String> snapshotSha256,
            boolean targetWritten, String detail) {
        return new FixtureSkillApplyReceipt(1,
                FixtureSkillApplyReceipt.Status.RECOVERY_REQUIRED,
                Optional.of(transactionId), plan.id(), candidate.logicalPath(),
                candidate.sha256(), snapshotSha256, true,
                targetWritten, targetWritten, false, detail);
    }

    private static FixtureSkillRecoveryReceipt recoveryReceipt(String transactionId,
            String logicalPath, FixtureSkillRecoveryReceipt.Status status,
            boolean targetWrites, boolean stateWrites, boolean rollbackAvailable,
            String detail) {
        return new FixtureSkillRecoveryReceipt(1, transactionId, status, logicalPath,
                true, targetWrites, stateWrites, rollbackAvailable, detail);
    }

    private static FixtureSkillRollbackReceipt rollbackReceipt(String tx, String path,
            boolean writes, FixtureSkillRollbackReceipt.Status status, String detail) {
        return new FixtureSkillRollbackReceipt(1, tx, status, path, true, writes, detail);
    }

    private static void moveAtomic(Path source, Path target, boolean replace) throws IOException {
        if (replace) {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    private static void writeForced(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
            writeAll(channel, bytes);
            channel.force(true);
        }
    }

    private static void writeForcedNew(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE,
                StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS)) {
            writeAll(channel, bytes);
            channel.force(true);
        }
    }

    private static void writeAll(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
    }

    private static byte[] readBounded(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(
                path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES || input.read() != -1) {
                throw new IOException("file exceeds fixture byte limit");
            }
            return bytes;
        }
    }

    private static boolean same(BasicFileAttributes left, BasicFileAttributes right) {
        return right.isRegularFile() && left.size() == right.size()
                && left.lastModifiedTime().equals(right.lastModifiedTime())
                && java.util.Objects.equals(left.fileKey(), right.fileKey());
    }

    private static String permissions(Path path) {
        try {
            return Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).stream()
                    .sorted(Comparator.comparing(Enum::name)).map(Enum::name)
                    .reduce((left, right) -> left + "," + right).orElse("");
        } catch (IOException | UnsupportedOperationException exception) {
            return "UNSUPPORTED";
        }
    }

    private static String newTargetPermissions(Path parent) throws IOException {
        return Files.getFileStore(parent).supportsFileAttributeView(PosixFileAttributeView.class)
                ? "OWNER_READ,OWNER_WRITE" : "UNSUPPORTED";
    }

    private static void setPermissions(Path path, String encoded) throws IOException {
        if ("UNSUPPORTED".equals(encoded)) return;
        Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
        if (!encoded.isEmpty()) {
            for (String value : encoded.split(",")) {
                permissions.add(PosixFilePermission.valueOf(value));
            }
        }
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException exception) {
            if (!"OWNER_READ,OWNER_WRITE".equals(encoded)) throw exception;
        }
    }

    private static void writeManifest(Path directory, Manifest manifest, boolean replace)
            throws IOException {
        byte[] bytes = manifestBytes(manifest);
        Path temporary = Files.createTempFile(directory, ".manifest-", ".tmp");
        try {
            writeForced(temporary, bytes);
            Path target = directory.resolve(MANIFEST);
            if (replace) moveAtomic(temporary, target, true);
            else moveAtomic(temporary, target, false);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static byte[] manifestBytes(Manifest manifest) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF("ACW-S3-FIXTURE-MANIFEST");
            output.writeInt(2);
            output.writeUTF(manifest.transactionId);
            output.writeUTF(manifest.planId);
            output.writeUTF(manifest.rootIdentity);
            output.writeUTF(manifest.logicalPath);
            output.writeBoolean(manifest.existedBefore);
            output.writeUTF(manifest.preimageSha256);
            output.writeUTF(manifest.preimageIdentity);
            output.writeUTF(manifest.candidateSha256);
            output.writeUTF(manifest.permissions);
            output.writeUTF(manifest.state.name());
            output.writeUTF(manifestIntegrity(manifest));
        }
        return bytes.toByteArray();
    }

    private static Manifest readManifest(Path directory) throws IOException {
        Path path = directory.resolve(MANIFEST);
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) > 4096) throw new IOException("manifest missing");
        byte[] bytes = Files.readAllBytes(path);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (!"ACW-S3-FIXTURE-MANIFEST".equals(input.readUTF()) || input.readInt() != 2) {
                throw new IOException("manifest schema");
            }
            Manifest manifest = new Manifest(input.readUTF(), input.readUTF(), input.readUTF(),
                    input.readUTF(), input.readBoolean(), input.readUTF(), input.readUTF(),
                    input.readUTF(), input.readUTF(), ManifestState.valueOf(input.readUTF()));
            String integrity = input.readUTF();
            if (input.read() != -1) throw new IOException("manifest trailing bytes");
            validateManifest(manifest);
            if (!integrity.equals(manifestIntegrity(manifest))) {
                throw new IOException("manifest integrity");
            }
            return manifest;
        }
    }

    private static void validateManifest(Manifest manifest) throws IOException {
        if (!manifest.transactionId.matches("[0-9a-f-]{36}")
                || !manifest.planId.matches("scp_[0-9a-f]{64}")
                || !manifest.rootIdentity.matches("[0-9a-f]{64}")
                || !manifest.logicalPath.matches(
                        "\\.agents/skills/[a-z0-9]+(?:-[a-z0-9]+)*/SKILL\\.md")
                || !manifest.candidateSha256.matches("[0-9a-f]{64}")
                || (manifest.existedBefore
                        != manifest.preimageSha256.matches("[0-9a-f]{64}"))
                || (manifest.existedBefore != !manifest.preimageIdentity.isEmpty())
                || (!manifest.preimageIdentity.isEmpty()
                        && !manifest.preimageIdentity.matches("[0-9a-f]{64}"))) {
            throw new IOException("manifest semantic validation");
        }
        if (!"UNSUPPORTED".equals(manifest.permissions)) {
            try {
                if (!manifest.permissions.isEmpty()) {
                    for (String permission : manifest.permissions.split(",")) {
                        PosixFilePermission.valueOf(permission);
                    }
                }
            } catch (IllegalArgumentException exception) {
                throw new IOException("manifest permissions", exception);
            }
        }
    }

    private static String manifestIntegrity(Manifest manifest) {
        return hash(tuple("fixture-manifest:v2", manifest.transactionId, manifest.planId,
                manifest.rootIdentity, manifest.logicalPath,
                Boolean.toString(manifest.existedBefore), manifest.preimageSha256,
                manifest.preimageIdentity, manifest.candidateSha256,
                manifest.permissions, manifest.state.name()));
    }

    private static Candidate readyCandidate(CodexSkillDraftPreview draft) {
        if (draft == null || draft.status() != CodexSkillDraftPreview.Status.READY
                || draft.validation().status()
                        != CodexSkillDraftPreview.ValidationStatus.PASSED
                || draft.candidate().isEmpty()) {
            throw new IllegalArgumentException("fixture transaction requires a READY Skill draft");
        }
        return draft.candidate().orElseThrow();
    }

    private static String hash(String value) {
        return hash(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String rootIdentity(Path root) throws IOException {
        Path marker = root.resolve(WORKSPACE_MARKER);
        BasicFileAttributes rootAttributes = Files.readAttributes(
                root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        BasicFileAttributes markerAttributes = Files.readAttributes(
                marker, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return hash(tuple("fixture-root-identity:v1", root.toString(),
                String.valueOf(rootAttributes.fileKey()),
                rootAttributes.creationTime().toString(),
                rootAttributes.lastModifiedTime().toString(),
                String.valueOf(markerAttributes.fileKey()),
                markerAttributes.creationTime().toString(),
                markerAttributes.lastModifiedTime().toString(),
                hash(readBounded(marker))));
    }

    private static String hash(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String tuple(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) result.append(value.length()).append(':').append(value).append(';');
        return result.toString();
    }

    private record TargetView(boolean present, byte[] bytes, String sha256, String identity,
            String permissions, Optional<String> blockedReason) {
        private TargetView { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
        static TargetView absent() {
            return new TargetView(false, new byte[0], "", "", "", Optional.empty());
        }
        static TargetView blocked(String reason) {
            return new TargetView(false, new byte[0], "", "", "", Optional.of(reason));
        }
    }

    private record Snapshot(Optional<String> sha256) {}

    private enum ManifestState { PREPARED, COMMIT_INTENT, APPLIED, ABORTED, ROLLED_BACK }

    private enum StageState { ABSENT, CANDIDATE, INVALID }

    private record Manifest(String transactionId, String planId, String rootIdentity,
            String logicalPath, boolean existedBefore, String preimageSha256,
            String preimageIdentity, String candidateSha256, String permissions,
            ManifestState state) {
        Manifest withState(ManifestState next) {
            return new Manifest(transactionId, planId, rootIdentity, logicalPath, existedBefore,
                    preimageSha256, preimageIdentity, candidateSha256, permissions, next);
        }
    }

    private static final class Blocked extends Exception {
        private static final long serialVersionUID = 1L;
        private final String code;
        private Blocked(String code) { super(code); this.code = code; }
    }
}
