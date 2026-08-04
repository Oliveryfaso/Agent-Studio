package dev.agentconfig.workbench.analyze;

import java.util.Objects;

public record AnalysisNotice(String code, String sourceId, int line) {
    public AnalysisNotice {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(sourceId, "sourceId");
        if (code.isBlank() || line < 0) {
            throw new IllegalArgumentException("Analysis notice metadata is invalid");
        }
    }
}
