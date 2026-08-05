package dev.agentconfig.workbench.transaction;

/** Content-free result of a guarded fixture rollback attempt. */
public record FixtureSkillRollbackReceipt(
        int schemaVersion,
        String transactionId,
        Status status,
        String logicalPath,
        boolean fixtureOnly,
        boolean targetWritesPerformed,
        boolean stateWritesPerformed,
        String detail) {
    public FixtureSkillRollbackReceipt {
        if (schemaVersion != 1) throw new IllegalArgumentException("schema");
        if (transactionId == null || !transactionId.matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("transactionId");
        }
        if (status == null) throw new NullPointerException("status");
        if (logicalPath == null || logicalPath.isBlank()) throw new IllegalArgumentException("path");
        if (!fixtureOnly) throw new IllegalArgumentException("fixtureOnly");
        if (status == Status.ROLLED_BACK
                && (!targetWritesPerformed || !stateWritesPerformed)) {
            throw new IllegalArgumentException("completed rollback flags");
        }
        if (status != Status.ROLLED_BACK && status != Status.RECOVERY_REQUIRED
                && (targetWritesPerformed || stateWritesPerformed)) {
            throw new IllegalArgumentException("non-writing rollback flags");
        }
        if (detail == null || detail.isBlank()) throw new IllegalArgumentException("detail");
    }

    public enum Status {
        ROLLED_BACK, ALREADY_ROLLED_BACK, CURRENT_HASH_MISMATCH,
        SNAPSHOT_INVALID, FAILED_BEFORE_WRITE, RECOVERY_REQUIRED
    }
}
