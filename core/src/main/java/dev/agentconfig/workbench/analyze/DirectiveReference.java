package dev.agentconfig.workbench.analyze;

import java.util.Objects;

public record DirectiveReference(String sourceId, String unitId, int line) {
    public DirectiveReference {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(unitId, "unitId");
        if (line < 1) {
            throw new IllegalArgumentException("line must be positive");
        }
    }
}
