package dev.agentconfig.workbench.conversion;

import dev.agentconfig.workbench.ir.IrResolutionStatus;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Versioned, metadata-only conversion preview. It contains no candidate bytes and grants no write
 * authority.
 */
public record ConversionPlan(
        int schemaVersion,
        String id,
        ConversionOperation operation,
        boolean writesPerformed,
        boolean applyEligible,
        ConversionRequest request,
        ConversionPlanStatus status,
        List<MappingItem> mappings,
        List<LossItem> losses,
        List<UnresolvedQuestion> unresolvedQuestions) {
    public static final int CURRENT_SCHEMA_VERSION = 2;

    public ConversionPlan {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported ConversionPlan schema version: " + schemaVersion);
        }
        id = ConversionValidation.id(id, "plan id");
        if (operation != ConversionOperation.CONVERSION_PREVIEW) {
            throw new IllegalArgumentException("only conversion preview plans are supported");
        }
        if (writesPerformed || applyEligible) {
            throw new IllegalArgumentException("ConversionPlan v1 never writes and is never apply-eligible");
        }
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(status, "status");
        mappings = ConversionValidation.immutableList(mappings, "mappings");
        losses = ConversionValidation.immutableList(losses, "losses");
        unresolvedQuestions = ConversionValidation.immutableList(
                unresolvedQuestions, "unresolvedQuestions");

        Map<String, LossItem> lossesById = uniqueById(
                losses, LossItem::id, "loss");
        Map<String, UnresolvedQuestion> questionsById = uniqueById(
                unresolvedQuestions, UnresolvedQuestion::id, "question");
        Set<String> mappingIds = new HashSet<>();
        Set<String> usedLossIds = new HashSet<>();
        Set<String> usedQuestionIds = new HashSet<>();

        for (MappingItem mapping : mappings) {
            if (!mappingIds.add(mapping.id())) {
                throw new IllegalArgumentException("duplicate mapping id: " + mapping.id());
            }
            for (String lossId : mapping.lossIds()) {
                LossItem loss = lossesById.get(lossId);
                if (loss == null) {
                    throw new IllegalArgumentException(
                            "mapping references unknown loss: " + lossId);
                }
                if (mapping.grade() != MappingGrade.UNSUPPORTED
                        && loss.severity() == LossSeverity.BLOCKING) {
                    throw new IllegalArgumentException(
                            "a mapping with a blocking loss cannot expose a candidate");
                }
                usedLossIds.add(lossId);
            }
            for (String questionId : mapping.unresolvedQuestionIds()) {
                if (!questionsById.containsKey(questionId)) {
                    throw new IllegalArgumentException(
                            "mapping references unknown question: " + questionId);
                }
                usedQuestionIds.add(questionId);
            }
            if (mapping.grade() == MappingGrade.UNSUPPORTED
                    && mapping.lossIds().stream().map(lossesById::get)
                            .noneMatch(loss -> loss.severity() == LossSeverity.BLOCKING)) {
                throw new IllegalArgumentException(
                        "unsupported mappings require at least one blocking loss");
            }
        }
        if (!usedLossIds.equals(lossesById.keySet())) {
            throw new IllegalArgumentException("conversion plan contains an unreferenced loss");
        }
        if (!usedQuestionIds.equals(questionsById.keySet())) {
            throw new IllegalArgumentException("conversion plan contains an unreferenced question");
        }

        ConversionPlanStatus expected = expectedStatus(request, mappings, losses, unresolvedQuestions);
        if (status != expected) {
            throw new IllegalArgumentException(
                    "conversion plan status must be " + expected + " for its contents");
        }
    }

    private static ConversionPlanStatus expectedStatus(
            ConversionRequest request,
            List<MappingItem> mappings,
            List<LossItem> losses,
            List<UnresolvedQuestion> questions) {
        if (mappings.isEmpty()) {
            if (!losses.isEmpty() || !questions.isEmpty()) {
                throw new IllegalArgumentException("empty plans cannot contain loss or question records");
            }
            return ConversionPlanStatus.EMPTY;
        }
        long supported = mappings.stream()
                .filter(mapping -> mapping.grade() != MappingGrade.UNSUPPORTED).count();
        if (supported == 0) {
            return ConversionPlanStatus.BLOCKED;
        }
        if (supported != mappings.size()) {
            return ConversionPlanStatus.PARTIALLY_SUPPORTED;
        }
        boolean targetReview = mappings.stream().map(MappingItem::targetCandidate)
                .flatMap(java.util.Optional::stream)
                .anyMatch(candidate -> !candidate.fullyValidated()
                        || candidate.conflictState() == TargetConflictState.NOT_EVALUATED
                        || candidate.conflictState() == TargetConflictState.EXISTING_TARGET_PRESENT
                        || candidate.conflictState() == TargetConflictState.EXISTING_TARGET_CONFLICT
                        || candidate.conflictState() == TargetConflictState.INVALID_TARGET
                        || candidate.conflictState() == TargetConflictState.OUTSIDE_SCOPE);
        boolean assisted = mappings.stream()
                .anyMatch(mapping -> mapping.grade() == MappingGrade.ASSISTED);
        boolean capabilityReview = mappings.stream()
                .anyMatch(mapping -> mapping.capabilityDelta().blocksReadyStatus());
        if (request.sourceResolutionStatus() == IrResolutionStatus.PARTIAL
                || !losses.isEmpty() || !questions.isEmpty() || targetReview || assisted
                || capabilityReview) {
            return ConversionPlanStatus.REVIEW_REQUIRED;
        }
        return ConversionPlanStatus.PREVIEW_READY;
    }

    private static <T> Map<String, T> uniqueById(
            List<T> values,
            java.util.function.Function<T, String> id,
            String type) {
        Map<String, T> result = new HashMap<>();
        for (T value : values) {
            String key = id.apply(value);
            if (result.put(key, value) != null) {
                throw new IllegalArgumentException("duplicate " + type + " id: " + key);
            }
        }
        return result;
    }
}
