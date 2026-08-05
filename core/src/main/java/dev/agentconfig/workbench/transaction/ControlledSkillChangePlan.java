package dev.agentconfig.workbench.transaction;

import java.util.Optional;

/** Metadata-only plan for the transitional real-workspace existing-Skill editor. */
public record ControlledSkillChangePlan(
        int schemaVersion,
        String id,
        Status status,
        String rootIdentitySha256,
        String logicalPath,
        String candidateSha256,
        Optional<String> preimageSha256,
        Optional<String> preimageIdentity,
        Optional<String> permissions,
        Optional<String> diffSha256,
        Optional<String> approvalToken,
        Optional<String> blockedReason,
        boolean contentIncluded,
        boolean writesPerformed,
        boolean applyEligible) {
    public ControlledSkillChangePlan {
        if (schemaVersion != 1) throw new IllegalArgumentException("schemaVersion");
        if (id == null || !id.matches("csp_[0-9a-f]{64}")) throw new IllegalArgumentException("id");
        if (status == null) throw new NullPointerException("status");
        hash(rootIdentitySha256, "rootIdentitySha256");
        if (logicalPath == null || !logicalPath.matches(
                "\\.agents/skills/[a-z0-9]+(?:-[a-z0-9]+)*/SKILL\\.md")) {
            throw new IllegalArgumentException("logicalPath");
        }
        hash(candidateSha256, "candidateSha256");
        preimageSha256 = requiredOptional(preimageSha256, "preimageSha256", true);
        preimageIdentity = requiredOptional(preimageIdentity, "preimageIdentity", false);
        permissions = requiredOptional(permissions, "permissions", false);
        diffSha256 = requiredOptional(diffSha256, "diffSha256", true);
        approvalToken = requiredOptional(approvalToken, "approvalToken", false);
        blockedReason = requiredOptional(blockedReason, "blockedReason", false);
        if (contentIncluded || writesPerformed) throw new IllegalArgumentException("capability flags");
        boolean ready = status == Status.READY_REPLACE;
        if (applyEligible != ready || ready != approvalToken.isPresent()
                || ready != diffSha256.isPresent()) throw new IllegalArgumentException("approval");
        boolean targetKnown = status == Status.READY_REPLACE || status == Status.NO_CHANGE;
        if (targetKnown != preimageSha256.isPresent()
                || targetKnown != preimageIdentity.isPresent()
                || targetKnown != permissions.isPresent()) throw new IllegalArgumentException("preimage");
        if ((status == Status.BLOCKED) != blockedReason.isPresent()) {
            throw new IllegalArgumentException("blockedReason");
        }
    }

    public enum Status { READY_REPLACE, NO_CHANGE, BLOCKED }

    private static Optional<String> requiredOptional(
            Optional<String> value, String field, boolean hash) {
        if (value == null) throw new NullPointerException(field);
        return value.map(item -> {
            if (item.isBlank() || (hash && !item.matches("[0-9a-f]{64}"))) {
                throw new IllegalArgumentException(field);
            }
            return item;
        });
    }

    private static void hash(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field);
        }
    }
}
