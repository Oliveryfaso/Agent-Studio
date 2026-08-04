package dev.agentconfig.workbench.conversion;

import dev.agentconfig.workbench.ir.InstructionSource;
import dev.agentconfig.workbench.ir.InstructionSourceKind;
import dev.agentconfig.workbench.ir.InstructionSourceState;
import dev.agentconfig.workbench.ir.ScopeKind;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Recipe-specific validation over ephemeral bytes and metadata-only target inventory. */
public final class CandidateValidationPipeline {
    public static final String CLAUDE_WRAPPER_STRUCTURE_PROFILE =
            "claude-project-import-wrapper-validator-v1";
    public static final String ROOT_WRAPPER_ROUND_TRIP_PROFILE =
            "codex-root-claude-import-roundtrip-v1";
    public static final String METADATA_TARGET_REVIEW_PROFILE =
            "metadata-target-review-v1";

    public TargetCandidate validateCodexRootClaudeWrapper(
            InstructionSource source,
            RenderedCandidate candidate,
            TargetInventoryEntry inventory) {
        ValidationStatus targetValidation = validatesCanonicalWrapper(source, candidate)
                ? ValidationStatus.PASSED : ValidationStatus.FAILED;
        ValidationStatus roundTrip = targetValidation == ValidationStatus.PASSED
                && eligibleFullRoot(source)
                ? ValidationStatus.PASSED : ValidationStatus.NOT_RUN;
        TargetConflictState conflict = conflict(candidate, inventory);
        ValidationStatus targetReview = targetReview(conflict);
        return new TargetCandidate(
                candidate.logicalPath(), CandidateRenderState.RENDERED,
                candidate.sha256(), candidate.byteSize(), candidate.rendererProfile(),
                conflict,
                inventory.state() == TargetInventoryState.PRESENT ? inventory.sha256() : "",
                inventory.state() == TargetInventoryState.PRESENT ? inventory.byteSize() : -1,
                targetValidation, CLAUDE_WRAPPER_STRUCTURE_PROFILE, candidate.sha256(),
                roundTrip,
                roundTrip == ValidationStatus.PASSED ? ROOT_WRAPPER_ROUND_TRIP_PROFILE : "",
                roundTrip == ValidationStatus.PASSED ? candidate.sha256() : "",
                targetReview,
                targetReview == ValidationStatus.PASSED || targetReview == ValidationStatus.FAILED
                        ? METADATA_TARGET_REVIEW_PROFILE : "",
                targetReview == ValidationStatus.PASSED || targetReview == ValidationStatus.FAILED
                        ? candidate.sha256() : "");
    }

    static boolean eligibleFullRoot(InstructionSource source) {
        return source.kind() == InstructionSourceKind.PROJECT_GUIDANCE
                && source.logicalPath().equals("AGENTS.md")
                && source.scope().kind() == ScopeKind.PROJECT
                && source.state() == InstructionSourceState.ACTIVE
                && source.includedBytes() == source.byteSize()
                && !source.effectiveSha256().isEmpty()
                && source.effectiveSha256().equals(source.revisionSha256());
    }

    private static boolean validatesCanonicalWrapper(
            InstructionSource source, RenderedCandidate candidate) {
        if (!candidate.logicalPath().equals("CLAUDE.md") || !eligibleFullRoot(source)) {
            return false;
        }
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(candidate.bytes())).toString();
            return decoded.equals("@AGENTS.md\n");
        } catch (CharacterCodingException exception) {
            return false;
        }
    }

    private static TargetConflictState conflict(
            RenderedCandidate candidate, TargetInventoryEntry inventory) {
        return switch (inventory.state()) {
            case NOT_EVALUATED -> TargetConflictState.NOT_EVALUATED;
            case ABSENT -> TargetConflictState.NO_EXISTING_TARGET;
            case INVALID -> TargetConflictState.INVALID_TARGET;
            case OUTSIDE_SCOPE -> TargetConflictState.OUTSIDE_SCOPE;
            case CHANGED_DURING_PROBE -> TargetConflictState.TARGET_CHANGED_DURING_PROBE;
            case PRESENT -> candidate.sha256().equals(inventory.sha256())
                    ? TargetConflictState.EXISTING_TARGET_IDENTICAL
                    : TargetConflictState.EXISTING_TARGET_CONFLICT;
        };
    }

    private static ValidationStatus targetReview(TargetConflictState conflict) {
        return switch (conflict) {
            case NO_EXISTING_TARGET -> ValidationStatus.NOT_APPLICABLE;
            case EXISTING_TARGET_IDENTICAL -> ValidationStatus.PASSED;
            case EXISTING_TARGET_CONFLICT, EXISTING_TARGET_PRESENT, INVALID_TARGET,
                    OUTSIDE_SCOPE, TARGET_CHANGED_DURING_PROBE -> ValidationStatus.FAILED;
            case NOT_EVALUATED -> ValidationStatus.NOT_RUN;
        };
    }
}
