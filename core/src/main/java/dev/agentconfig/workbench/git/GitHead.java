package dev.agentconfig.workbench.git;

import java.util.Objects;

public record GitHead(Kind kind, String value) {
    public GitHead {
        Objects.requireNonNull(kind, "kind");
        value = Objects.requireNonNull(value, "value").strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("HEAD value must not be empty");
        }
    }

    public enum Kind {
        SYMBOLIC_REF,
        DETACHED_HASH
    }
}
