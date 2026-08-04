package dev.agentconfig.workbench.analyze;

import java.util.List;
import java.util.Objects;

public record DirectiveAnalysis(
        List<DirectiveUnit> units,
        List<DirectiveFinding> findings,
        List<DirectiveAnalysisNotice> notices) {
    public DirectiveAnalysis {
        units = List.copyOf(Objects.requireNonNull(units, "units"));
        findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
        notices = List.copyOf(Objects.requireNonNull(notices, "notices"));
    }
}
