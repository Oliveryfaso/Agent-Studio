package dev.agentconfig.workbench.ir;

public enum ActivationEvidenceKind {
    ALWAYS,
    CURRENT_DIRECTORY_ANCESTOR,
    PATH_MATCH,
    HOST_PRECEDENCE,
    EXPLICIT_REFERENCE,
    BYTE_BUDGET,
    UNKNOWN
}
