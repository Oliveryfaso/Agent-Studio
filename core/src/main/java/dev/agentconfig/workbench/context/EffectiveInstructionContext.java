package dev.agentconfig.workbench.context;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record EffectiveInstructionContext(
        String hostId,
        String semanticProfile,
        String supportLevel,
        String orderingModel,
        ContextResolutionStatus resolutionStatus,
        Path logicalRoot,
        Path realRoot,
        Path currentDirectory,
        Path targetFile,
        boolean explicitConfigSnapshot,
        Instant compiledAt,
        long maxCombinedBytes,
        long includedBytes,
        List<ContextSource> sources,
        List<ContextRelation> relations,
        List<ContextFinding> findings,
        List<String> limitations) {
    public EffectiveInstructionContext {
        Objects.requireNonNull(hostId, "hostId");
        Objects.requireNonNull(semanticProfile, "semanticProfile");
        Objects.requireNonNull(supportLevel, "supportLevel");
        Objects.requireNonNull(orderingModel, "orderingModel");
        Objects.requireNonNull(resolutionStatus, "resolutionStatus");
        Objects.requireNonNull(logicalRoot, "logicalRoot");
        Objects.requireNonNull(realRoot, "realRoot");
        Objects.requireNonNull(currentDirectory, "currentDirectory");
        Objects.requireNonNull(compiledAt, "compiledAt");
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        relations = List.copyOf(Objects.requireNonNull(relations, "relations"));
        findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
        limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations"));
        if (maxCombinedBytes < 0 || includedBytes < 0) {
            throw new IllegalArgumentException("Byte counts must not be negative");
        }
        if (!ProjectSemanticProfile.forHost(hostId).id().equals(semanticProfile)) {
            throw new IllegalArgumentException("Semantic profile does not match host");
        }
    }
}
