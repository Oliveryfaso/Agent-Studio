package dev.agentconfig.workbench.transaction;

import java.util.Optional;

/** Content-free result of one controlled existing-Skill apply attempt. */
public record ControlledSkillApplyReceipt(
        int schemaVersion,
        Status status,
        Optional<String> transactionId,
        String planId,
        String logicalPath,
        boolean targetWritesPerformed,
        boolean stateWritesPerformed,
        boolean rollbackAvailable,
        boolean recoveryRequired,
        String detail) {
    public ControlledSkillApplyReceipt {
        if (schemaVersion != 1) throw new IllegalArgumentException("schemaVersion");
        if (status == null) throw new NullPointerException("status");
        transactionId = transactionId == null ? Optional.empty() : transactionId;
        if (transactionId.isPresent() && !transactionId.orElseThrow().matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("transactionId");
        }
        if (planId == null || !planId.matches("csp_[0-9a-f]{64}")) {
            throw new IllegalArgumentException("planId");
        }
        if (logicalPath == null || logicalPath.isBlank()) throw new IllegalArgumentException("path");
        if (detail == null || detail.isBlank()) throw new IllegalArgumentException("detail");
        boolean applied = status == Status.VERIFIED_APPLIED;
        if (applied && (!targetWritesPerformed || !stateWritesPerformed
                || !rollbackAvailable || recoveryRequired || transactionId.isEmpty())) {
            throw new IllegalArgumentException("applied flags");
        }
        if (recoveryRequired && (!targetWritesPerformed || transactionId.isEmpty())) {
            throw new IllegalArgumentException("recovery flags");
        }
        if (targetWritesPerformed && transactionId.isEmpty()) {
            throw new IllegalArgumentException("target write without transaction");
        }
        if (rollbackAvailable && transactionId.isEmpty()) {
            throw new IllegalArgumentException("rollback without transaction");
        }
    }

    public enum Status {
        VERIFIED_APPLIED, APPROVAL_MISMATCH, STALE_PREIMAGE, BLOCKED, WRITE_FAILED,
        RECOVERY_REQUIRED
    }
}
