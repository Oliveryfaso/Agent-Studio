package dev.agentconfig.workbench.context;

import java.nio.file.Path;
import java.util.Objects;

public record ContextRelation(
        ContextRelationKind kind,
        Path fromLogicalPath,
        Path toLogicalPath,
        String detail) {
    public ContextRelation {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(fromLogicalPath, "fromLogicalPath");
        Objects.requireNonNull(toLogicalPath, "toLogicalPath");
        Objects.requireNonNull(detail, "detail");
    }
}
