package dev.agentconfig.workbench;

import dev.agentconfig.workbench.conversion.ConversionPlan;
import dev.agentconfig.workbench.conversion.ConversionPlanStatus;
import dev.agentconfig.workbench.conversion.ConversionOperation;
import dev.agentconfig.workbench.conversion.CapabilityDelta;
import dev.agentconfig.workbench.conversion.CandidateRenderState;
import dev.agentconfig.workbench.conversion.ConversionRecipeRef;
import dev.agentconfig.workbench.conversion.ConversionRequest;
import dev.agentconfig.workbench.conversion.LossItem;
import dev.agentconfig.workbench.conversion.LossSeverity;
import dev.agentconfig.workbench.conversion.MappingGrade;
import dev.agentconfig.workbench.conversion.MappingItem;
import dev.agentconfig.workbench.conversion.PartialIrPolicy;
import dev.agentconfig.workbench.conversion.TargetCandidate;
import dev.agentconfig.workbench.conversion.TargetConflictState;
import dev.agentconfig.workbench.conversion.UnresolvedQuestion;
import dev.agentconfig.workbench.conversion.ValidationStatus;
import dev.agentconfig.workbench.ir.InstructionIr;
import dev.agentconfig.workbench.ir.IrNodeRef;
import dev.agentconfig.workbench.ir.IrResolutionStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ConversionPlanSchemaTests {
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final String RENDERER = "renderer-v1";
    private static final String TARGET_VALIDATOR = "target-validator-v1";
    private static final String ROUND_TRIP = "round-trip-v1";
    private static final String TARGET_REVIEW = "target-review-v1";
    private static final IrNodeRef SOURCE = IrNodeRef.source("source-root");

    private int passed;

    public static void main(String[] args) throws Exception {
        new ConversionPlanSchemaTests().runAll();
    }

    private void runAll() throws Exception {
        run("constructs a versioned metadata-only exact plan", this::constructsExactPlan);
        run("collections are defensively copied", this::collectionsAreDefensivelyCopied);
        run("stable identifiers and hashes are validated", this::stableFormatsAreValidated);
        run("partial IR is rejected by default", this::partialIrIsRejectedByDefault);
        run("partial IR requires explicit preview override", this::partialIrPreviewOverride);
        run("invalid IR is always rejected", this::invalidIrIsRejected);
        run("unsupported mapping cannot expose a candidate", this::unsupportedCannotExposeCandidate);
        run("unsupported mapping requires a blocking loss", this::unsupportedRequiresBlockingLoss);
        run("exact and assisted grade invariants are enforced", this::gradeInvariantsAreEnforced);
        run("plan references and orphan records are rejected", this::referencesAreValidated);
        run("plan status is derived from mapping support", this::statusIsValidated);
        run("target conflict metadata is internally consistent", this::targetConflictIsValidated);
        run("metadata-only candidates cannot claim validation", this::metadataOnlyCannotPass);
        run("validation evidence and ordering are enforced", this::validationEvidenceIsEnforced);
        run("unsafe targets cannot be fully validated", this::unsafeTargetsCannotValidate);
        System.out.printf("ConversionPlan schema v2 tests: %d passed%n", passed);
    }

    private void constructsExactPlan() {
        ConversionPlan plan = plan(
                completeRequest(),
                ConversionPlanStatus.PREVIEW_READY,
                List.of(exact("map-root")),
                List.of(),
                List.of());

        equal(2, plan.schemaVersion(), "schema version");
        equal("codex-project-semantics-v1", plan.request().sourceSemanticProfile(),
                "source profile");
        equal("claude-code-project-semantics-v1", plan.request().targetSemanticProfile(),
                "target profile");
        equal("portable-project-instruction", plan.request().recipe().id(), "recipe id");
        equal(1, plan.request().recipe().version(), "recipe version");
        equal(MappingGrade.EXACT, plan.mappings().getFirst().grade(), "grade");
        check(plan.mappings().getFirst().targetCandidate().isPresent(), "candidate missing");
        equal(List.of("logicalPath", "renderState", "candidateSha256", "candidateByteSize",
                        "rendererProfile", "conflictState", "existingTargetSha256",
                        "existingTargetByteSize", "targetValidation", "targetValidatorProfile",
                        "targetValidationSubjectSha256", "semanticRoundTrip",
                        "semanticRoundTripProfile", "semanticRoundTripSubjectSha256",
                        "threeWayReview", "threeWayReviewProfile",
                        "threeWayReviewSubjectSha256"),
                List.of(TargetCandidate.class.getRecordComponents()).stream()
                        .map(component -> component.getName()).toList(),
                "candidate metadata fields");
        check(!plan.writesPerformed(), "preview must not claim writes");
        check(!plan.applyEligible(), "preview must not be apply eligible");
    }

    private void collectionsAreDefensivelyCopied() {
        List<IrNodeRef> provenance = new ArrayList<>(List.of(SOURCE));
        MappingItem mapping = new MappingItem(
                "map-root", MappingGrade.EXACT, provenance, Optional.of(candidate()),
                CapabilityDelta.unchanged(), List.of(), List.of());
        provenance.clear();
        equal(1, mapping.sourceProvenance().size(), "mapping provenance copy");
        expectThrows(UnsupportedOperationException.class,
                () -> mapping.sourceProvenance().clear());

        List<MappingItem> mappings = new ArrayList<>(List.of(mapping));
        ConversionPlan plan = plan(
                completeRequest(), ConversionPlanStatus.PREVIEW_READY,
                mappings, List.of(), List.of());
        mappings.clear();
        equal(1, plan.mappings().size(), "plan mapping copy");
        expectThrows(UnsupportedOperationException.class, () -> plan.mappings().clear());

        List<IrNodeRef> lossProvenance = new ArrayList<>(List.of(SOURCE));
        LossItem loss = new LossItem(
                "loss-one", "SCOPE_DIFFERENCE", LossSeverity.REVIEW_REQUIRED,
                "Target activation differs", lossProvenance);
        lossProvenance.clear();
        equal(1, loss.provenance().size(), "loss provenance copy");
    }

    private void stableFormatsAreValidated() {
        expectThrows(IllegalArgumentException.class,
                () -> new ConversionRecipeRef("bad recipe", 1));
        expectThrows(IllegalArgumentException.class,
                () -> new ConversionRecipeRef("recipe", 0));
        expectThrows(IllegalArgumentException.class,
                () -> new ConversionRequest(
                        "request", "ir-source", "A".repeat(64), InstructionIr.CURRENT_SCHEMA_VERSION,
                        IrResolutionStatus.COMPLETE, "codex-project-semantics-v1",
                        "claude-code-project-semantics-v1", recipe(), PartialIrPolicy.REJECT));
        expectThrows(IllegalArgumentException.class,
                () -> new TargetCandidate(
                        "../CLAUDE.md", CandidateRenderState.RENDERED, HASH_A, 10, RENDERER,
                        TargetConflictState.NO_EXISTING_TARGET, "", -1,
                        ValidationStatus.PASSED, TARGET_VALIDATOR, HASH_A,
                        ValidationStatus.PASSED, ROUND_TRIP, HASH_A,
                        ValidationStatus.NOT_APPLICABLE, "", ""));
    }

    private void partialIrIsRejectedByDefault() {
        expectThrows(IllegalArgumentException.class,
                () -> ConversionRequest.strict(
                        "request", "ir-source", HASH_A, InstructionIr.CURRENT_SCHEMA_VERSION,
                        IrResolutionStatus.PARTIAL, "codex-project-semantics-v1",
                        "claude-code-project-semantics-v1", recipe()));
    }

    private void partialIrPreviewOverride() {
        ConversionRequest request = new ConversionRequest(
                "request", "ir-source", HASH_A, InstructionIr.CURRENT_SCHEMA_VERSION,
                IrResolutionStatus.PARTIAL, "codex-project-semantics-v1",
                "claude-code-project-semantics-v1", recipe(),
                PartialIrPolicy.ALLOW_PARTIAL_PREVIEW);
        ConversionPlan plan = plan(
                request, ConversionPlanStatus.REVIEW_REQUIRED,
                List.of(exact("map-root")), List.of(), List.of());

        equal(PartialIrPolicy.ALLOW_PARTIAL_PREVIEW,
                plan.request().partialIrPolicy(), "partial policy");
        equal(ConversionPlanStatus.REVIEW_REQUIRED, plan.status(), "degraded plan status");
        expectThrows(IllegalArgumentException.class,
                () -> new ConversionRequest(
                        "request", "ir-source", HASH_A, InstructionIr.CURRENT_SCHEMA_VERSION,
                        IrResolutionStatus.COMPLETE, "codex-project-semantics-v1",
                        "claude-code-project-semantics-v1", recipe(),
                        PartialIrPolicy.ALLOW_PARTIAL_PREVIEW));
    }

    private void invalidIrIsRejected() {
        expectThrows(IllegalArgumentException.class,
                () -> new ConversionRequest(
                        "request", "ir-source", HASH_A, InstructionIr.CURRENT_SCHEMA_VERSION,
                        IrResolutionStatus.INVALID, "codex-project-semantics-v1",
                        "claude-code-project-semantics-v1", recipe(), PartialIrPolicy.REJECT));
    }

    private void unsupportedCannotExposeCandidate() {
        expectThrows(IllegalArgumentException.class,
                () -> new MappingItem(
                        "map-policy", MappingGrade.UNSUPPORTED, List.of(SOURCE),
                        Optional.of(candidate()), CapabilityDelta.unchanged(),
                        List.of("loss-policy"), List.of()));
        expectThrows(IllegalArgumentException.class,
                () -> new MappingItem(
                        "map-policy", MappingGrade.UNSUPPORTED, List.of(SOURCE),
                        Optional.empty(), CapabilityDelta.unchanged(), List.of(), List.of()));
    }

    private void unsupportedRequiresBlockingLoss() {
        MappingItem unsupported = unsupported("map-policy", "loss-policy");
        LossItem reviewOnly = new LossItem(
                "loss-policy", "POLICY_UNSUPPORTED", LossSeverity.REVIEW_REQUIRED,
                "Command policy has no instruction equivalent", List.of(SOURCE));
        expectThrows(IllegalArgumentException.class,
                () -> plan(
                        completeRequest(), ConversionPlanStatus.BLOCKED,
                        List.of(unsupported), List.of(reviewOnly), List.of()));

        LossItem blocking = blockingLoss("loss-policy");
        ConversionPlan plan = plan(
                completeRequest(), ConversionPlanStatus.BLOCKED,
                List.of(unsupported), List.of(blocking), List.of());
        equal(ConversionPlanStatus.BLOCKED, plan.status(), "blocked status");
    }

    private void gradeInvariantsAreEnforced() {
        expectThrows(IllegalArgumentException.class,
                () -> new MappingItem(
                        "map-exact", MappingGrade.EXACT, List.of(SOURCE), Optional.of(candidate()),
                        CapabilityDelta.unchanged(), List.of("loss-one"), List.of()));
        expectThrows(IllegalArgumentException.class,
                () -> new MappingItem(
                        "map-assisted", MappingGrade.ASSISTED, List.of(SOURCE),
                        Optional.of(candidate()), CapabilityDelta.unchanged(), List.of(), List.of()));

        UnresolvedQuestion question = question("question-path");
        MappingItem assisted = new MappingItem(
                "map-assisted", MappingGrade.ASSISTED, List.of(SOURCE), Optional.of(candidate()),
                CapabilityDelta.unchanged(), List.of(), List.of(question.id()));
        ConversionPlan plan = plan(
                completeRequest(), ConversionPlanStatus.REVIEW_REQUIRED,
                List.of(assisted), List.of(), List.of(question));
        equal(ConversionPlanStatus.REVIEW_REQUIRED, plan.status(), "assisted status");
    }

    private void referencesAreValidated() {
        MappingItem unknownLoss = new MappingItem(
                "map-compatible", MappingGrade.COMPATIBLE, List.of(SOURCE),
                Optional.of(candidate()), CapabilityDelta.unchanged(),
                List.of("missing-loss"), List.of());
        expectThrows(IllegalArgumentException.class,
                () -> plan(
                        completeRequest(), ConversionPlanStatus.REVIEW_REQUIRED,
                        List.of(unknownLoss), List.of(), List.of()));

        LossItem orphan = new LossItem(
                "loss-orphan", "UNUSED_LOSS", LossSeverity.INFORMATIONAL,
                "This loss is not attached", List.of(SOURCE));
        expectThrows(IllegalArgumentException.class,
                () -> plan(
                        completeRequest(), ConversionPlanStatus.REVIEW_REQUIRED,
                        List.of(exact("map-root")), List.of(orphan), List.of()));

        UnresolvedQuestion question = question("question-path");
        expectThrows(IllegalArgumentException.class,
                () -> plan(
                        completeRequest(), ConversionPlanStatus.REVIEW_REQUIRED,
                        List.of(exact("map-root")), List.of(), List.of(question)));
    }

    private void statusIsValidated() {
        expectThrows(IllegalArgumentException.class,
                () -> plan(
                        completeRequest(), ConversionPlanStatus.REVIEW_REQUIRED,
                        List.of(exact("map-root")), List.of(), List.of()));

        LossItem blocking = blockingLoss("loss-policy");
        ConversionPlan partial = plan(
                completeRequest(), ConversionPlanStatus.PARTIALLY_SUPPORTED,
                List.of(exact("map-root"), unsupported("map-policy", blocking.id())),
                List.of(blocking), List.of());
        equal(ConversionPlanStatus.PARTIALLY_SUPPORTED, partial.status(), "partial support status");

        TargetCandidate conflict = new TargetCandidate(
                "CLAUDE.md", CandidateRenderState.RENDERED, HASH_A, 12, RENDERER,
                TargetConflictState.EXISTING_TARGET_CONFLICT, HASH_B, 9,
                ValidationStatus.PASSED, TARGET_VALIDATOR, HASH_A,
                ValidationStatus.PASSED, ROUND_TRIP, HASH_A,
                ValidationStatus.FAILED, TARGET_REVIEW, HASH_A);
        MappingItem mapping = new MappingItem(
                "map-conflict", MappingGrade.COMPATIBLE, List.of(SOURCE), Optional.of(conflict),
                CapabilityDelta.unchanged(), List.of(), List.of());
        ConversionPlan review = plan(
                completeRequest(), ConversionPlanStatus.REVIEW_REQUIRED,
                List.of(mapping), List.of(), List.of());
        equal(ConversionPlanStatus.REVIEW_REQUIRED, review.status(), "conflict status");
    }

    private void targetConflictIsValidated() {
        expectThrows(IllegalArgumentException.class,
                () -> new TargetCandidate(
                        "CLAUDE.md", CandidateRenderState.RENDERED, HASH_A, 12, RENDERER,
                        TargetConflictState.EXISTING_TARGET_IDENTICAL, HASH_B, 12,
                        ValidationStatus.PASSED, TARGET_VALIDATOR, HASH_A,
                        ValidationStatus.PASSED, ROUND_TRIP, HASH_A,
                        ValidationStatus.PASSED, TARGET_REVIEW, HASH_A));
        expectThrows(IllegalArgumentException.class,
                () -> new TargetCandidate(
                        "CLAUDE.md", CandidateRenderState.RENDERED, HASH_A, 12, RENDERER,
                        TargetConflictState.EXISTING_TARGET_CONFLICT, HASH_A, 12,
                        ValidationStatus.PASSED, TARGET_VALIDATOR, HASH_A,
                        ValidationStatus.PASSED, ROUND_TRIP, HASH_A,
                        ValidationStatus.FAILED, TARGET_REVIEW, HASH_A));
        expectThrows(IllegalArgumentException.class,
                () -> new TargetCandidate(
                        "CLAUDE.md", CandidateRenderState.RENDERED, HASH_A, 12, RENDERER,
                        TargetConflictState.NO_EXISTING_TARGET, HASH_B, 12,
                        ValidationStatus.PASSED, TARGET_VALIDATOR, HASH_A,
                        ValidationStatus.PASSED, ROUND_TRIP, HASH_A,
                        ValidationStatus.NOT_APPLICABLE, "", ""));

        TargetCandidate identical = new TargetCandidate(
                "CLAUDE.md", CandidateRenderState.RENDERED, HASH_A, 12, RENDERER,
                TargetConflictState.EXISTING_TARGET_IDENTICAL, HASH_A, 12,
                ValidationStatus.PASSED, TARGET_VALIDATOR, HASH_A,
                ValidationStatus.PASSED, ROUND_TRIP, HASH_A,
                ValidationStatus.PASSED, TARGET_REVIEW, HASH_A);
        equal(TargetConflictState.EXISTING_TARGET_IDENTICAL,
                identical.conflictState(), "identical state");
    }

    private void metadataOnlyCannotPass() {
        expectThrows(IllegalArgumentException.class,
                () -> new TargetCandidate(
                        "AGENTS.md", CandidateRenderState.METADATA_ONLY, "", -1, "",
                        TargetConflictState.NO_EXISTING_TARGET, "", -1,
                        ValidationStatus.PASSED, TARGET_VALIDATOR, HASH_A,
                        ValidationStatus.PASSED, ROUND_TRIP, HASH_A,
                        ValidationStatus.NOT_APPLICABLE, "", ""));
    }

    private void validationEvidenceIsEnforced() {
        expectThrows(IllegalArgumentException.class,
                () -> new TargetCandidate(
                        "CLAUDE.md", CandidateRenderState.RENDERED, HASH_A, 12, RENDERER,
                        TargetConflictState.NO_EXISTING_TARGET, "", -1,
                        ValidationStatus.PASSED, "", HASH_A,
                        ValidationStatus.PASSED, ROUND_TRIP, HASH_A,
                        ValidationStatus.NOT_APPLICABLE, "", ""));
        expectThrows(IllegalArgumentException.class,
                () -> new TargetCandidate(
                        "CLAUDE.md", CandidateRenderState.RENDERED, HASH_A, 12, RENDERER,
                        TargetConflictState.NO_EXISTING_TARGET, "", -1,
                        ValidationStatus.FAILED, TARGET_VALIDATOR, HASH_A,
                        ValidationStatus.PASSED, ROUND_TRIP, HASH_A,
                        ValidationStatus.NOT_APPLICABLE, "", ""));
        expectThrows(IllegalArgumentException.class,
                () -> new TargetCandidate(
                        "CLAUDE.md", CandidateRenderState.RENDERED, HASH_A, 12, RENDERER,
                        TargetConflictState.NO_EXISTING_TARGET, "", -1,
                        ValidationStatus.PASSED, TARGET_VALIDATOR, HASH_B,
                        ValidationStatus.PASSED, ROUND_TRIP, HASH_A,
                        ValidationStatus.NOT_APPLICABLE, "", ""));
    }

    private void unsafeTargetsCannotValidate() {
        TargetCandidate outside = new TargetCandidate(
                "CLAUDE.md", CandidateRenderState.RENDERED, HASH_A, 12, RENDERER,
                TargetConflictState.OUTSIDE_SCOPE, "", -1,
                ValidationStatus.PASSED, TARGET_VALIDATOR, HASH_A,
                ValidationStatus.PASSED, ROUND_TRIP, HASH_A,
                ValidationStatus.FAILED, TARGET_REVIEW, HASH_A);
        check(!outside.fullyValidated(), "outside target looked fully validated");
        expectThrows(IllegalArgumentException.class,
                () -> new MappingItem(
                        "map-outside", MappingGrade.EXACT, List.of(SOURCE),
                        Optional.of(outside), CapabilityDelta.unchanged(), List.of(), List.of()));
        expectThrows(IllegalArgumentException.class,
                () -> new TargetCandidate(
                        "CLAUDE.md", CandidateRenderState.RENDERED, HASH_A, 12, RENDERER,
                        TargetConflictState.OUTSIDE_SCOPE, "", -1,
                        ValidationStatus.PASSED, TARGET_VALIDATOR, HASH_A,
                        ValidationStatus.PASSED, ROUND_TRIP, HASH_A,
                        ValidationStatus.PASSED, TARGET_REVIEW, HASH_A));
    }

    private static ConversionPlan plan(
            ConversionRequest request,
            ConversionPlanStatus status,
            List<MappingItem> mappings,
            List<LossItem> losses,
            List<UnresolvedQuestion> questions) {
        return new ConversionPlan(
                ConversionPlan.CURRENT_SCHEMA_VERSION,
                "plan-demo",
                ConversionOperation.CONVERSION_PREVIEW,
                false,
                false,
                request,
                status,
                mappings,
                losses,
                questions);
    }

    private static ConversionRequest completeRequest() {
        return ConversionRequest.strict(
                "request-demo",
                "ir-source",
                HASH_A,
                InstructionIr.CURRENT_SCHEMA_VERSION,
                IrResolutionStatus.COMPLETE,
                "codex-project-semantics-v1",
                "claude-code-project-semantics-v1",
                recipe());
    }

    private static ConversionRecipeRef recipe() {
        return new ConversionRecipeRef("portable-project-instruction", 1);
    }

    private static MappingItem exact(String id) {
        return new MappingItem(
                id, MappingGrade.EXACT, List.of(SOURCE), Optional.of(candidate()),
                CapabilityDelta.unchanged(), List.of(), List.of());
    }

    private static MappingItem unsupported(String id, String lossId) {
        return new MappingItem(
                id, MappingGrade.UNSUPPORTED, List.of(SOURCE), Optional.empty(),
                CapabilityDelta.unchanged(), List.of(lossId), List.of());
    }

    private static TargetCandidate candidate() {
        return new TargetCandidate(
                "CLAUDE.md", CandidateRenderState.RENDERED, HASH_A, 12, RENDERER,
                TargetConflictState.NO_EXISTING_TARGET, "", -1,
                ValidationStatus.PASSED, TARGET_VALIDATOR, HASH_A,
                ValidationStatus.PASSED, ROUND_TRIP, HASH_A,
                ValidationStatus.NOT_APPLICABLE, "", "");
    }

    private static LossItem blockingLoss(String id) {
        return new LossItem(
                id, "POLICY_UNSUPPORTED", LossSeverity.BLOCKING,
                "Command policy cannot become a natural-language instruction", List.of(SOURCE));
    }

    private static UnresolvedQuestion question(String id) {
        return new UnresolvedQuestion(
                id, "TARGET_SCOPE_REQUIRED", "Choose the target activation scope", List.of(SOURCE));
    }

    private void run(String name, ThrowingRunnable test) throws Exception {
        test.run();
        passed++;
        System.out.println("PASS " + name);
    }

    private static void expectThrows(Class<? extends Throwable> type, ThrowingRunnable action) {
        try {
            action.run();
            throw new AssertionError("Expected " + type.getSimpleName());
        } catch (Throwable failure) {
            if (!type.isInstance(failure)) {
                throw new AssertionError(
                        "Expected " + type.getSimpleName() + ", got " + failure, failure);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
