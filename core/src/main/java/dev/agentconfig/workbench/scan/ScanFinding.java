package dev.agentconfig.workbench.scan;

import java.nio.file.Path;
import java.util.Objects;

public record ScanFinding(Severity severity, FindingCode code, Path logicalPath, String detail) {
    public ScanFinding {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(logicalPath, "logicalPath");
        detail = Objects.requireNonNull(detail, "detail").strip();
        if (detail.isEmpty()) {
            throw new IllegalArgumentException("Finding detail must not be empty");
        }
    }
}
