package dev.agentconfig.workbench.analyze;

public record DirectiveSourceMetadata(int sourceOrder, int startingLine) {
    public DirectiveSourceMetadata {
        if (sourceOrder < 0) {
            throw new IllegalArgumentException("sourceOrder must be non-negative");
        }
        if (startingLine < 1) {
            throw new IllegalArgumentException("startingLine must be positive");
        }
    }
}
