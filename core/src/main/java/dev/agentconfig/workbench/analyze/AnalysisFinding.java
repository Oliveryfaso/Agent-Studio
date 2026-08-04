package dev.agentconfig.workbench.analyze;

import java.util.List;
import java.util.Objects;

public record AnalysisFinding(
        String id,
        AnalysisFindingType type,
        AnalysisCertainty certainty,
        String evidenceHash,
        List<AnalysisReference> references) {
    public AnalysisFinding {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(certainty, "certainty");
        Objects.requireNonNull(evidenceHash, "evidenceHash");
        references = List.copyOf(Objects.requireNonNull(references, "references"));
        if (id.isBlank() || !evidenceHash.matches("[0-9a-f]{64}") || references.size() < 2) {
            throw new IllegalArgumentException("Analysis finding metadata is invalid");
        }
    }
}
