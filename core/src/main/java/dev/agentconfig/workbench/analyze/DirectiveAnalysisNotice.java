package dev.agentconfig.workbench.analyze;

import java.util.Objects;

public record DirectiveAnalysisNotice(String code, String sourceId, int line) {
    public DirectiveAnalysisNotice {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(sourceId, "sourceId");
        if (line < 0) {
            throw new IllegalArgumentException("line must be non-negative");
        }
    }
}
