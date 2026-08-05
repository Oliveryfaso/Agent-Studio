package dev.agentconfig.workbench.transaction;

/** Content-free result of a guarded fixture rollback. */
public record FixtureSkillRollbackReceipt(
        int schemaVersion,
        String transactionId,
        Status status,
        String logicalPath,
        boolean fixtureOnly,
        boolean writesPerformed,
        String detail) {
    public FixtureSkillRollbackReceipt {
        if (schemaVersion != 1) throw new IllegalArgumentException("schema");
        if (transactionId == null || transactionId.isBlank()) throw new IllegalArgumentException("tx");
        if (status == null) throw new NullPointerException("status");
        if (logicalPath == null || logicalPath.isBlank()) throw new IllegalArgumentException("path");
        if (!fixtureOnly) throw new IllegalArgumentException("fixtureOnly");
        if (writesPerformed != (status == Status.ROLLED_BACK)) {
            throw new IllegalArgumentException("rollback flags disagree with status");
        }
        if (detail == null || detail.isBlank()) throw new IllegalArgumentException("detail");
    }

    public enum Status {
        ROLLED_BACK, ALREADY_ROLLED_BACK, CURRENT_HASH_MISMATCH,
        SNAPSHOT_INVALID, RECOVERY_REQUIRED
    }
}
