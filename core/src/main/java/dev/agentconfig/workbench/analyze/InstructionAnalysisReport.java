package dev.agentconfig.workbench.analyze;

import dev.agentconfig.workbench.ir.InstructionIr;
import dev.agentconfig.workbench.context.ProjectSemanticProfile;
import java.util.List;
import java.util.Objects;

public record InstructionAnalysisReport(
        int schemaVersion,
        int contextSchemaVersion,
        String semanticProfile,
        InstructionIr instructionIr,
        List<AnalysisFinding> findings,
        List<AnalysisNotice> notices,
        AnalysisSummary summary) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public InstructionAnalysisReport {
        if (schemaVersion != CURRENT_SCHEMA_VERSION || contextSchemaVersion != 2) {
            throw new IllegalArgumentException("Unsupported analysis or context schema version");
        }
        Objects.requireNonNull(instructionIr, "instructionIr");
        ProjectSemanticProfile profile = ProjectSemanticProfile.fromId(
                Objects.requireNonNull(semanticProfile, "semanticProfile"));
        if (instructionIr.sources().stream().anyMatch(
                source -> !source.identity().hostId().equals(profile.hostId()))) {
            throw new IllegalArgumentException("Semantic profile does not match IR source host");
        }
        findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
        notices = List.copyOf(Objects.requireNonNull(notices, "notices"));
        Objects.requireNonNull(summary, "summary");
    }
}
