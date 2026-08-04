package dev.agentconfig.workbench.conversion;

public enum CapabilityChange {
    UNCHANGED,
    REDUCED,
    ADDED,
    UNKNOWN;

    public boolean blocksReadyStatus() {
        return this == ADDED || this == UNKNOWN;
    }
}
