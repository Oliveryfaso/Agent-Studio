package dev.agentconfig.workbench.ir;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record InstructionIr(
        int schemaVersion,
        String id,
        IrResolutionStatus resolutionStatus,
        List<InstructionSource> sources,
        List<DirectiveUnit> directives,
        List<ProvenanceEdge> provenance,
        List<String> limitations) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public InstructionIr {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported Instruction IR schema version: " + schemaVersion);
        }
        id = IrValidation.id(id, "IR id");
        Objects.requireNonNull(resolutionStatus, "resolutionStatus");
        sources = copyWithoutNulls(sources, "sources");
        directives = copyWithoutNulls(directives, "directives");
        provenance = copyWithoutNulls(provenance, "provenance");
        limitations = copyWithoutNulls(limitations, "limitations");
        limitations.forEach(value -> IrValidation.nonBlank(value, "limitation"));

        if (resolutionStatus == IrResolutionStatus.COMPLETE && !limitations.isEmpty()) {
            throw new IllegalArgumentException("COMPLETE IR must not contain limitations");
        }
        if (resolutionStatus != IrResolutionStatus.COMPLETE && limitations.isEmpty()) {
            throw new IllegalArgumentException("PARTIAL or INVALID IR requires at least one limitation");
        }

        Map<String, InstructionSource> sourceById = new HashMap<>();
        Set<String> hostAndPath = new HashSet<>();
        Set<Integer> loadOrders = new HashSet<>();
        for (InstructionSource source : sources) {
            String sourceId = source.identity().sourceId();
            if (sourceById.put(sourceId, source) != null) {
                throw new IllegalArgumentException("Duplicate source id: " + sourceId);
            }
            String pathKey = source.identity().hostId() + '\u0000' + source.logicalPath();
            if (!hostAndPath.add(pathKey)) {
                throw new IllegalArgumentException("Duplicate host/logical path: " + source.logicalPath());
            }
            if (source.loadOrder() >= 0 && !loadOrders.add(source.loadOrder())) {
                throw new IllegalArgumentException("Duplicate source load order: " + source.loadOrder());
            }
        }

        Set<String> directiveIds = new HashSet<>();
        for (DirectiveUnit directive : directives) {
            if (!directiveIds.add(directive.id())) {
                throw new IllegalArgumentException("Duplicate directive id: " + directive.id());
            }
            InstructionSource owner = sourceById.get(directive.source().sourceId());
            if (owner == null || !owner.identity().equals(directive.source())) {
                throw new IllegalArgumentException(
                        "Directive references an unknown source: " + directive.source().sourceId());
            }
        }

        Set<ProvenanceEdge> uniqueEdges = new HashSet<>();
        for (ProvenanceEdge edge : provenance) {
            if (!uniqueEdges.add(edge)) {
                throw new IllegalArgumentException("Duplicate provenance edge: " + edge);
            }
            validateReference(edge.from(), sourceById.keySet(), directiveIds);
            validateReference(edge.to(), sourceById.keySet(), directiveIds);
        }
    }

    private static void validateReference(
            IrNodeRef reference,
            Set<String> sourceIds,
            Set<String> directiveIds) {
        boolean exists = switch (reference.kind()) {
            case SOURCE -> sourceIds.contains(reference.id());
            case DIRECTIVE -> directiveIds.contains(reference.id());
        };
        if (!exists) {
            throw new IllegalArgumentException(
                    "Provenance references an unknown " + reference.kind() + ": " + reference.id());
        }
    }

    private static <T> List<T> copyWithoutNulls(List<T> values, String name) {
        List<T> copy = List.copyOf(Objects.requireNonNull(values, name));
        if (copy.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(name + " must not contain null");
        }
        return copy;
    }
}
