package dev.agentconfig.workbench.context;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public record ContextCompileRequest(
        String hostId,
        Path authorizedRoot,
        Path currentDirectory,
        Optional<Path> codexConfigSnapshot,
        Optional<Path> targetFile) {
    public ContextCompileRequest {
        Objects.requireNonNull(hostId, "hostId");
        Objects.requireNonNull(authorizedRoot, "authorizedRoot");
        Objects.requireNonNull(currentDirectory, "currentDirectory");
        codexConfigSnapshot = Objects.requireNonNull(codexConfigSnapshot, "codexConfigSnapshot");
        targetFile = Objects.requireNonNull(targetFile, "targetFile");
        if (!"codex".equals(hostId) && !"claude-code".equals(hostId)) {
            throw new IllegalArgumentException("Effective context is only available for codex and claude-code");
        }
        if ("codex".equals(hostId) && targetFile.isPresent()) {
            throw new IllegalArgumentException("--target-file is only valid for claude-code");
        }
        if ("claude-code".equals(hostId) && codexConfigSnapshot.isPresent()) {
            throw new IllegalArgumentException("--codex-config is only valid for codex");
        }
    }

    public static ContextCompileRequest defaults(String hostId, Path root, Path currentDirectory) {
        return new ContextCompileRequest(
                hostId, root, currentDirectory, Optional.empty(), Optional.empty());
    }
}
