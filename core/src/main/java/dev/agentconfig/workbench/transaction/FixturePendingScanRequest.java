package dev.agentconfig.workbench.transaction;

import java.util.Optional;

/** Bounded, content-free request for explicit fixture transaction discovery. */
public record FixturePendingScanRequest(
        int schemaVersion,
        int maxDirectEntries,
        int maxManifests,
        Optional<String> afterTransactionIdExclusive) {
    public FixturePendingScanRequest {
        if (schemaVersion != 1) throw new IllegalArgumentException("schemaVersion");
        if (maxDirectEntries < 1 || maxDirectEntries > 512) {
            throw new IllegalArgumentException("maxDirectEntries");
        }
        if (maxManifests < 1 || maxManifests > 256) {
            throw new IllegalArgumentException("maxManifests");
        }
        afterTransactionIdExclusive = afterTransactionIdExclusive == null
                ? Optional.empty() : afterTransactionIdExclusive;
        if (afterTransactionIdExclusive.isPresent()
                && !afterTransactionIdExclusive.orElseThrow().matches(
                        "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("afterTransactionIdExclusive");
        }
    }

    public static FixturePendingScanRequest defaults() {
        return new FixturePendingScanRequest(1, 128, 64, Optional.empty());
    }
}
