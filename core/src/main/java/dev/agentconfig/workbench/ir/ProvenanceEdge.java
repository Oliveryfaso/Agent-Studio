package dev.agentconfig.workbench.ir;

import java.util.Objects;

public record ProvenanceEdge(
        ProvenanceKind kind,
        IrNodeRef from,
        IrNodeRef to) {
    public ProvenanceEdge {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.equals(to)) {
            throw new IllegalArgumentException("provenance edge must not reference itself");
        }
        if ((kind == ProvenanceKind.IMPORTS || kind == ProvenanceKind.SHADOWS)
                && (from.kind() != IrNodeKind.SOURCE || to.kind() != IrNodeKind.SOURCE)) {
            throw new IllegalArgumentException(kind + " edges must connect source nodes");
        }
    }
}
