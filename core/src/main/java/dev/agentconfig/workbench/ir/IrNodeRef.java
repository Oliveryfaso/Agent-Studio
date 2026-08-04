package dev.agentconfig.workbench.ir;

import java.util.Objects;

public record IrNodeRef(IrNodeKind kind, String id) {
    public IrNodeRef {
        Objects.requireNonNull(kind, "kind");
        id = IrValidation.id(id, "node id");
    }

    public static IrNodeRef source(String sourceId) {
        return new IrNodeRef(IrNodeKind.SOURCE, sourceId);
    }

    public static IrNodeRef directive(String directiveId) {
        return new IrNodeRef(IrNodeKind.DIRECTIVE, directiveId);
    }
}
