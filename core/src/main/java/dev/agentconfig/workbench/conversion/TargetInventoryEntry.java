package dev.agentconfig.workbench.conversion;

import java.util.Objects;

/** Read-only metadata supplied by a bounded target probe; never target content. */
public record TargetInventoryEntry(
        String logicalPath,
        TargetInventoryState state,
        String sha256,
        long byteSize) {
    public TargetInventoryEntry {
        logicalPath = ConversionValidation.logicalPath(logicalPath);
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(sha256, "sha256");
        if (state == TargetInventoryState.PRESENT) {
            sha256 = ConversionValidation.sha256(sha256, "target inventory hash");
            if (byteSize < 0) {
                throw new IllegalArgumentException("present target requires byte size");
            }
        } else if (!sha256.isEmpty() || byteSize != -1) {
            throw new IllegalArgumentException("non-present target cannot contain file metadata");
        }
    }

    public static TargetInventoryEntry absent(String logicalPath) {
        return new TargetInventoryEntry(logicalPath, TargetInventoryState.ABSENT, "", -1);
    }

    public static TargetInventoryEntry unknown(String logicalPath) {
        return new TargetInventoryEntry(logicalPath, TargetInventoryState.NOT_EVALUATED, "", -1);
    }
}
