package dev.agentconfig.workbench.ir;

import java.util.Objects;

public record InstructionScope(ScopeKind kind, String expression) {
    public InstructionScope {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(expression, "expression");
        if (kind == ScopeKind.GLOBAL || kind == ScopeKind.PROJECT) {
            if (!expression.isEmpty()) {
                throw new IllegalArgumentException(kind + " scope expression must be empty");
            }
        } else if (expression.isBlank()) {
            throw new IllegalArgumentException(kind + " scope expression must not be blank");
        }
        if (expression.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("scope expression must not contain NUL");
        }
    }

    public static InstructionScope project() {
        return new InstructionScope(ScopeKind.PROJECT, "");
    }
}
