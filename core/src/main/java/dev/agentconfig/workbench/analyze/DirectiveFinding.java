package dev.agentconfig.workbench.analyze;

import java.util.List;
import java.util.Objects;

public record DirectiveFinding(
        String id,
        DirectiveFindingType type,
        DirectiveFindingClassification classification,
        String subjectHash,
        List<DirectiveReference> references) {
    public DirectiveFinding {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(subjectHash, "subjectHash");
        references = List.copyOf(Objects.requireNonNull(references, "references"));
        if (references.size() < 2) {
            throw new IllegalArgumentException("A finding must reference at least two units");
        }
    }
}
