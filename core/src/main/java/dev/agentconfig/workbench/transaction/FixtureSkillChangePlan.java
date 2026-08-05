package dev.agentconfig.workbench.transaction;

import java.util.Objects;
import java.util.Optional;

/** Metadata-only approval plan for the S3 fixture transaction slice. */
public record FixtureSkillChangePlan(
        int schemaVersion,
        String id,
        Status status,
        String rootIdentitySha256,
        String logicalPath,
        String candidateSha256,
        Optional<String> preimageSha256,
        long preimageBytes,
        Optional<String> preimageIdentity,
        Optional<String> diffSha256,
        Optional<String> approvalToken,
        Optional<String> blockedReason,
        boolean fixtureOnly,
        boolean workspaceContentIncluded,
        boolean writesPerformed,
        boolean applyEligible) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public FixtureSkillChangePlan {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) throw new IllegalArgumentException("schema");
        id = required(id, "id");
        Objects.requireNonNull(status, "status");
        rootIdentitySha256 = hash(rootIdentitySha256, "rootIdentitySha256");
        logicalPath = required(logicalPath, "logicalPath");
        candidateSha256 = hash(candidateSha256, "candidateSha256");
        preimageSha256 = Objects.requireNonNull(preimageSha256, "preimageSha256")
                .map(value -> hash(value, "preimageSha256"));
        preimageIdentity = Objects.requireNonNull(preimageIdentity, "preimageIdentity")
                .map(value -> required(value, "preimageIdentity"));
        diffSha256 = Objects.requireNonNull(diffSha256, "diffSha256")
                .map(value -> hash(value, "diffSha256"));
        approvalToken = Objects.requireNonNull(approvalToken, "approvalToken")
                .map(value -> required(value, "approvalToken"));
        blockedReason = Objects.requireNonNull(blockedReason, "blockedReason")
                .map(value -> required(value, "blockedReason"));
        if (!fixtureOnly || workspaceContentIncluded || writesPerformed) {
            throw new IllegalArgumentException("fixture preview flags are invalid");
        }
        boolean ready = status == Status.READY_CREATE || status == Status.READY_REPLACE;
        if (applyEligible != ready || ready != approvalToken.isPresent()
                || ready != diffSha256.isPresent()) {
            throw new IllegalArgumentException("approval fields disagree with status");
        }
        boolean existing = status == Status.READY_REPLACE || status == Status.NO_CHANGE;
        if (existing != preimageSha256.isPresent() || existing != preimageIdentity.isPresent()
                || (existing ? preimageBytes < 0 : preimageBytes != -1)) {
            throw new IllegalArgumentException("preimage fields disagree with status");
        }
        if ((status == Status.BLOCKED) != blockedReason.isPresent()) {
            throw new IllegalArgumentException("blocked reason disagrees with status");
        }
    }

    public enum Status { READY_CREATE, READY_REPLACE, NO_CHANGE, BLOCKED }

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
