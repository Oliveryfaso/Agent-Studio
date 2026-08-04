package dev.agentconfig.workbench.context.claude;

import java.util.List;
import java.util.Objects;

public record ClaudeRuleEvaluation(
        ClaudeRuleApplicability applicability,
        List<String> configuredPatterns,
        List<String> matchingPatterns,
        List<ClaudeDiagnostic> diagnostics) {
    public ClaudeRuleEvaluation {
        applicability = Objects.requireNonNull(applicability, "applicability");
        configuredPatterns = List.copyOf(Objects.requireNonNull(configuredPatterns, "configuredPatterns"));
        matchingPatterns = List.copyOf(Objects.requireNonNull(matchingPatterns, "matchingPatterns"));
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }
}
