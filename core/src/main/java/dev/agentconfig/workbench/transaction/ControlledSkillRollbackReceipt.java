package dev.agentconfig.workbench.transaction;

/** Content-free result of one controlled rollback attempt. */
public record ControlledSkillRollbackReceipt(
        int schemaVersion,
        String transactionId,
        Status status,
        String logicalPath,
        boolean targetWritesPerformed,
        boolean stateWritesPerformed,
        boolean recoveryRequired,
        String detail) {
    public ControlledSkillRollbackReceipt {
        if (schemaVersion != 1) throw new IllegalArgumentException("schemaVersion");
        if (transactionId == null || !transactionId.matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("transactionId");
        }
        if (status == null) throw new NullPointerException("status");
        if (logicalPath == null || logicalPath.isBlank()) throw new IllegalArgumentException("path");
        if (recoveryRequired && !targetWritesPerformed) {
            throw new IllegalArgumentException("recoveryRequired");
        }
        if (detail == null || detail.isBlank()) throw new IllegalArgumentException("detail");
    }

    public enum Status {
        ROLLED_BACK, ALREADY_ROLLED_BACK, CURRENT_TARGET_CHANGED, INVALID_TRANSACTION,
        WRITE_FAILED, RECOVERY_REQUIRED
    }
}
