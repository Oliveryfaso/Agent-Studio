package dev.agentconfig.workbench.git;

import java.nio.file.Path;
import java.util.Objects;

/** Explicit authorization boundary for the read-only Git metadata probe. */
public record GitProbeRequest(Path approvedRoot, boolean allowBoundedExternalWorktreeMetadata) {
    public GitProbeRequest {
        Objects.requireNonNull(approvedRoot, "approvedRoot");
    }

    public static GitProbeRequest strict(Path approvedRoot) {
        return new GitProbeRequest(approvedRoot, false);
    }
}
