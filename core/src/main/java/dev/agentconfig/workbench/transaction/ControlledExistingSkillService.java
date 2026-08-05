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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
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
 * Transitional Gate-6 entry for replacing one existing Codex project Skill.
 *
 * <p>This deliberately does not create target directories, recover interrupted processes, or
 * claim power-loss durability. It keeps the minimum real-workspace contract: explicit root,
 * exact diff, approval token, stale-target rejection, external snapshot, atomic replacement,
 * verification, and guarded rollback.</p>
 */
public final class ControlledExistingSkillService {
    public static final String STATE_MARKER = ".agent-config-workbench-controlled-state";
    public static final String STATE_MARKER_CONTENT = "agent-config-workbench-controlled-v1\n";
    private static final int MAX_BYTES = 128 * 1024;
    private static final String MANIFEST = "manifest.bin";
    private static final String SNAPSHOT = "preimage.bin";

    public PreparedControlledSkillChange prepare(Path authorizedWorkspace,
            CodexSkillDraftPreview draft) throws IOException {
        Candidate candidate = readyCandidate(draft);
        Path root;
        String rootIdentity;
        try {
            root = controlledRoot(authorizedWorkspace);
            rootIdentity = rootIdentity(root);
            Path target = target(root, candidate.logicalPath());
            TargetView current = inspect(root, target);
            if (current.blockedReason().isPresent()) {
                return blocked(rootIdentity, candidate,
                        current.blockedReason().orElseThrow());
            }
            if (!current.present()) return blocked(rootIdentity, candidate, "EXISTING_TARGET_REQUIRED");
            if (!strictSkillText(current.bytes())) {
                return blocked(rootIdentity, candidate, "TARGET_MUST_BE_UTF8_LF_WITH_FINAL_NEWLINE");
            }
            if (current.sha256().equals(candidate.sha256())) {
                ControlledSkillChangePlan plan = new ControlledSkillChangePlan(1,
                        "csp_" + hash(tuple("controlled-no-change:v1", rootIdentity,
                                candidate.logicalPath(), candidate.sha256(), current.identity())),
                        ControlledSkillChangePlan.Status.NO_CHANGE, rootIdentity,
                        candidate.logicalPath(), candidate.sha256(), Optional.of(current.sha256()),
                        Optional.of(current.identity()), Optional.of(current.permissions()),
                        Optional.empty(), Optional.empty(), Optional.empty(), false, false, false);
                return new PreparedControlledSkillChange(plan, Optional.empty());
            }
            String diff = exactDiff(candidate, current);
            String diffSha = hash(diff);
            String id = "csp_" + hash(tuple("controlled-plan:v1", rootIdentity,
                    candidate.logicalPath(), candidate.sha256(), current.sha256(),
                    current.identity(), current.permissions(), diffSha));
            String approval = "acw_apply1_" + hash(tuple("controlled-approval:v1", id,
                    rootIdentity, candidate.logicalPath(), candidate.sha256(), current.sha256(),
                    current.identity(), current.permissions(), diffSha));
            ControlledSkillChangePlan plan = new ControlledSkillChangePlan(1, id,
                    ControlledSkillChangePlan.Status.READY_REPLACE, rootIdentity,
                    candidate.logicalPath(), candidate.sha256(), Optional.of(current.sha256()),
                    Optional.of(current.identity()), Optional.of(current.permissions()),
                    Optional.of(diffSha), Optional.of(approval), Optional.empty(),
                    false, false, true);
            return new PreparedControlledSkillChange(plan, Optional.of(diff));
        } catch (Blocked blocked) {
            rootIdentity = hash(authorizedWorkspace.toAbsolutePath().normalize().toString());
            return blocked(rootIdentity, candidate, blocked.code);
        }
    }

