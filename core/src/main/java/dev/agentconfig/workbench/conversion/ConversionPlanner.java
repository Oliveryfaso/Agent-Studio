package dev.agentconfig.workbench.conversion;

import dev.agentconfig.workbench.context.ProjectSemanticProfile;
import dev.agentconfig.workbench.ir.InstructionIr;
import dev.agentconfig.workbench.ir.InstructionSource;
import dev.agentconfig.workbench.ir.InstructionSourceKind;
import dev.agentconfig.workbench.ir.InstructionSourceState;
import dev.agentconfig.workbench.ir.IrNodeRef;
import dev.agentconfig.workbench.ir.IrResolutionStatus;
import dev.agentconfig.workbench.ir.ScopeKind;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Pure, metadata-only planner for the first Codex/Claude instruction preview recipes. */
public final class ConversionPlanner {
    public static final String CODEX_TO_CLAUDE_RECIPE =
            "codex-project-v1-to-claude-project-v1";
    public static final String CLAUDE_TO_CODEX_RECIPE =
            "claude-project-v1-to-codex-project-v1";
    public static final int RECIPE_VERSION = 2;
    private static final String SOURCE_REUSE_PROFILE = "verified-source-reuse-v1";

    public ConversionPlan plan(
            InstructionIr source,
            String sourceSemanticProfile,
            String targetSemanticProfile,
            TargetInventory targetInventory) {
        if (source.resolutionStatus() != IrResolutionStatus.COMPLETE) {
            throw new IllegalArgumentException("conversion preview requires a COMPLETE source IR");
        }
        ProjectSemanticProfile sourceProfile = ProjectSemanticProfile.fromId(sourceSemanticProfile);
        ProjectSemanticProfile targetProfile = ProjectSemanticProfile.fromId(targetSemanticProfile);
        if (sourceProfile == targetProfile) {
            throw new IllegalArgumentException("source and target profiles must differ");
        }
        if (source.sources().stream().anyMatch(
                value -> !value.identity().hostId().equals(sourceProfile.hostId()))) {
            throw new IllegalArgumentException("source IR host does not match semantic profile");
        }
        String recipeId = recipeId(sourceProfile, targetProfile);
        String irHash = fingerprint(source);
        ConversionRecipeRef recipe = new ConversionRecipeRef(recipeId, RECIPE_VERSION);
        String requestId = stableId("request", recipeId, Integer.toString(RECIPE_VERSION),
                source.id(), irHash, sourceSemanticProfile, targetSemanticProfile);
        ConversionRequest request = ConversionRequest.strict(
                requestId, source.id(), irHash, source.schemaVersion(), source.resolutionStatus(),
                sourceSemanticProfile, targetSemanticProfile, recipe);

        List<InstructionSource> active = source.sources().stream()
                .filter(value -> value.state().participatesInLoadOrder())
                .sorted(Comparator.comparingInt(InstructionSource::loadOrder)
                        .thenComparing(InstructionSource::logicalPath)
                        .thenComparing(value -> value.identity().sourceId()))
                .toList();
        if (active.isEmpty()) {
            return new ConversionPlan(
                    ConversionPlan.CURRENT_SCHEMA_VERSION,
                    stableId("plan", requestId, "empty"),
                    ConversionOperation.CONVERSION_PREVIEW, false, false,
                    request, ConversionPlanStatus.EMPTY, List.of(), List.of(), List.of());
        }

        PlanParts parts = sourceProfile == ProjectSemanticProfile.CODEX_PROJECT_V1
                ? codexToClaude(active, targetInventory)
                : claudeToCodex(active, targetInventory);
        String planId = stableId("plan", requestId,
                parts.mappings().stream().map(MappingItem::id).sorted().reduce("", (a, b) -> a + "\n" + b),
                parts.losses().stream().map(LossItem::id).sorted().reduce("", (a, b) -> a + "\n" + b),
                parts.questions().stream().map(UnresolvedQuestion::id).sorted()
                        .reduce("", (a, b) -> a + "\n" + b));
        ConversionPlanStatus status = derivedStatus(
                request, parts.mappings(), parts.losses(), parts.questions());
        return new ConversionPlan(
                ConversionPlan.CURRENT_SCHEMA_VERSION, planId,
                ConversionOperation.CONVERSION_PREVIEW, false, false,
                request, status, parts.mappings(), parts.losses(), parts.questions());
    }

