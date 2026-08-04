package dev.agentconfig.workbench.context.claude;

import java.util.List;
import java.util.Objects;

public record ClaudeRuleDefinition(
        boolean frontmatterPresent,
        boolean valid,
        List<String> paths,
        List<ClaudeDiagnostic> diagnostics) {
    public ClaudeRuleDefinition {
        paths = List.copyOf(Objects.requireNonNull(paths, "paths"));
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    public boolean unconditional() {
        return valid && paths.isEmpty();
    }
}
