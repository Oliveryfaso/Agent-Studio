package dev.agentconfig.workbench.scan;

import dev.agentconfig.workbench.host.ArtifactType;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

public record DiscoveredArtifact(
        Path logicalPath,
        Path realPath,
        Set<String> hostIds,
        Set<ArtifactType> artifactTypes,
        boolean symbolicLink,
        long byteSize,
        String sha256,
        EncodingHint encodingHint,
        LineEnding lineEnding) {

    public DiscoveredArtifact {
        Objects.requireNonNull(logicalPath, "logicalPath");
        Objects.requireNonNull(realPath, "realPath");
        hostIds = Set.copyOf(Objects.requireNonNull(hostIds, "hostIds"));
        artifactTypes = Set.copyOf(Objects.requireNonNull(artifactTypes, "artifactTypes"));
        sha256 = Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(encodingHint, "encodingHint");
        Objects.requireNonNull(lineEnding, "lineEnding");
        if (hostIds.isEmpty() || artifactTypes.isEmpty()) {
            throw new IllegalArgumentException("Discovered artifacts require host and type matches");
        }
        if (byteSize < 0 || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid artifact size or SHA-256");
        }
    }
}
