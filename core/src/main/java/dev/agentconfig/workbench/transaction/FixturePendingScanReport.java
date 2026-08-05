package dev.agentconfig.workbench.transaction;

import java.util.List;
import java.util.Optional;

/** Content-free result of an explicit, read-only fixture transaction scan. */
public record FixturePendingScanReport(
        int schemaVersion,
        Status status,
        List<PendingTransaction> pendingTransactions,
        int directEntriesObserved,
        int manifestsInspected,
        int terminalTransactions,
        int invalidEntries,
        int invalidTransactions,
        Optional<String> nextCursor,
        boolean fixtureOnly,
        boolean contentIncluded,
        boolean writesPerformed,
        String detail) {
    public FixturePendingScanReport {
        if (schemaVersion != 1) throw new IllegalArgumentException("schemaVersion");
        if (status == null) throw new NullPointerException("status");
        pendingTransactions = List.copyOf(pendingTransactions);
        if (directEntriesObserved < 0 || manifestsInspected < 0 || terminalTransactions < 0
                || invalidEntries < 0 || invalidTransactions < 0) {
            throw new IllegalArgumentException("counts");
        }
        nextCursor = nextCursor == null ? Optional.empty() : nextCursor;
        if (nextCursor.isPresent() && !nextCursor.orElseThrow().matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("nextCursor");
        }
        if ((status == Status.PARTIAL_MANIFEST_BUDGET) != nextCursor.isPresent()) {
            throw new IllegalArgumentException("status/cursor");
        }
        if (!fixtureOnly || contentIncluded || writesPerformed) {
            throw new IllegalArgumentException("scan capability flags");
        }
        if (detail == null || detail.isBlank()) throw new IllegalArgumentException("detail");
    }

    public enum Status {
        COMPLETE,
        PARTIAL_MANIFEST_BUDGET,
        DIRECT_ENTRY_BUDGET_EXCEEDED,
        INVALID_STATE_ROOT
    }

    public record PendingTransaction(String transactionId, Action action, Phase phase) {
        public PendingTransaction {
            if (transactionId == null || !transactionId.matches(
                    "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) {
                throw new IllegalArgumentException("transactionId");
            }
            if (action == null || phase == null) throw new NullPointerException("pending state");
            if ((phase == Phase.ROLLBACK_INTENT) != (action == Action.RECOVER_ROLLBACK)) {
                throw new IllegalArgumentException("action/phase");
            }
        }
    }

    public enum Action { RECOVER_APPLY, RECOVER_ROLLBACK }

    public enum Phase { PREPARED, COMMIT_INTENT, ROLLBACK_INTENT }
}
