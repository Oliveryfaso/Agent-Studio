package dev.agentconfig.workbench.conversion;

import java.util.Objects;

/** Metadata for an in-memory candidate; candidate bytes are deliberately outside this schema. */
public record TargetCandidate(
        String logicalPath,
        CandidateRenderState renderState,
        String candidateSha256,
        long candidateByteSize,
        String rendererProfile,
        TargetConflictState conflictState,
        String existingTargetSha256,
        long existingTargetByteSize,
        ValidationStatus targetValidation,
        String targetValidatorProfile,
        String targetValidationSubjectSha256,
        ValidationStatus semanticRoundTrip,
        String semanticRoundTripProfile,
        String semanticRoundTripSubjectSha256,
        ValidationStatus threeWayReview,
        String threeWayReviewProfile,
        String threeWayReviewSubjectSha256) {
    public TargetCandidate {
        logicalPath = ConversionValidation.logicalPath(logicalPath);
        Objects.requireNonNull(renderState, "renderState");
        Objects.requireNonNull(candidateSha256, "candidateSha256");
        Objects.requireNonNull(rendererProfile, "rendererProfile");
        Objects.requireNonNull(conflictState, "conflictState");
        Objects.requireNonNull(existingTargetSha256, "existingTargetSha256");
        Objects.requireNonNull(targetValidation, "targetValidation");
        Objects.requireNonNull(targetValidatorProfile, "targetValidatorProfile");
        Objects.requireNonNull(targetValidationSubjectSha256, "targetValidationSubjectSha256");
        Objects.requireNonNull(semanticRoundTrip, "semanticRoundTrip");
        Objects.requireNonNull(semanticRoundTripProfile, "semanticRoundTripProfile");
        Objects.requireNonNull(semanticRoundTripSubjectSha256,
                "semanticRoundTripSubjectSha256");
        Objects.requireNonNull(threeWayReview, "threeWayReview");
        Objects.requireNonNull(threeWayReviewProfile, "threeWayReviewProfile");
        Objects.requireNonNull(threeWayReviewSubjectSha256, "threeWayReviewSubjectSha256");
        if (renderState == CandidateRenderState.RENDERED
                || renderState == CandidateRenderState.REUSED_SOURCE) {
            candidateSha256 = ConversionValidation.sha256(candidateSha256, "candidate hash");
            if (candidateByteSize < 0) {
                throw new IllegalArgumentException("rendered candidate requires a byte size");
            }
            rendererProfile = ConversionValidation.id(rendererProfile, "renderer profile");
        } else if (!candidateSha256.isEmpty() || candidateByteSize != -1) {
            throw new IllegalArgumentException(
                    "metadata-only candidate cannot claim rendered bytes or hash");
        } else if (!rendererProfile.isEmpty()) {
            throw new IllegalArgumentException("metadata-only candidate cannot claim a renderer");
        }
        boolean existing = conflictState == TargetConflictState.EXISTING_TARGET_PRESENT
                || conflictState == TargetConflictState.EXISTING_TARGET_IDENTICAL
                || conflictState == TargetConflictState.EXISTING_TARGET_CONFLICT;
        if (existing) {
            existingTargetSha256 = ConversionValidation.sha256(
                    existingTargetSha256, "existing target hash");
            if (existingTargetByteSize < 0) {
                throw new IllegalArgumentException("existing target requires a byte size");
            }
        } else if (!existingTargetSha256.isEmpty() || existingTargetByteSize != -1) {
            throw new IllegalArgumentException(
                    "existing target metadata requires an existing-target state");
        }
        if ((conflictState == TargetConflictState.EXISTING_TARGET_IDENTICAL
                || conflictState == TargetConflictState.EXISTING_TARGET_CONFLICT)
                && renderState != CandidateRenderState.RENDERED
                && renderState != CandidateRenderState.REUSED_SOURCE) {
            throw new IllegalArgumentException("hash comparison requires rendered candidate bytes");
        }
        if (conflictState == TargetConflictState.EXISTING_TARGET_IDENTICAL
                && !candidateSha256.equals(existingTargetSha256)) {
            throw new IllegalArgumentException("identical target state requires equal hashes");
        }
        if (conflictState == TargetConflictState.EXISTING_TARGET_CONFLICT
                && candidateSha256.equals(existingTargetSha256)) {
            throw new IllegalArgumentException("conflict target state requires different hashes");
        }
        boolean reviewRequired = existing
                || conflictState == TargetConflictState.INVALID_TARGET
                || conflictState == TargetConflictState.OUTSIDE_SCOPE
                || conflictState == TargetConflictState.TARGET_CHANGED_DURING_PROBE;
        if (reviewRequired && threeWayReview == ValidationStatus.NOT_APPLICABLE) {
            throw new IllegalArgumentException("existing or unsafe target requires explicit review state");
        }
        if (conflictState == TargetConflictState.NOT_EVALUATED
                && threeWayReview != ValidationStatus.NOT_RUN) {
            throw new IllegalArgumentException("unevaluated target requires a not-run review state");
        }
        if (conflictState == TargetConflictState.NO_EXISTING_TARGET
                && threeWayReview != ValidationStatus.NOT_APPLICABLE) {
            throw new IllegalArgumentException("absent target requires a not-applicable review state");
        }
        if ((conflictState == TargetConflictState.EXISTING_TARGET_PRESENT
                || conflictState == TargetConflictState.EXISTING_TARGET_CONFLICT
                || conflictState == TargetConflictState.INVALID_TARGET
                || conflictState == TargetConflictState.OUTSIDE_SCOPE
                || conflictState == TargetConflictState.TARGET_CHANGED_DURING_PROBE)
                && threeWayReview == ValidationStatus.PASSED) {
            throw new IllegalArgumentException(
                    "conflicting, unsafe, or stale targets cannot pass three-way review");
        }
        validateEvidence(targetValidation, targetValidatorProfile,
                targetValidationSubjectSha256, candidateSha256, "target validation");
        validateEvidence(semanticRoundTrip, semanticRoundTripProfile,
                semanticRoundTripSubjectSha256, candidateSha256, "semantic round-trip");
        validateEvidence(threeWayReview, threeWayReviewProfile,
                threeWayReviewSubjectSha256, candidateSha256, "three-way review");
        if (renderState == CandidateRenderState.METADATA_ONLY
                && (targetValidation == ValidationStatus.PASSED
                        || semanticRoundTrip == ValidationStatus.PASSED)) {
            throw new IllegalArgumentException(
                    "metadata-only candidates cannot claim content validation");
        }
        if (targetValidation != ValidationStatus.PASSED
                && semanticRoundTrip == ValidationStatus.PASSED) {
            throw new IllegalArgumentException(
                    "semantic round-trip cannot pass before target validation");
        }
    }

    public boolean fullyValidated() {
        return renderState == CandidateRenderState.RENDERED
                && (conflictState == TargetConflictState.NO_EXISTING_TARGET
                        || conflictState == TargetConflictState.EXISTING_TARGET_IDENTICAL)
                && targetValidation == ValidationStatus.PASSED
                && semanticRoundTrip == ValidationStatus.PASSED
                && (threeWayReview == ValidationStatus.PASSED
                        || threeWayReview == ValidationStatus.NOT_APPLICABLE);
    }

    private static void validateEvidence(
            ValidationStatus status, String profile, String subjectSha256,
            String candidateSha256, String label) {
        boolean ran = status == ValidationStatus.PASSED || status == ValidationStatus.FAILED;
        if (ran) {
            ConversionValidation.id(profile, label + " profile");
            ConversionValidation.sha256(subjectSha256, label + " subject hash");
            if (!subjectSha256.equals(candidateSha256)) {
                throw new IllegalArgumentException(label + " evidence must bind the candidate hash");
            }
        } else if (!profile.isEmpty() || !subjectSha256.isEmpty()) {
            throw new IllegalArgumentException(
                    label + " evidence requires a completed result");
        }
    }
}
