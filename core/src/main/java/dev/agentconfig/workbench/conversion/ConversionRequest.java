package dev.agentconfig.workbench.conversion;

import dev.agentconfig.workbench.ir.InstructionIr;
import dev.agentconfig.workbench.ir.IrResolutionStatus;
import dev.agentconfig.workbench.context.ProjectSemanticProfile;
import java.util.Objects;

/** Immutable request metadata. This contract authorizes preview planning only, never target writes. */
public record ConversionRequest(
        String id,
        String sourceIrId,
        String sourceIrSha256,
        int sourceIrSchemaVersion,
        IrResolutionStatus sourceResolutionStatus,
        String sourceSemanticProfile,
        String targetSemanticProfile,
        ConversionRecipeRef recipe,
        PartialIrPolicy partialIrPolicy) {
    public ConversionRequest {
        id = ConversionValidation.id(id, "request id");
        sourceIrId = ConversionValidation.id(sourceIrId, "source IR id");
        sourceIrSha256 = ConversionValidation.sha256(sourceIrSha256, "source IR hash");
        if (sourceIrSchemaVersion != InstructionIr.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported source IR schema version");
        }
        Objects.requireNonNull(sourceResolutionStatus, "sourceResolutionStatus");
        sourceSemanticProfile = ConversionValidation.id(
                sourceSemanticProfile, "source semantic profile");
        targetSemanticProfile = ConversionValidation.id(
                targetSemanticProfile, "target semantic profile");
        ProjectSemanticProfile sourceProfile = ProjectSemanticProfile.fromId(sourceSemanticProfile);
        ProjectSemanticProfile targetProfile = ProjectSemanticProfile.fromId(targetSemanticProfile);
        if (sourceProfile == targetProfile) {
            throw new IllegalArgumentException("source and target semantic profiles must differ");
        }
        Objects.requireNonNull(recipe, "recipe");
        Objects.requireNonNull(partialIrPolicy, "partialIrPolicy");
        if (sourceResolutionStatus == IrResolutionStatus.INVALID) {
            throw new IllegalArgumentException("invalid source IR cannot be converted");
        }
        if (sourceResolutionStatus == IrResolutionStatus.PARTIAL
                && partialIrPolicy != PartialIrPolicy.ALLOW_PARTIAL_PREVIEW) {
            throw new IllegalArgumentException(
                    "partial source IR is rejected unless preview-only override is explicit");
        }
        if (sourceResolutionStatus == IrResolutionStatus.COMPLETE
                && partialIrPolicy == PartialIrPolicy.ALLOW_PARTIAL_PREVIEW) {
            throw new IllegalArgumentException(
                    "partial preview override is only valid for a partial source IR");
        }
    }

    public String sourceHostId() {
        return ProjectSemanticProfile.fromId(sourceSemanticProfile).hostId();
    }

    public String targetHostId() {
        return ProjectSemanticProfile.fromId(targetSemanticProfile).hostId();
    }

    public static ConversionRequest strict(
            String id,
            String sourceIrId,
            String sourceIrSha256,
            int sourceIrSchemaVersion,
            IrResolutionStatus sourceResolutionStatus,
            String sourceSemanticProfile,
            String targetSemanticProfile,
            ConversionRecipeRef recipe) {
        return new ConversionRequest(
                id,
                sourceIrId,
                sourceIrSha256,
                sourceIrSchemaVersion,
                sourceResolutionStatus,
                sourceSemanticProfile,
                targetSemanticProfile,
                recipe,
                PartialIrPolicy.REJECT);
    }
}