    private static PlanParts codexToClaude(
            List<InstructionSource> active, TargetInventory inventory) {
        List<InstructionSource> supported = active.stream()
                .filter(ConversionPlanner::projectInstructionKind).toList();
        List<InstructionSource> unsupported = active.stream()
                .filter(value -> !projectInstructionKind(value)).toList();
        PlanBuilder builder = new PlanBuilder();
        if (!supported.isEmpty()) {
            boolean simpleRoot = supported.size() == 1
                    && CandidateValidationPipeline.eligibleFullRoot(supported.getFirst());
            if (simpleRoot) {
                InstructionSource root = supported.getFirst();
                RenderedCandidate rendered = new BoundedCandidateRenderer()
                        .renderClaudeProjectImportWrapper(root.logicalPath());
                TargetCandidate candidate = new CandidateValidationPipeline()
                        .validateCodexRootClaudeWrapper(
                                root, rendered, inventory.entry(rendered.logicalPath()));
                builder.mapping(MappingGrade.COMPATIBLE, supported, candidate,
                        CapabilityDelta.instructionOnlyUnknown(), List.of(), List.of());
            } else {
                TargetCandidate candidate = candidate(
                        "CLAUDE.md", CandidateRenderState.METADATA_ONLY, "", -1,
                        "",
                        inventory.entry("CLAUDE.md"), ValidationStatus.NOT_RUN,
                        ValidationStatus.NOT_RUN);
                List<String> lossCodes = new ArrayList<>(List.of(
                        "SOURCE_BODY_NOT_IN_IR", "CODEX_STRUCTURE_REVIEW_REQUIRED"));
                if (supported.stream().anyMatch(
                        value -> value.state() == InstructionSourceState.ACTIVE_TRUNCATED)) {
                    lossCodes.add("TRUNCATED_EFFECTIVE_SOURCE");
                }
                builder.mapping(MappingGrade.ASSISTED, supported, candidate,
                        CapabilityDelta.instructionOnlyUnknown(), lossCodes,
                        List.of("CONFIRM_CODEX_TO_CLAUDE_STRUCTURE"));
            }
        }
        unsupported.forEach(source -> builder.unsupported(
                source, "SOURCE_KIND_NOT_SUPPORTED_IN_PLAN_V1"));
        return builder.build();
    }

    private static PlanParts claudeToCodex(
            List<InstructionSource> active, TargetInventory inventory) {
        List<InstructionSource> supported = active.stream()
                .filter(ConversionPlanner::projectInstructionKind).toList();
        List<InstructionSource> unsupported = active.stream()
                .filter(value -> !projectInstructionKind(value)).toList();
        PlanBuilder builder = new PlanBuilder();
        if (!supported.isEmpty()) {
            boolean nativeAgentsReuse = supported.size() == 1
                    && supported.getFirst().kind() == InstructionSourceKind.IMPORTED_GUIDANCE
                    && supported.getFirst().logicalPath().endsWith("AGENTS.md")
                    && supported.getFirst().state() == InstructionSourceState.ACTIVE
                    && !supported.getFirst().effectiveSha256().isEmpty();
            if (nativeAgentsReuse) {
                InstructionSource source = supported.getFirst();
                TargetCandidate candidate = candidate(
                        source.logicalPath(), CandidateRenderState.REUSED_SOURCE,
                        source.effectiveSha256(), source.includedBytes(),
                        SOURCE_REUSE_PROFILE,
                        inventory.entry(source.logicalPath()), ValidationStatus.NOT_RUN,
                        ValidationStatus.NOT_RUN);
                builder.mapping(MappingGrade.COMPATIBLE, supported, candidate,
                        CapabilityDelta.unchanged(), List.of(), List.of());
            } else {
                String targetPath = soleNestedMemoryPath(supported);
                TargetCandidate candidate = candidate(
                        targetPath, CandidateRenderState.METADATA_ONLY, "", -1,
                        "",
                        inventory.entry(targetPath), ValidationStatus.NOT_RUN,
                        ValidationStatus.NOT_RUN);
                List<String> lossCodes = new ArrayList<>(List.of(
                        "SOURCE_BODY_NOT_IN_IR", "CLAUDE_STRUCTURE_REVIEW_REQUIRED"));
                if (supported.stream().anyMatch(
                        value -> value.state() == InstructionSourceState.ACTIVE_TRUNCATED)) {
                    lossCodes.add("TRUNCATED_EFFECTIVE_SOURCE");
                }
                if (supported.stream().anyMatch(
                        value -> value.kind() == InstructionSourceKind.LOCAL_OVERRIDE)) {
                    lossCodes.add("PERSONAL_LOCALITY_NOT_PORTABLE");
                }
                if (supported.stream().anyMatch(value -> value.scope().kind() == ScopeKind.PATH_GLOB)) {
                    lossCodes.add("CLAUDE_GLOB_EXPRESSION_REDACTED");
                }
                builder.mapping(MappingGrade.ASSISTED, supported, candidate,
                        CapabilityDelta.instructionOnlyUnknown(), lossCodes,
                        List.of("SELECT_CLAUDE_TO_CODEX_STRUCTURE"));
            }
        }
        unsupported.forEach(source -> builder.unsupported(
                source, "SOURCE_KIND_NOT_SUPPORTED_IN_PLAN_V1"));
        return builder.build();
    }

