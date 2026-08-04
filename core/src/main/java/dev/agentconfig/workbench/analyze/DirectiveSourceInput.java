package dev.agentconfig.workbench.analyze;

import java.util.Objects;

public record DirectiveSourceInput(
        String sourceId,
        String markdown,
        DirectiveSourceMetadata metadata) {
    public DirectiveSourceInput {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(markdown, "markdown");
        Objects.requireNonNull(metadata, "metadata");
        if (sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
    }
}