    public ControlledSkillApplyReceipt apply(Path authorizedWorkspace, Path stateDirectory,
            CodexSkillDraftPreview draft, String approvalToken) throws IOException {
        PreparedControlledSkillChange prepared = prepare(authorizedWorkspace, draft);
        ControlledSkillChangePlan plan = prepared.plan();
        if (!plan.applyEligible() || approvalToken == null
                || !approvalToken.equals(plan.approvalToken().orElse(""))) {
            return applyReceipt(ControlledSkillApplyReceipt.Status.APPROVAL_MISMATCH,
                    Optional.empty(), plan, false, false, false, false,
                    "APPROVAL_MISMATCH");
        }
        Path root;
        Path stateRoot;
        try {
            root = controlledRoot(authorizedWorkspace);
            stateRoot = controlledStateRoot(stateDirectory, root);
        } catch (Blocked blocked) {
            return applyReceipt(ControlledSkillApplyReceipt.Status.BLOCKED,
                    Optional.empty(), plan, false, false, false, false, blocked.code);
        }
        Candidate candidate = readyCandidate(draft);
        Path target;
        TargetView current;
        try {
            target = target(root, candidate.logicalPath());
            current = inspect(root, target);
        } catch (Blocked blocked) {
            return applyReceipt(ControlledSkillApplyReceipt.Status.BLOCKED,
                    Optional.empty(), plan, false, false, false, false, blocked.code);
        }
        if (!matchesPlan(current, plan)) {
            return applyReceipt(ControlledSkillApplyReceipt.Status.STALE_PREIMAGE,
                    Optional.empty(), plan, false, false, false, false, "STALE_PREIMAGE");
        }

        String transactionId = UUID.randomUUID().toString();
        Path transaction = stateRoot.resolve(transactionId);
        Path stage = target.getParent().resolve(".acw-controlled-stage-" + transactionId + ".tmp");
        boolean stateWritten = false;
        boolean ownedStage = false;
        boolean moved = false;
        try {
            Files.createDirectory(transaction);
            stateWritten = true;
            Path snapshot = transaction.resolve(SNAPSHOT);
            writeNewForced(snapshot, current.bytes());
            writeNewForced(stage, candidate.bytes());
            ownedStage = true;
            setPermissions(stage, current.permissions());
            TargetView staged = inspect(root, stage);
            if (!staged.present() || !staged.sha256().equals(candidate.sha256())
                    || !staged.permissions().equals(current.permissions())) {
                return applyReceipt(ControlledSkillApplyReceipt.Status.WRITE_FAILED,
                        Optional.of(transactionId), plan, false, true, false, false,
                        "STAGE_VERIFICATION_FAILED");
            }
            Manifest manifest = new Manifest(transactionId, plan.rootIdentitySha256(),
                    plan.logicalPath(), current.sha256(), current.identity(), current.permissions(),
                    candidate.sha256(), staged.identity(), State.APPLY_INTENT);
            writeManifest(transaction, manifest, false);
            TargetView beforeMove = inspect(root, target);
            if (!matchesPlan(beforeMove, plan)) {
                return applyReceipt(ControlledSkillApplyReceipt.Status.STALE_PREIMAGE,
                        Optional.of(transactionId), plan, false, true, false, false,
                        "STALE_BEFORE_MOVE");
            }
            Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            moved = true;
            ownedStage = false;
            TargetView written = inspect(root, target);
            if (!written.present() || !written.sha256().equals(candidate.sha256())
                    || !written.permissions().equals(current.permissions())) {
                return applyReceipt(ControlledSkillApplyReceipt.Status.RECOVERY_REQUIRED,
                        Optional.of(transactionId), plan, true, true, false, true,
                        "POST_WRITE_VERIFICATION_FAILED");
            }
            writeManifest(transaction, manifest.applied(written.identity()), true);
            return applyReceipt(ControlledSkillApplyReceipt.Status.VERIFIED_APPLIED,
                    Optional.of(transactionId), plan, true, true, true, false,
                    "CONTROLLED_APPLY_VERIFIED");
        } catch (IOException | Blocked exception) {
            return applyReceipt(moved ? ControlledSkillApplyReceipt.Status.RECOVERY_REQUIRED
                            : ControlledSkillApplyReceipt.Status.WRITE_FAILED,
                    stateWritten ? Optional.of(transactionId) : Optional.empty(), plan,
                    moved, stateWritten, false, moved,
                    moved ? "WRITE_FAILED_AFTER_TARGET_MOVE" : "WRITE_FAILED_BEFORE_TARGET_MOVE");
        } finally {
            if (ownedStage) Files.deleteIfExists(stage);
        }
    }