    private static boolean projectInstructionKind(InstructionSource source) {
        return source.kind() == InstructionSourceKind.PROJECT_GUIDANCE
                || source.kind() == InstructionSourceKind.LOCAL_OVERRIDE
                || source.kind() == InstructionSourceKind.MODULAR_RULE
                || source.kind() == InstructionSourceKind.IMPORTED_GUIDANCE;
    }

    private static String soleNestedMemoryPath(List<InstructionSource> sources) {
        if (sources.size() == 1) {
            String path = sources.getFirst().logicalPath();
            int slash = path.lastIndexOf('/');
            if (slash > 0 && path.endsWith("/CLAUDE.md")) {
                return path.substring(0, slash + 1) + "AGENTS.md";
            }
        }
        return "AGENTS.md";
    }

    private static TargetCandidate candidate(
            String path,
            CandidateRenderState renderState,
            String candidateHash,
            long candidateBytes,
            String rendererProfile,
            TargetInventoryEntry inventory,
            ValidationStatus targetValidation,
            ValidationStatus roundTrip) {
        TargetConflictState conflict = switch (inventory.state()) {
            case NOT_EVALUATED -> TargetConflictState.NOT_EVALUATED;
            case ABSENT -> TargetConflictState.NO_EXISTING_TARGET;
            case INVALID -> TargetConflictState.INVALID_TARGET;
            case OUTSIDE_SCOPE -> TargetConflictState.OUTSIDE_SCOPE;
            case CHANGED_DURING_PROBE -> TargetConflictState.TARGET_CHANGED_DURING_PROBE;
            case PRESENT -> {
                if (renderState == CandidateRenderState.METADATA_ONLY) {
                    yield TargetConflictState.EXISTING_TARGET_PRESENT;
                }
                yield candidateHash.equals(inventory.sha256())
                        ? TargetConflictState.EXISTING_TARGET_IDENTICAL
                        : TargetConflictState.EXISTING_TARGET_CONFLICT;
            }
        };
        ValidationStatus review = switch (conflict) {
            case NOT_EVALUATED -> ValidationStatus.NOT_RUN;
            case NO_EXISTING_TARGET -> ValidationStatus.NOT_APPLICABLE;
            case EXISTING_TARGET_IDENTICAL -> ValidationStatus.PASSED;
            case EXISTING_TARGET_PRESENT, EXISTING_TARGET_CONFLICT, INVALID_TARGET,
                    OUTSIDE_SCOPE, TARGET_CHANGED_DURING_PROBE -> ValidationStatus.FAILED;
        };
        if (renderState == CandidateRenderState.METADATA_ONLY
                && review != ValidationStatus.NOT_APPLICABLE) {
            review = ValidationStatus.NOT_RUN;
        }
        boolean reviewRan = review == ValidationStatus.PASSED
                || review == ValidationStatus.FAILED;
        return new TargetCandidate(
                path, renderState, candidateHash, candidateBytes, rendererProfile, conflict,
                inventory.state() == TargetInventoryState.PRESENT ? inventory.sha256() : "",
                inventory.state() == TargetInventoryState.PRESENT ? inventory.byteSize() : -1,
                targetValidation, "", "", roundTrip, "", "", review,
                reviewRan ? CandidateValidationPipeline.METADATA_TARGET_REVIEW_PROFILE : "",
                reviewRan ? candidateHash : "");
    }

