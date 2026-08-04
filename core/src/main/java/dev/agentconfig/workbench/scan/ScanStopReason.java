package dev.agentconfig.workbench.scan;

public enum ScanStopReason {
    NONE,
    CANCELLED,
    DEPTH_LIMIT_REACHED,
    ENTRY_LIMIT_REACHED,
    TOTAL_BYTE_LIMIT_REACHED
}
