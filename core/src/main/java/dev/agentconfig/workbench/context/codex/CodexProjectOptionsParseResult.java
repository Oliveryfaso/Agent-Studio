package dev.agentconfig.workbench.context.codex;

import java.util.List;
import java.util.Objects;

/** Parsed values plus any structured, redacted errors. */
public record CodexProjectOptionsParseResult(
        CodexProjectOptions options,
        List<CodexProjectOptionsDiagnostic> diagnostics) {
    public CodexProjectOptionsParseResult {
        Objects.requireNonNull(options, "options");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    public boolean valid() {
        return diagnostics.isEmpty();
    }
}
