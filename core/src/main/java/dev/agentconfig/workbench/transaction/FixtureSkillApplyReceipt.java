package dev.agentconfig.workbench.transaction;

import java.util.Objects;
import java.util.Optional;

/** Content-free result of a fixture-only apply attempt. */
public record FixtureSkillApplyReceipt(
        int schemaVersion,
        Status status,
        Optional<String> transactionId,
        String planId,
        String logicalPath,
        String candidateSha256,
        Optional<String> snapshotSha256,
        boolean fixtureOnly,
        boolean atomicMoveUsed,
        boolean writesPerformed,
        boolean rollbackAvailable,
        String detail) {
    public FixtureSkillApplyReceipt {
        if (schemaVersion != 1) throw new IllegalArgumentException("schema");
        Objects.requireNonNull(status, "status");
        transactionId = Objects.requireNonNull(transactionId, "transactionId");
        planId = required(planId, "planId");
        logicalPath = required(logicalPath, "logicalPath");
        candidateSha256 = hash(candidateSha256, "candidateSha256");
        snapshotSha256 = Objects.requireNonNull(snapshotSha256, "snapshotSha256")
                .map(value -> hash(value, "snapshotSha256"));
        detail = required(detail, "detail");
        if (!fixtureOnly) throw new IllegalArgumentException("fixtureOnly");
        switch (status) {
            case VERIFIED_APPLIED -> requireFlags(transactionId, atomicMoveUsed,
                    writesPerformed, rollbackAvailable, true, true, true, true);
            case AUTO_ROLLED_BACK -> requireFlags(transactionId, atomicMoveUsed,
                    writesPerformed, rollbackAvailable, true, true, true, false);
            case RECOVERY_REQUIRED -> requireFlags(transactionId, atomicMoveUsed,
                    writesPerformed, rollbackAvailable, true, true, true, false);
            case APPROVAL_MISMATCH, STALE_PREIMAGE, FAILED_BEFORE_WRITE -> {
                if (atomicMoveUsed || writesPerformed || rollbackAvailable) {
                    throw new IllegalArgumentException("pre-write status has write flags");
                }
            }
        }
    }

    public enum Status {
        VERIFIED_APPLIED, APPROVAL_MISMATCH, STALE_PREIMAGE, FAILED_BEFORE_WRITE,
        AUTO_ROLLED_BACK, RECOVERY_REQUIRED
    }

    private static void requireFlags(Optional<String> transactionId, boolean atomicMoveUsed,
            boolean writesPerformed, boolean rollbackAvailable, boolean transactionExpected,
            boolean atomicExpected, boolean writesExpected, boolean rollbackExpected) {
        if (transactionId.isPresent() != transactionExpected || atomicMoveUsed != atomicExpected
                || writesPerformed != writesExpected || rollbackAvailable != rollbackExpected) {
            throw new IllegalArgumentException("apply flags disagree with status");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field);
        return value;
    }

    private static String hash(String value, String field) {
        value = required(value, field);
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(field);
        return value;
    }
}
