package dev.agentconfig.workbench.git;

import java.nio.file.Path;
import java.util.Objects;

public record GitProbeFinding(Severity severity, Code code, Path path, String detail) {
    public GitProbeFinding {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(code, "code");
        path = Objects.requireNonNull(path, "path").normalize();
        detail = Objects.requireNonNull(detail, "detail").strip();
        if (detail.isEmpty()) {
            throw new IllegalArgumentException("Finding detail must not be empty");
        }
    }

    public enum Severity {
        WARNING,
        ERROR,
        BLOCKING
    }

    public enum Code {
        GIT_ENTRY_SYMLINK_REJECTED,
        GIT_ENTRY_UNSUPPORTED,
        GITFILE_INVALID,
        GIT_DIR_OUTSIDE_APPROVED_ROOT,
        EXTERNAL_METADATA_NOT_AUTHORIZED,
        WORKTREE_POINTER_INVALID,
        METADATA_MISSING,
        METADATA_SYMLINK_REJECTED,
        METADATA_TOO_LARGE,
        METADATA_INVALID_UTF8,
        METADATA_INVALID_FORMAT,
        METADATA_CONCURRENTLY_MODIFIED,
        METADATA_READ_FAILED
    }
}
