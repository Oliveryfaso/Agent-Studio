package dev.agentconfig.workbench.context;

public enum ContextSourceState {
    ACTIVE,
    ACTIVE_TRUNCATED,
    SHADOWED,
    EMPTY,
    SKIPPED_LIMIT,
    SKIPPED_TOO_LARGE,
    SKIPPED_SYMLINK,
    SKIPPED_SPECIAL_FILE,
    NOT_EVALUATED,
    CONDITIONAL_NO_MATCH,
    EXTERNAL_APPROVAL_REQUIRED,
    INVALID
}