    public ControlledSkillRollbackReceipt rollback(Path authorizedWorkspace,
            Path stateDirectory, String transactionId) throws IOException {
        if (!validTransactionId(transactionId)) throw new IllegalArgumentException("transactionId");
        Path root;
        Path stateRoot;
        try {
            root = controlledRoot(authorizedWorkspace);
            stateRoot = requireControlledStateRoot(stateDirectory, root);
        } catch (Blocked blocked) {
            return rollbackReceipt(transactionId, "unknown",
                    ControlledSkillRollbackReceipt.Status.INVALID_TRANSACTION,
                    false, false, false, blocked.code);
        }
        Path transaction = stateRoot.resolve(transactionId);
        Manifest manifest;
        try {
            if (Files.isSymbolicLink(transaction)
                    || !Files.isDirectory(transaction, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("transaction directory");
            }
            manifest = readManifest(transaction);
        } catch (IOException | IllegalArgumentException exception) {
            return rollbackReceipt(transactionId, "unknown",
                    ControlledSkillRollbackReceipt.Status.INVALID_TRANSACTION,
                    false, false, false, "MANIFEST_INVALID");
        }
        if (!manifest.transactionId.equals(transactionId)
                || !manifest.rootIdentity.equals(rootIdentity(root))) {
            return rollbackReceipt(transactionId, manifest.logicalPath,
                    ControlledSkillRollbackReceipt.Status.INVALID_TRANSACTION,
                    false, false, false, "TRANSACTION_SCOPE_MISMATCH");
        }
        Path target;
        TargetView current;
        try {
            target = target(root, manifest.logicalPath);
            current = inspect(root, target);
        } catch (Blocked blocked) {
            return rollbackReceipt(transactionId, manifest.logicalPath,
                    ControlledSkillRollbackReceipt.Status.INVALID_TRANSACTION,
                    false, false, false, blocked.code);
        }
        if (manifest.state == State.ROLLED_BACK) {
            boolean restored = current.present()
                    && current.sha256().equals(manifest.preimageSha256)
                    && current.permissions().equals(manifest.permissions)
                    && current.identity().equals(manifest.resultIdentity);
            return rollbackReceipt(transactionId, manifest.logicalPath,
                    restored ? ControlledSkillRollbackReceipt.Status.ALREADY_ROLLED_BACK
                            : ControlledSkillRollbackReceipt.Status.CURRENT_TARGET_CHANGED,
                    false, false, false,
                    restored ? "ALREADY_ROLLED_BACK" : "ROLLED_BACK_TARGET_CHANGED");
        }
        boolean recoverableApplyIntent = manifest.state == State.APPLY_INTENT
                && current.present() && current.sha256().equals(manifest.candidateSha256)
                && current.permissions().equals(manifest.permissions)
                && current.identity().equals(manifest.resultIdentity);
        boolean verifiedApplied = manifest.state == State.APPLIED && current.present()
                && current.sha256().equals(manifest.candidateSha256)
                && current.permissions().equals(manifest.permissions)
                && current.identity().equals(manifest.resultIdentity);
        if (!recoverableApplyIntent && !verifiedApplied) {
            return rollbackReceipt(transactionId, manifest.logicalPath,
                    ControlledSkillRollbackReceipt.Status.CURRENT_TARGET_CHANGED,
                    false, false, false, "CURRENT_TARGET_CHANGED");
        }
        Path snapshot = transaction.resolve(SNAPSHOT);
        byte[] original;
        try {
            if (Files.isSymbolicLink(snapshot)
                    || !Files.isRegularFile(snapshot, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("snapshot invalid");
            }
            original = readBounded(snapshot);
            if (!hash(original).equals(manifest.preimageSha256)) {
                throw new IOException("snapshot hash");
            }
            Path stage = target.getParent().resolve(
                    ".acw-controlled-rollback-" + transactionId + ".tmp");
            boolean ownedStage = false;
            boolean moved = false;
            try {
                writeNewForced(stage, original);
                ownedStage = true;
                setPermissions(stage, manifest.permissions);
                TargetView staged = inspect(root, stage);
                if (!staged.present() || !staged.sha256().equals(manifest.preimageSha256)
                        || !staged.permissions().equals(manifest.permissions)) {
                    return rollbackReceipt(transactionId, manifest.logicalPath,
                            ControlledSkillRollbackReceipt.Status.WRITE_FAILED,
                            false, false, false, "ROLLBACK_STAGE_VERIFICATION_FAILED");
                }
                TargetView beforeMove = inspect(root, target);
                if (!beforeMove.present()
                        || !beforeMove.identity().equals(current.identity())
                        || !beforeMove.sha256().equals(current.sha256())
                        || !beforeMove.permissions().equals(current.permissions())) {
                    return rollbackReceipt(transactionId, manifest.logicalPath,
                            ControlledSkillRollbackReceipt.Status.CURRENT_TARGET_CHANGED,
                            false, false, false, "CURRENT_TARGET_CHANGED_BEFORE_ROLLBACK_MOVE");
                }
                Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                moved = true;
                ownedStage = false;
                TargetView restored = inspect(root, target);
                if (!restored.present() || !restored.sha256().equals(manifest.preimageSha256)
                        || !restored.permissions().equals(manifest.permissions)) {
                    return rollbackReceipt(transactionId, manifest.logicalPath,
                            ControlledSkillRollbackReceipt.Status.RECOVERY_REQUIRED,
                            true, false, true, "ROLLBACK_VERIFICATION_FAILED");
                }
                writeManifest(transaction, manifest.rolledBack(restored.identity()), true);
                return rollbackReceipt(transactionId, manifest.logicalPath,
                        ControlledSkillRollbackReceipt.Status.ROLLED_BACK,
                        true, true, false, "CONTROLLED_ROLLBACK_VERIFIED");
            } catch (IOException | Blocked exception) {
                return rollbackReceipt(transactionId, manifest.logicalPath,
                        moved ? ControlledSkillRollbackReceipt.Status.RECOVERY_REQUIRED
                                : ControlledSkillRollbackReceipt.Status.WRITE_FAILED,
                        moved, false, moved,
                        moved ? "ROLLBACK_FAILED_AFTER_TARGET_MOVE"
                                : "ROLLBACK_WRITE_FAILED");
            } finally {
                if (ownedStage) Files.deleteIfExists(stage);
            }
        } catch (IOException exception) {
            return rollbackReceipt(transactionId, manifest.logicalPath,
                    ControlledSkillRollbackReceipt.Status.WRITE_FAILED,
                    false, false, false, "ROLLBACK_SNAPSHOT_INVALID");
        }
    }

    private static PreparedControlledSkillChange blocked(String rootIdentity, Candidate candidate,
            String reason) {
        ControlledSkillChangePlan plan = new ControlledSkillChangePlan(1,
                "csp_" + hash(tuple("controlled-blocked:v1", rootIdentity,
                        candidate.logicalPath(), candidate.sha256(), reason)),
                ControlledSkillChangePlan.Status.BLOCKED, rootIdentity, candidate.logicalPath(),
                candidate.sha256(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(reason), false, false, false);
        return new PreparedControlledSkillChange(plan, Optional.empty());
    }

    private static ControlledSkillApplyReceipt applyReceipt(
            ControlledSkillApplyReceipt.Status status, Optional<String> transactionId,
            ControlledSkillChangePlan plan, boolean targetWrites, boolean stateWrites,
            boolean rollbackAvailable, boolean recoveryRequired, String detail) {
        return new ControlledSkillApplyReceipt(1, status, transactionId, plan.id(),
                plan.logicalPath(), targetWrites, stateWrites, rollbackAvailable,
                recoveryRequired, detail);
    }

    private static ControlledSkillRollbackReceipt rollbackReceipt(String transactionId,
            String logicalPath, ControlledSkillRollbackReceipt.Status status,
            boolean targetWrites, boolean stateWrites, boolean recoveryRequired, String detail) {
        return new ControlledSkillRollbackReceipt(1, transactionId, status, logicalPath,
                targetWrites, stateWrites, recoveryRequired, detail);
    }

    private static boolean matchesPlan(TargetView current, ControlledSkillChangePlan plan) {
        return current.blockedReason().isEmpty() && current.present()
                && current.sha256().equals(plan.preimageSha256().orElse(""))
                && current.identity().equals(plan.preimageIdentity().orElse(""))
                && current.permissions().equals(plan.permissions().orElse(""));
    }

    private static Path controlledRoot(Path supplied) throws IOException, Blocked {
        Path absolute = supplied.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute)) throw new Blocked("WORKSPACE_ROOT_IS_LINK");
        Path root = absolute.toRealPath();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || unsafeLink(root)) {
            throw new Blocked("WORKSPACE_ROOT_INVALID");
        }
        return root;
    }