    private static String recipeId(
            ProjectSemanticProfile source, ProjectSemanticProfile target) {
        if (source == ProjectSemanticProfile.CODEX_PROJECT_V1
                && target == ProjectSemanticProfile.CLAUDE_CODE_PROJECT_V1) {
            return CODEX_TO_CLAUDE_RECIPE;
        }
        if (source == ProjectSemanticProfile.CLAUDE_CODE_PROJECT_V1
                && target == ProjectSemanticProfile.CODEX_PROJECT_V1) {
            return CLAUDE_TO_CODEX_RECIPE;
        }
        throw new IllegalArgumentException("unsupported conversion profile pair");
    }

    private static ConversionPlanStatus derivedStatus(
            ConversionRequest request,
            List<MappingItem> mappings,
            List<LossItem> losses,
            List<UnresolvedQuestion> questions) {
        if (mappings.isEmpty()) {
            return ConversionPlanStatus.EMPTY;
        }
        long supported = mappings.stream()
                .filter(value -> value.grade() != MappingGrade.UNSUPPORTED).count();
        if (supported == 0) {
            return ConversionPlanStatus.BLOCKED;
        }
        if (supported != mappings.size()) {
            return ConversionPlanStatus.PARTIALLY_SUPPORTED;
        }
        boolean review = request.sourceResolutionStatus() != IrResolutionStatus.COMPLETE
                || !losses.isEmpty() || !questions.isEmpty()
                || mappings.stream().anyMatch(value -> value.grade() == MappingGrade.ASSISTED
                        || value.capabilityDelta().blocksReadyStatus()
                        || value.targetCandidate().stream().anyMatch(
                                candidate -> !candidate.fullyValidated()));
        return review ? ConversionPlanStatus.REVIEW_REQUIRED : ConversionPlanStatus.PREVIEW_READY;
    }

