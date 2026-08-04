package dev.agentconfig.workbench.git;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public record GitDirDescriptor(Kind kind, Location location, Optional<Path> path) {
    public GitDirDescriptor {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(location, "location");
        path = Objects.requireNonNull(path, "path").map(Path::normalize);
    }

    public enum Kind {
        MISSING,
        DIRECTORY,
        GITFILE_POINTER,
        SYMLINK_REJECTED,
        UNSUPPORTED
    }

    public enum Location {
        NONE,
        WITHIN_APPROVED_ROOT,
        BOUNDED_EXTERNAL_WORKTREE,
        OUTSIDE_APPROVED_ROOT_REJECTED,
        UNRESOLVED
    }
}
