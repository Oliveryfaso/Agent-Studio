package dev.agentconfig.workbench.analyze;

import java.util.Objects;

/** A redacted directive. No source text is retained in this value. */
public record DirectiveUnit(
        String id,
        String sourceId,
        int line,
        DirectivePolarity polarity,
        String normalizedSha256,
        String subjectHash) {
    public DirectiveUnit {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(polarity, "polarity");
        Objects.requireNonNull(normalizedSha256, "normalizedSha256");
        Objects.requireNonNull(subjectHash, "subjectHash");
        if (line < 1) {
            throw new IllegalArgumentException("line must be positive");
        }
    }
}
