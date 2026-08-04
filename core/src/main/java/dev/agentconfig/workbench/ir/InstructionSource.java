package dev.agentconfig.workbench.ir;

import java.util.List;
import java.util.Objects;

public record InstructionSource(
        SourceIdentity identity,
        InstructionSourceKind kind,
        InstructionSourceState state,
        String revisionSha256,
        String effectiveSha256,
        long byteSize,
        long includedBytes,
        String logicalPath,
        InstructionScope scope,
        int loadOrder,
        List<ActivationEvidence> activationEvidence) {
    public static final int UNORDERED = -1;

    public InstructionSource {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(state, "state");
        revisionSha256 = IrValidation.optionalSha256(revisionSha256, "revisionSha256");
        effectiveSha256 = IrValidation.optionalSha256(effectiveSha256, "effectiveSha256");
        if (byteSize < 0 || includedBytes < 0 || includedBytes > byteSize) {
            throw new IllegalArgumentException("source byte counts are inconsistent");
        }
        if (!state.participatesInLoadOrder() && includedBytes != 0) {
            throw new IllegalArgumentException("inactive sources cannot include effective bytes");
        }
        if (!state.participatesInLoadOrder() && !effectiveSha256.isEmpty()) {
            throw new IllegalArgumentException("inactive sources cannot have an effective payload hash");
        }
        logicalPath = IrValidation.logicalPath(logicalPath);
        Objects.requireNonNull(scope, "scope");
        activationEvidence = List.copyOf(Objects.requireNonNull(
                activationEvidence, "activationEvidence"));
        if (activationEvidence.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("activationEvidence must not contain null");
        }
        if (state.participatesInLoadOrder() != (loadOrder >= 0)) {
            throw new IllegalArgumentException(
                    "active sources require a non-negative loadOrder; other sources must be UNORDERED");
        }
    }
}
