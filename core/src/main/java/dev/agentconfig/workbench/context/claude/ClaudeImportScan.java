package dev.agentconfig.workbench.context.claude;

import java.util.List;
import java.util.Objects;

public record ClaudeImportScan(
        List<ClaudeImport> imports,
        List<ClaudeDiagnostic> diagnostics,
        boolean inputTruncated,
        int maximumRecursiveHops) {
    public ClaudeImportScan {
        imports = List.copyOf(Objects.requireNonNull(imports, "imports"));
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        if (maximumRecursiveHops < 0) {
            throw new IllegalArgumentException("maximumRecursiveHops cannot be negative");
        }
    }
}
