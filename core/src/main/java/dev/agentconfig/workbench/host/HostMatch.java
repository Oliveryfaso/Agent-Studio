package dev.agentconfig.workbench.host;

import java.util.Set;

public record HostMatch(String hostId, Set<ArtifactType> artifactTypes) {
    public HostMatch {
        artifactTypes = Set.copyOf(artifactTypes);
    }
}