    private static String fingerprint(InstructionIr source) {
        List<String> lines = new ArrayList<>();
        lines.add("schema=" + source.schemaVersion());
        lines.add("id=" + source.id());
        lines.add("status=" + source.resolutionStatus().name());
        source.sources().stream().map(value -> String.join("|",
                        "source", value.identity().hostId(), value.identity().sourceId(),
                        value.kind().name(), value.state().name(), value.logicalPath(),
                        value.scope().kind().name(), value.scope().expression(),
                        Integer.toString(value.loadOrder()), value.revisionSha256(),
                        value.effectiveSha256(), Long.toString(value.includedBytes())))
                .sorted().forEach(lines::add);
        source.directives().stream().map(value -> String.join("|",
                        "directive", value.id(), value.source().sourceId(),
                        value.normalizedHash(), value.polarity().name(), Integer.toString(value.line())))
                .sorted().forEach(lines::add);
        source.provenance().stream().map(value -> String.join("|",
                        "edge", value.kind().name(), value.from().kind().name(), value.from().id(),
                        value.to().kind().name(), value.to().id()))
                .sorted().forEach(lines::add);
        source.limitations().stream().sorted().map(value -> "limitation|" + value)
                .forEach(lines::add);
        return sha256(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    private static String stableId(String prefix, String... parts) {
        return prefix + "_" + sha256(String.join("\n", parts).getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record PlanParts(
            List<MappingItem> mappings,
            List<LossItem> losses,
            List<UnresolvedQuestion> questions) {}

    private static final class PlanBuilder {
        private final List<MappingItem> mappings = new ArrayList<>();
        private final List<LossItem> losses = new ArrayList<>();
        private final List<UnresolvedQuestion> questions = new ArrayList<>();

        private void mapping(
                MappingGrade grade,
                List<InstructionSource> sources,
                TargetCandidate candidate,
                CapabilityDelta delta,
                List<String> lossCodes,
                List<String> questionCodes) {
            List<IrNodeRef> refs = sources.stream()
                    .map(value -> IrNodeRef.source(value.identity().sourceId())).toList();
            List<String> sourceIds = sources.stream()
                    .map(value -> value.identity().sourceId()).sorted().toList();
            String subject = String.join("\n", sourceIds);
            List<String> lossIds = lossCodes.stream().distinct().sorted().map(code -> {
                String id = stableId("loss", code, subject);
                losses.add(new LossItem(id, code, LossSeverity.REVIEW_REQUIRED,
                        lossSummary(code), refs));
                return id;
            }).toList();
            List<String> questionIds = questionCodes.stream().distinct().sorted().map(code -> {
                String id = stableId("question", code, subject);
                questions.add(new UnresolvedQuestion(id, code, questionPrompt(code), refs));
                return id;
            }).toList();
            mappings.add(new MappingItem(
                    stableId("mapping", grade.name(), candidate.logicalPath(), subject,
                            candidate.renderState().name(), candidate.candidateSha256(),
                            Long.toString(candidate.candidateByteSize()), candidate.rendererProfile(),
                            candidate.conflictState().name(), candidate.existingTargetSha256(),
                            Long.toString(candidate.existingTargetByteSize()),
                            candidate.targetValidation().name(), candidate.targetValidatorProfile(),
                            candidate.targetValidationSubjectSha256(),
                            candidate.semanticRoundTrip().name(),
                            candidate.semanticRoundTripProfile(),
                            candidate.semanticRoundTripSubjectSha256(),
                            candidate.threeWayReview().name(), candidate.threeWayReviewProfile(),
                            candidate.threeWayReviewSubjectSha256(),
                            delta.tools().name(), delta.permissions().name(), delta.network().name(),
                            delta.modelInvocation().name(), delta.automaticInvocation().name(),
                            delta.executableBehavior().name()),
                    grade, refs, java.util.Optional.of(candidate), delta, lossIds, questionIds));
        }

        private void unsupported(InstructionSource source, String code) {
            IrNodeRef ref = IrNodeRef.source(source.identity().sourceId());
            String lossId = stableId("loss", code, source.identity().sourceId());
            losses.add(new LossItem(lossId, code, LossSeverity.BLOCKING,
                    lossSummary(code), List.of(ref)));
            mappings.add(new MappingItem(
                    stableId("mapping", "unsupported", source.identity().sourceId()),
                    MappingGrade.UNSUPPORTED, List.of(ref), java.util.Optional.empty(),
                    CapabilityDelta.instructionOnlyUnknown(), List.of(lossId), List.of()));
        }

        private PlanParts build() {
            mappings.sort(Comparator.comparing(MappingItem::id));
            losses.sort(Comparator.comparing(LossItem::code).thenComparing(LossItem::id));
            questions.sort(Comparator.comparing(UnresolvedQuestion::code)
                    .thenComparing(UnresolvedQuestion::id));
            return new PlanParts(List.copyOf(mappings), List.copyOf(losses), List.copyOf(questions));
        }

        private static String lossSummary(String code) {
            return switch (code) {
                case "SOURCE_BODY_NOT_IN_IR" ->
                        "The content-free IR cannot render target instruction bytes";
                case "CODEX_STRUCTURE_REVIEW_REQUIRED" ->
                        "Codex precedence and directory scope require a reviewed Claude structure";
                case "CLAUDE_STRUCTURE_REVIEW_REQUIRED" ->
                        "Claude memory, import, and rule structure require a reviewed Codex structure";
                case "TRUNCATED_EFFECTIVE_SOURCE" ->
                        "Only an effective prefix of at least one source was available";
                case "PERSONAL_LOCALITY_NOT_PORTABLE" ->
                        "Personal local memory does not have an automatic team-level equivalent";
                case "CLAUDE_GLOB_EXPRESSION_REDACTED" ->
                        "The content-free IR stores only a fingerprint of the Claude path expression";
                default -> "This source kind is outside ConversionPlan v1";
            };
        }

        private static String questionPrompt(String code) {
            return switch (code) {
                case "CONFIRM_CODEX_TO_CLAUDE_STRUCTURE" ->
                        "Choose how Codex precedence and nested scopes should be represented in Claude Code";
                case "SELECT_CLAUDE_TO_CODEX_STRUCTURE" ->
                        "Choose which Claude memory, imports, and rules should become Codex project guidance";
                default -> "Review the unresolved conversion decision";
            };
        }
    }
}
