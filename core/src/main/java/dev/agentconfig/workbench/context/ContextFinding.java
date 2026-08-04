package dev.agentconfig.workbench.context;

import java.nio.file.Path;
import java.util.Objects;

public record ContextFinding(
        ContextFindingSeverity severity,
        String code,
        Path logicalPath,
        String detail) {
    public ContextFinding {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(detail, "detail");
        if (code.isBlank()) {
            throw new IllegalArgumentException("Finding code must not be blank");
        }
    }
}
