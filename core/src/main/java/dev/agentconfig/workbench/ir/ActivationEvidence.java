package dev.agentconfig.workbench.ir;

import java.util.Objects;

public record ActivationEvidence(
        ActivationEvidenceKind kind,
        ActivationOutcome outcome,
        String expression) {
    public ActivationEvidence {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(expression, "expression");
        if (expression.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("activation expression must not contain NUL");
        }
    }
}
