package dev.agentconfig.workbench.context;

import java.nio.file.Path;
import java.util.Objects;

public record ContextSource(
        Path logicalPath,
        Path realPath,
        ContextSourceKind kind,
        ContextSourceState state,
        int precedence,
        long byteSize,
        long includedBytes,
        String sha256,
        String detail) {
    public ContextSource {
        Objects.requireNonNull(logicalPath, "logicalPath");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(detail, "detail");
        if (precedence < 0 || byteSize < 0 || includedBytes < 0 || includedBytes > byteSize) {
            throw new IllegalArgumentException("Invalid context source metadata");
        }
    }

    public boolean active() {
        return state == ContextSourceState.ACTIVE || state == ContextSourceState.ACTIVE_TRUNCATED;
    }
}
