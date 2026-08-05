package dev.agentconfig.workbench.transaction;

/** Content-free result of reconciling one fixture transaction after an interrupted apply. */
public record FixtureSkillRecoveryReceipt(
        int schemaVersion,
        String transactionId,
        Status status,
        String logicalPath,
        boolean fixtureOnly,
        boolean targetWritesPerformed,
        boolean stateWritesPerformed,
        boolean rollbackAvailable,
        String detail) {
    public FixtureSkillRecoveryReceipt {
        if (schemaVersion != 1) throw new IllegalArgumentException("schema");
        if (transactionId == null || !transactionId.matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("transactionId");
        }
        if (status == null) throw new NullPointerException("status");
        if (logicalPath == null || logicalPath.isBlank()) throw new IllegalArgumentException("path");
        if (!fixtureOnly) throw new IllegalArgumentException("fixtureOnly");
        if (detail == null || detail.isBlank()) throw new IllegalArgumentException("detail");
        boolean applied = status == Status.COMPLETED_APPLY
                || status == Status.FINALIZED_APPLY
                || status == Status.ALREADY_APPLIED;
        if (rollbackAvailable != applied) throw new IllegalArgumentException("rollbackAvailable");
        if (targetWritesPerformed && status != Status.COMPLETED_APPLY
                && status != Status.COMPLETED_ROLLBACK
                && status != Status.RECOVERY_REQUIRED) {
            throw new IllegalArgumentException("targetWritesPerformed");
        }
        boolean journaled = status == Status.ABORTED_PREPARED
                || status == Status.COMPLETED_APPLY
                || status == Status.FINALIZED_APPLY
                || status == Status.COMPLETED_ROLLBACK
                || status == Status.FINALIZED_ROLLBACK;
        if (stateWritesPerformed != journaled) {
            throw new IllegalArgumentException("stateWritesPerformed");
        }
    }

    public enum Status {
        ABORTED_PREPARED,
        COMPLETED_APPLY,
        FINALIZED_APPLY,
        COMPLETED_ROLLBACK,
        FINALIZED_ROLLBACK,
        ALREADY_APPLIED,
        ALREADY_ABORTED,
        ALREADY_ROLLED_BACK,
        RECOVERY_REQUIRED,
        INVALID_TRANSACTION
    }
}
