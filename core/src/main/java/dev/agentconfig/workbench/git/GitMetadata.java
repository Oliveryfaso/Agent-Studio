package dev.agentconfig.workbench.git;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record GitMetadata(
        boolean isGitWorkspace,
        GitDirDescriptor gitDir,
        Optional<GitHead> head,
        WorktreeState worktreeState,
        List<GitProbeFinding> findings,
        List<GitProbeUnknown> unknowns) {
    public GitMetadata {
        Objects.requireNonNull(gitDir, "gitDir");
        head = Objects.requireNonNull(head, "head");
        Objects.requireNonNull(worktreeState, "worktreeState");
        findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
        unknowns = List.copyOf(Objects.requireNonNull(unknowns, "unknowns"));
    }

    /** This probe deliberately does not inspect the index or worktree files. */
    public enum WorktreeState {
        UNKNOWN_NOT_PROBED
    }
}
