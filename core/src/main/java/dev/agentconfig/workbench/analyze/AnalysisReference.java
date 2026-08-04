package dev.agentconfig.workbench.analyze;

import java.util.Objects;

public record AnalysisReference(String sourceId, String directiveId, int line) {
    public AnalysisReference {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(directiveId, "directiveId");
        if (sourceId.isBlank() || line < 0) {
            throw new IllegalArgumentException("Analysis reference is invalid");
        }
    }
}
