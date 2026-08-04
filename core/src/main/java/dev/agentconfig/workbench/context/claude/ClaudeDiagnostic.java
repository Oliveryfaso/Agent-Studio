package dev.agentconfig.workbench.context.claude;

import java.util.Objects;

/** A bounded parser diagnostic. Line and column are one-based, or zero when not applicable. */
public record ClaudeDiagnostic(
        String code,
        ClaudeDiagnosticSeverity severity,
        String message,
        int line,
        int column) {
    public ClaudeDiagnostic {
        code = Objects.requireNonNull(code, "code");
        severity = Objects.requireNonNull(severity, "severity");
        message = Objects.requireNonNull(message, "message");
        if (line < 0 || column < 0) {
            throw new IllegalArgumentException("Diagnostic positions cannot be negative");
        }
    }
}
