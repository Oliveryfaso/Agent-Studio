package dev.agentconfig.workbench.host;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record HostDescriptor(
        String id,
        String displayName,
        RoadmapTier roadmapTier,
        AdapterMaturity adapterMaturity,
        String versionStatus,
        Set<Capability> capabilities,
        List<DiscoveryRule> discoveryRules,
        List<OfficialEvidence> evidence) {

    public HostDescriptor {
        id = Objects.requireNonNull(id, "id").strip();
        displayName = Objects.requireNonNull(displayName, "displayName").strip();
        Objects.requireNonNull(roadmapTier, "roadmapTier");
        Objects.requireNonNull(adapterMaturity, "adapterMaturity");
        versionStatus = Objects.requireNonNull(versionStatus, "versionStatus").strip();
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        discoveryRules = List.copyOf(Objects.requireNonNull(discoveryRules, "discoveryRules"));
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (!id.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("Host id must be lowercase kebab-case: " + id);
        }
        if (displayName.isEmpty() || versionStatus.isEmpty()) {
            throw new IllegalArgumentException("Host display name and version status are required");
        }
        if (discoveryRules.isEmpty() || evidence.isEmpty()) {
            throw new IllegalArgumentException("Host adapters require discovery rules and official evidence");
        }
    }
}
