package dev.agentconfig.workbench.ir;

public enum InstructionSourceState {
    ACTIVE,
    ACTIVE_TRUNCATED,
    INACTIVE,
    SHADOWED,
    MISSING,
    INVALID,
    NOT_EVALUATED;

    public boolean participatesInLoadOrder() {
        return this == ACTIVE || this == ACTIVE_TRUNCATED;
    }
}