    private static Path controlledStateRoot(Path supplied, Path workspace)
            throws IOException, Blocked {
        Path absolute = supplied.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute)) throw new Blocked("STATE_ROOT_IS_LINK");
        Path state = absolute.toRealPath();
        if (!Files.isDirectory(state, LinkOption.NOFOLLOW_LINKS) || unsafeLink(state)
                || state.startsWith(workspace) || workspace.startsWith(state)) {
            throw new Blocked("STATE_ROOT_INVALID_OR_NOT_SEPARATE");
        }
        Path marker = state.resolve(STATE_MARKER);
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(marker)
                    || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                    || !new String(readBounded(marker), StandardCharsets.UTF_8)
                            .equals(STATE_MARKER_CONTENT)) {
                throw new Blocked("STATE_MARKER_INVALID");
            }
        } else {
            writeNewForced(marker, STATE_MARKER_CONTENT.getBytes(StandardCharsets.UTF_8));
        }
        return state;
    }

    private static Path requireControlledStateRoot(Path supplied, Path workspace)
            throws IOException, Blocked {
        Path absolute = supplied.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute)) throw new Blocked("STATE_ROOT_IS_LINK");
        Path state = absolute.toRealPath();
        if (!Files.isDirectory(state, LinkOption.NOFOLLOW_LINKS) || unsafeLink(state)
                || state.startsWith(workspace) || workspace.startsWith(state)) {
            throw new Blocked("STATE_ROOT_INVALID_OR_NOT_SEPARATE");
        }
        Path marker = state.resolve(STATE_MARKER);
        if (Files.isSymbolicLink(marker)
                || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                || !strictUtf8(readBounded(marker)).equals(STATE_MARKER_CONTENT)) {
            throw new Blocked("STATE_MARKER_MISSING_OR_INVALID");
        }
        return state;
    }

    private static Path target(Path root, String logicalPath) throws IOException, Blocked {
        Path target = root.resolve(logicalPath).normalize();
        if (!target.startsWith(root)) throw new Blocked("TARGET_OUTSIDE_ROOT");
        Path cursor = root;
        for (Path segment : root.relativize(target.getParent())) {
            cursor = cursor.resolve(segment);
            if (!Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS) || unsafeLink(cursor)) {
                throw new Blocked("TARGET_PARENT_MISSING_OR_UNSAFE");
            }
        }
        return target;
    }

    private static TargetView inspect(Path root, Path target) throws IOException, Blocked {
        if (!target.normalize().startsWith(root)) throw new Blocked("TARGET_OUTSIDE_ROOT");
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return TargetView.absent();
        if (unsafeLink(target)) return TargetView.blocked("TARGET_LINK_OR_REPARSE");
        BasicFileAttributes before = Files.readAttributes(
                target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size() > MAX_BYTES) {
            return TargetView.blocked("TARGET_NOT_BOUNDED_REGULAR_FILE");
        }
        byte[] bytes = readBounded(target);
        BasicFileAttributes after = Files.readAttributes(
                target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!same(before, after)) return TargetView.blocked("TARGET_CHANGED_DURING_PROBE");
        String permissions = permissions(target);
        String identity = hash(tuple(Long.toString(after.size()),
                Long.toString(after.lastModifiedTime().toMillis()),
                String.valueOf(after.fileKey()), permissions));
        return new TargetView(true, bytes, hash(bytes), identity, permissions, Optional.empty());
    }

    private static boolean unsafeLink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) return true;
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isOther()) return true;
        return !path.toRealPath().equals(path.toRealPath(LinkOption.NOFOLLOW_LINKS));
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

    private static void setPermissions(Path path, String encoded) throws IOException {
        if ("UNSUPPORTED".equals(encoded)) return;
        Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
        if (!encoded.isEmpty()) {
            for (String value : encoded.split(",")) {
                permissions.add(PosixFilePermission.valueOf(value));
            }
        }
        Files.setPosixFilePermissions(path, permissions);
    }

    private static String exactDiff(Candidate candidate, TargetView target) {
        String oldText;
        try {
            oldText = strictUtf8(target.bytes());
        } catch (CharacterCodingException exception) {
            throw new IllegalStateException("strict text was already validated", exception);
        }
        List<String> oldLines = lines(oldText);
        List<String> newLines = lines(candidate.content());
        StringBuilder diff = new StringBuilder()
                .append("# diffMode=REAL_TARGET_EXACT_REPLACEMENT targetState=EXISTING applyEligible=true\n")
                .append("diff --git a/").append(candidate.logicalPath()).append(" b/")
                .append(candidate.logicalPath()).append('\n')
                .append("--- a/").append(candidate.logicalPath()).append('\n')
                .append("+++ b/").append(candidate.logicalPath()).append('\n')
                .append("@@ -1,").append(oldLines.size()).append(" +1,")
                .append(newLines.size()).append(" @@\n");
        for (String line : oldLines) diff.append('-').append(line).append('\n');
        for (String line : newLines) diff.append('+').append(line).append('\n');
        return diff.toString();
    }

    private static List<String> lines(String text) {
        if (text.isEmpty()) return List.of();
        List<String> result = new ArrayList<>(List.of(text.split("\n", -1)));
        if (result.getLast().isEmpty()) result.removeLast();
        return List.copyOf(result);
    }

    private static boolean strictSkillText(byte[] bytes) {
        try {
            String text = strictUtf8(bytes);
            return !text.contains("\r") && !text.contains("\u0000") && text.endsWith("\n");
        } catch (CharacterCodingException exception) {
            return false;
        }
    }

    private static String strictUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static Candidate readyCandidate(CodexSkillDraftPreview draft) {
        if (draft == null || draft.status() != CodexSkillDraftPreview.Status.READY
                || draft.validation().status()
                        != CodexSkillDraftPreview.ValidationStatus.PASSED
                || draft.candidate().isEmpty()) {
            throw new IllegalArgumentException("controlled apply requires a READY Skill draft");
        }
        return draft.candidate().orElseThrow();
    }

    private static String rootIdentity(Path root) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return hash(tuple("controlled-root:v1", root.toString(),
                String.valueOf(attributes.fileKey()), attributes.creationTime().toString()));
    }

    private static void writeNewForced(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE,
                StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    private static void writeForced(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    private static byte[] readBounded(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(
                path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES || input.read() != -1) throw new IOException("byte limit");
            return bytes;
        }
    }

    private static void writeManifest(Path directory, Manifest manifest, boolean replace)
            throws IOException {
        byte[] bytes = manifestBytes(manifest);
        Path temporary = Files.createTempFile(directory, ".manifest-", ".tmp");
        try {
            writeForced(temporary, bytes);
            if (replace) Files.move(temporary, directory.resolve(MANIFEST),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(temporary, directory.resolve(MANIFEST), StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static byte[] manifestBytes(Manifest manifest) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF("ACW-CONTROLLED-SKILL");
            output.writeInt(1);
            output.writeUTF(manifest.transactionId);
            output.writeUTF(manifest.rootIdentity);
            output.writeUTF(manifest.logicalPath);
            output.writeUTF(manifest.preimageSha256);
            output.writeUTF(manifest.preimageIdentity);
            output.writeUTF(manifest.permissions);
            output.writeUTF(manifest.candidateSha256);
            output.writeUTF(manifest.resultIdentity);
            output.writeUTF(manifest.state.name());
            output.writeUTF(manifest.integrity());
        }
        return bytes.toByteArray();
    }

    private static Manifest readManifest(Path directory) throws IOException {
        Path path = directory.resolve(MANIFEST);
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) > 4096) throw new IOException("manifest");
        try (DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(Files.readAllBytes(path)))) {
            if (!"ACW-CONTROLLED-SKILL".equals(input.readUTF()) || input.readInt() != 1) {
                throw new IOException("manifest schema");
            }
            Manifest manifest = new Manifest(input.readUTF(), input.readUTF(), input.readUTF(),
                    input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(),
                    input.readUTF(), State.valueOf(input.readUTF()));
            String integrity = input.readUTF();
            if (input.read() != -1 || !integrity.equals(manifest.integrity())) {
                throw new IOException("manifest integrity");
            }
            manifest.validate();
            return manifest;
        }
    }

    private static boolean validTransactionId(String value) {
        return value != null && value.matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    }

    private static String hash(String value) {
        return hash(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String hash(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
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

    private enum State { APPLY_INTENT, APPLIED, ROLLED_BACK }

    private record Manifest(String transactionId, String rootIdentity, String logicalPath,
            String preimageSha256, String preimageIdentity, String permissions,
            String candidateSha256, String resultIdentity, State state) {
        Manifest applied(String identity) {
            return new Manifest(transactionId, rootIdentity, logicalPath, preimageSha256,
                    preimageIdentity, permissions, candidateSha256, identity, State.APPLIED);
        }
        Manifest rolledBack(String identity) {
            return new Manifest(transactionId, rootIdentity, logicalPath, preimageSha256,
                    preimageIdentity, permissions, candidateSha256, identity, State.ROLLED_BACK);
        }
        String integrity() {
            return hash(tuple("controlled-manifest:v1", transactionId, rootIdentity, logicalPath,
                    preimageSha256, preimageIdentity, permissions, candidateSha256,
                    resultIdentity, state.name()));
        }
        void validate() throws IOException {
            if (!validTransactionId(transactionId)
                    || !rootIdentity.matches("[0-9a-f]{64}")
                    || !logicalPath.matches(
                            "\\.agents/skills/[a-z0-9]+(?:-[a-z0-9]+)*/SKILL\\.md")
                    || !preimageSha256.matches("[0-9a-f]{64}")
                    || !preimageIdentity.matches("[0-9a-f]{64}")
                    || !candidateSha256.matches("[0-9a-f]{64}")
                    || !resultIdentity.matches("[0-9a-f]{64}")) {
                throw new IOException("manifest fields");
            }
        }
    }

    private static final class Blocked extends Exception {
        private static final long serialVersionUID = 1L;
        private final String code;
        private Blocked(String code) { super(code); this.code = code; }
    }
}
