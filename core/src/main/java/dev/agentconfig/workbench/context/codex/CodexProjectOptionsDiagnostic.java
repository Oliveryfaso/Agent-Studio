package dev.agentconfig.workbench.context.codex;

import java.util.Objects;

/** A redacted parser diagnostic. Messages never contain source text or parsed values. */
public record CodexProjectOptionsDiagnostic(
        CodexProjectOptionsDiagnosticCode code,
        String key,
        int line,
        String message) {
    public CodexProjectOptionsDiagnostic {
        Objects.requireNonNull(code, "code");
        key = Objects.requireNonNull(key, "key");
        message = Objects.requireNonNull(message, "message");
        if (line < 0) {
            throw new IllegalArgumentException("line must not be negative");
        }
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }
}
