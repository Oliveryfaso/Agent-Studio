package dev.agentconfig.workbench.conversion;

import dev.agentconfig.workbench.ir.IrNodeRef;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record MappingItem(
        String id,
        MappingGrade grade,
        List<IrNodeRef> sourceProvenance,
        Optional<TargetCandidate> targetCandidate,
        CapabilityDelta capabilityDelta,
        List<String> lossIds,
        List<String> unresolvedQuestionIds) {
    public MappingItem {
        id = ConversionValidation.id(id, "mapping id");
        Objects.requireNonNull(grade, "grade");
        sourceProvenance = ConversionValidation.immutableList(
                sourceProvenance, "sourceProvenance");
        if (sourceProvenance.isEmpty()) {
            throw new IllegalArgumentException("sourceProvenance must not be empty");
        }
        targetCandidate = Objects.requireNonNull(targetCandidate, "targetCandidate");
        Objects.requireNonNull(capabilityDelta, "capabilityDelta");
        lossIds = validatedIds(lossIds, "lossIds");
        unresolvedQuestionIds = validatedIds(
                unresolvedQuestionIds, "unresolvedQuestionIds");

        if (grade == MappingGrade.UNSUPPORTED) {
            if (targetCandidate.isPresent()) {
                throw new IllegalArgumentException(
                        "unsupported mappings cannot contain a target candidate");
            }
            if (lossIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "unsupported mappings require an explicit loss item");
            }
        } else if (targetCandidate.isEmpty()) {
            throw new IllegalArgumentException("supported mappings require a target candidate");
        }
        if (grade == MappingGrade.EXACT
                && (!lossIds.isEmpty() || !unresolvedQuestionIds.isEmpty())) {
            throw new IllegalArgumentException(
                    "exact mappings cannot contain losses or unresolved questions");
        }
        if (grade == MappingGrade.EXACT
                && (!targetCandidate.orElseThrow().fullyValidated()
                        || capabilityDelta.blocksReadyStatus())) {
            throw new IllegalArgumentException(
                    "exact mappings require validated round-trip and safe capability delta");
        }
        if (grade == MappingGrade.ASSISTED && unresolvedQuestionIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "assisted mappings require an unresolved question");
        }
    }

    private static List<String> validatedIds(List<String> ids, String name) {
        List<String> copy = ConversionValidation.immutableList(ids, name);
        copy = copy.stream().map(value -> ConversionValidation.id(value, name + " entry")).toList();
        if (new HashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException(name + " must not contain duplicates");
        }
        return copy;
    }
}
