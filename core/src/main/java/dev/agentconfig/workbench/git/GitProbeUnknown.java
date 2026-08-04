package dev.agentconfig.workbench.git;

import java.util.Objects;

public record GitProbeUnknown(Code code, String detail) {
    public GitProbeUnknown {
        Objects.requireNonNull(code, "code");
        detail = Objects.requireNonNull(detail, "detail").strip();
        if (detail.isEmpty()) {
            throw new IllegalArgumentException("Unknown detail must not be empty");
        }
    }

    public enum Code {
        NOT_A_GIT_WORKSPACE,
        HEAD_UNAVAILABLE,
        DIRTY_STATE_NOT_PROBED
    }
}
