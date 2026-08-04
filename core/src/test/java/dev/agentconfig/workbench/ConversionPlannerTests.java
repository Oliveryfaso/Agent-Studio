package dev.agentconfig.workbench;

import dev.agentconfig.workbench.analyze.InstructionAnalysisEngine;
import dev.agentconfig.workbench.context.ContextCompileRequest;
import dev.agentconfig.workbench.context.EffectiveInstructionCompiler;
import dev.agentconfig.workbench.context.ProjectSemanticProfile;
import dev.agentconfig.workbench.conversion.CandidateRenderState;
import dev.agentconfig.workbench.conversion.BoundedCandidateRenderer;
import dev.agentconfig.workbench.conversion.CandidateValidationPipeline;
import dev.agentconfig.workbench.conversion.ConversionPlan;
import dev.agentconfig.workbench.conversion.ConversionPlanStatus;
import dev.agentconfig.workbench.conversion.ConversionPlanner;
import dev.agentconfig.workbench.conversion.LossSeverity;
import dev.agentconfig.workbench.conversion.MappingGrade;
import dev.agentconfig.workbench.conversion.TargetConflictState;
import dev.agentconfig.workbench.conversion.TargetInventory;
import dev.agentconfig.workbench.conversion.TargetInventoryEntry;
import dev.agentconfig.workbench.conversion.TargetInventoryState;
import dev.agentconfig.workbench.conversion.ValidationStatus;
import dev.agentconfig.workbench.ir.ActivationEvidence;
import dev.agentconfig.workbench.ir.ActivationEvidenceKind;
import dev.agentconfig.workbench.ir.ActivationOutcome;
import dev.agentconfig.workbench.ir.InstructionIr;
import dev.agentconfig.workbench.ir.InstructionScope;
import dev.agentconfig.workbench.ir.InstructionSource;
import dev.agentconfig.workbench.ir.InstructionSourceKind;
import dev.agentconfig.workbench.ir.InstructionSourceState;
import dev.agentconfig.workbench.ir.IrResolutionStatus;
import dev.agentconfig.workbench.ir.ScopeKind;
import dev.agentconfig.workbench.ir.SourceIdentity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

public final class ConversionPlannerTests {
    private static final String CODEX = ProjectSemanticProfile.CODEX_PROJECT_V1.id();
    private static final String CLAUDE = ProjectSemanticProfile.CLAUDE_CODE_PROJECT_V1.id();
    private int passed;

    public static void main(String[] args) throws Exception {
        new ConversionPlannerTests().runAll();
    }

    private void runAll() throws Exception {
        run("root AGENTS produces a rendered Claude wrapper preview", this::rootCodexWrapper);
        run("existing target is conflict metadata only", this::existingTargetConflict);
        run("nested Codex chain requires assisted structure review", this::nestedCodexIsAssisted);
        run("Claude memory requires metadata-only assisted planning", this::claudeMemoryIsAssisted);
        run("Claude path rule keeps redacted-glob loss", this::claudePathRuleLoss);
        run("partial source IR is rejected", this::partialSourceRejected);
        run("unsupported source kind is report-only and blocked", this::unsupportedSource);
        run("planner IDs and ordering are deterministic", this::plannerIsDeterministic);
        run("empty effective input produces an explicit empty plan", this::emptyInput);
        run("unsafe target states cannot look preview-ready", this::unsafeTargetStates);
        run("renderer enforces byte bounds and defensive copies", this::rendererIsBounded);
        run("incomplete active payload does not enter canonical renderer", this::incompletePayloadAssisted);
        run("plan identity binds target and validation evidence", this::planIdBindsEvidence);
        run("invalid wrapper cannot pass target or round-trip validation", this::invalidWrapperFails);
        System.out.printf("Conversion planner tests: %d passed%n", passed);
    }

    private void rootCodexWrapper() throws Exception {
        withTempDirectory(root -> {
            write(root, "AGENTS.md", "- Run tests\n");
            ConversionPlan plan = plan(analyze("codex", root, root, Optional.empty()), CLAUDE,
                    new TargetInventory(List.of(TargetInventoryEntry.absent("CLAUDE.md"))));
            equal(ConversionPlanStatus.REVIEW_REQUIRED, plan.status(), "status");
            equal(MappingGrade.COMPATIBLE, plan.mappings().getFirst().grade(), "grade");
            var candidate = plan.mappings().getFirst().targetCandidate().orElseThrow();
            equal("CLAUDE.md", candidate.logicalPath(), "target path");
            equal(CandidateRenderState.RENDERED, candidate.renderState(), "render state");
            equal(11L, candidate.candidateByteSize(), "wrapper bytes");
            equal(TargetConflictState.NO_EXISTING_TARGET, candidate.conflictState(), "conflict");
            equal(ValidationStatus.PASSED, candidate.targetValidation(), "target validation");
            equal(ValidationStatus.PASSED, candidate.semanticRoundTrip(), "round trip");
            equal(ValidationStatus.NOT_APPLICABLE, candidate.threeWayReview(), "target review");
            check(!candidate.targetValidatorProfile().isEmpty(), "validator profile missing");
            check(!candidate.semanticRoundTripProfile().isEmpty(), "round-trip profile missing");
            check(!plan.writesPerformed() && !plan.applyEligible(), "preview authority widened");
        });
    }

    private void existingTargetConflict() throws Exception {
        withTempDirectory(root -> {
            write(root, "AGENTS.md", "- Run tests\n");
            String existingHash = sha256("custom Claude memory");
            TargetInventory inventory = new TargetInventory(List.of(new TargetInventoryEntry(
                    "CLAUDE.md", TargetInventoryState.PRESENT, existingHash, 20)));
            ConversionPlan plan = plan(analyze("codex", root, root, Optional.empty()), CLAUDE, inventory);
            var candidate = plan.mappings().getFirst().targetCandidate().orElseThrow();
            equal(TargetConflictState.EXISTING_TARGET_CONFLICT, candidate.conflictState(), "state");
            equal(existingHash, candidate.existingTargetSha256(), "existing hash");
            equal(ValidationStatus.FAILED, candidate.threeWayReview(), "target review");
            check(!candidate.threeWayReviewProfile().isEmpty(), "review profile missing");
        });
    }

    private void nestedCodexIsAssisted() throws Exception {
        withTempDirectory(root -> {
            Path nested = Files.createDirectory(root.resolve("service"));
            write(root, "AGENTS.md", "root\n");
            write(root, "service/AGENTS.md", "nested\n");
            ConversionPlan plan = plan(analyze("codex", root, nested, Optional.empty()), CLAUDE,
                    TargetInventory.unknown());
            equal(MappingGrade.ASSISTED, plan.mappings().getFirst().grade(), "grade");
            check(plan.losses().stream().anyMatch(
                    value -> value.code().equals("CODEX_STRUCTURE_REVIEW_REQUIRED")),
                    "structure loss missing");
            check(!plan.unresolvedQuestions().isEmpty(), "question missing");
            equal(CandidateRenderState.METADATA_ONLY,
                    plan.mappings().getFirst().targetCandidate().orElseThrow().renderState(),
                    "render state");
        });
    }

    private void claudeMemoryIsAssisted() throws Exception {
        withTempDirectory(root -> {
            write(root, "CLAUDE.md", "- Run tests\n");
            ConversionPlan plan = plan(analyze("claude-code", root, root, Optional.empty()), CODEX,
                    new TargetInventory(List.of(TargetInventoryEntry.absent("AGENTS.md"))));
            equal(MappingGrade.ASSISTED, plan.mappings().getFirst().grade(), "grade");
            equal("AGENTS.md", plan.mappings().getFirst().targetCandidate().orElseThrow()
                    .logicalPath(), "target path");
            check(plan.losses().stream().anyMatch(
                    value -> value.code().equals("SOURCE_BODY_NOT_IN_IR")), "body loss missing");
        });
    }

    private void claudePathRuleLoss() throws Exception {
        withTempDirectory(root -> {
            write(root, "CLAUDE.md", "root\n");
            write(root, ".claude/rules/api.md",
                    "---\npaths: [\"src/api/**\"]\n---\n- Validate responses\n");
            var request = new ContextCompileRequest(
                    "claude-code", root, root, Optional.empty(), Optional.of(Path.of("src/api/User.java")));
            InstructionIr ir = new InstructionAnalysisEngine().analyze(
                    new EffectiveInstructionCompiler().compile(request)).instructionIr();
            ConversionPlan plan = plan(ir, CODEX, TargetInventory.unknown());
            check(plan.losses().stream().anyMatch(
                    value -> value.code().equals("CLAUDE_GLOB_EXPRESSION_REDACTED")),
                    "redacted glob loss missing");
        });
    }

    private void partialSourceRejected() throws Exception {
        withTempDirectory(root -> {
            write(root, ".claude/rules/api.md",
                    "---\npaths: [\"src/**\"]\n---\n- Validate responses\n");
            InstructionIr ir = analyze("claude-code", root, root, Optional.empty());
            equal(IrResolutionStatus.PARTIAL, ir.resolutionStatus(), "fixture status");
            expectThrows(IllegalArgumentException.class,
                    () -> plan(ir, CODEX, TargetInventory.unknown()));
        });
    }

    private void unsupportedSource() {
        InstructionIr ir = ir("codex", List.of(source(
                "agent", InstructionSourceKind.AGENT_DEFINITION, "agent.md", 0)));
        ConversionPlan plan = plan(ir, CLAUDE, TargetInventory.unknown());
        equal(ConversionPlanStatus.BLOCKED, plan.status(), "status");
        equal(MappingGrade.UNSUPPORTED, plan.mappings().getFirst().grade(), "grade");
        check(plan.mappings().getFirst().targetCandidate().isEmpty(), "candidate exposed");
        check(plan.losses().stream().allMatch(
                value -> value.severity() == LossSeverity.BLOCKING), "loss not blocking");
    }

    private void plannerIsDeterministic() {
        InstructionSource first = source(
                "first", InstructionSourceKind.PROJECT_GUIDANCE, "AGENTS.md", 0);
        InstructionSource second = source(
                "second", InstructionSourceKind.PROJECT_GUIDANCE, "service/AGENTS.md", 1);
        ConversionPlan one = plan(ir("codex", List.of(first, second)), CLAUDE,
                TargetInventory.unknown());
        ConversionPlan two = plan(ir("codex", List.of(second, first)), CLAUDE,
                TargetInventory.unknown());
        equal(one, two, "deterministic plan");
    }

    private void emptyInput() {
        ConversionPlan plan = plan(ir("codex", List.of()), CLAUDE, TargetInventory.unknown());
        equal(ConversionPlanStatus.EMPTY, plan.status(), "status");
        check(plan.mappings().isEmpty(), "empty plan mappings");
    }

    private void unsafeTargetStates() {
        InstructionIr ir = ir("codex", List.of(source(
                "root", InstructionSourceKind.PROJECT_GUIDANCE, "AGENTS.md", 0)));
        TargetInventory outside = new TargetInventory(List.of(new TargetInventoryEntry(
                "CLAUDE.md", TargetInventoryState.OUTSIDE_SCOPE, "", -1)));
        ConversionPlan plan = plan(ir, CLAUDE, outside);
        equal(ConversionPlanStatus.REVIEW_REQUIRED, plan.status(), "status");
        equal(TargetConflictState.OUTSIDE_SCOPE,
                plan.mappings().getFirst().targetCandidate().orElseThrow().conflictState(),
                "conflict state");

        TargetInventory changed = new TargetInventory(List.of(new TargetInventoryEntry(
                "CLAUDE.md", TargetInventoryState.CHANGED_DURING_PROBE, "", -1)));
        ConversionPlan changedPlan = plan(ir, CLAUDE, changed);
        var changedCandidate = changedPlan.mappings().getFirst().targetCandidate().orElseThrow();
        equal(TargetConflictState.TARGET_CHANGED_DURING_PROBE,
                changedCandidate.conflictState(), "changed target state");
        equal(ValidationStatus.FAILED, changedCandidate.threeWayReview(), "changed review");
        check(!changedCandidate.fullyValidated(), "changed target looked validated");
    }

    private void rendererIsBounded() {
        expectThrows(IllegalArgumentException.class,
                () -> new BoundedCandidateRenderer(10)
                        .renderClaudeProjectImportWrapper("AGENTS.md"));
        var rendered = new BoundedCandidateRenderer(11)
                .renderClaudeProjectImportWrapper("AGENTS.md");
        equal(11L, rendered.byteSize(), "candidate size");
        byte[] first = rendered.bytes();
        first[0] = 'X';
        equal((byte) '@', rendered.bytes()[0], "defensive byte copy");
    }

    private void incompletePayloadAssisted() {
        String hash = sha256("incomplete-root");
        InstructionSource incomplete = new InstructionSource(
                new SourceIdentity("codex", "source-incomplete"),
                InstructionSourceKind.PROJECT_GUIDANCE, InstructionSourceState.ACTIVE,
                hash, hash, 11, 10, "AGENTS.md", InstructionScope.project(), 0,
                List.of(new ActivationEvidence(
                        ActivationEvidenceKind.ALWAYS, ActivationOutcome.MATCHED, "")));
        ConversionPlan plan = plan(ir("codex", List.of(incomplete)), CLAUDE,
                TargetInventory.unknown());
        equal(MappingGrade.ASSISTED, plan.mappings().getFirst().grade(), "grade");
        equal(CandidateRenderState.METADATA_ONLY,
                plan.mappings().getFirst().targetCandidate().orElseThrow().renderState(),
                "render state");
    }

    private void planIdBindsEvidence() {
        InstructionIr source = ir("codex", List.of(source(
                "root", InstructionSourceKind.PROJECT_GUIDANCE, "AGENTS.md", 0)));
        ConversionPlan unknown = plan(source, CLAUDE, TargetInventory.unknown());
        ConversionPlan absent = plan(source, CLAUDE,
                new TargetInventory(List.of(TargetInventoryEntry.absent("CLAUDE.md"))));
        TargetInventory conflict = new TargetInventory(List.of(new TargetInventoryEntry(
                "CLAUDE.md", TargetInventoryState.PRESENT, sha256("other"), 5)));
        ConversionPlan existing = plan(source, CLAUDE, conflict);
        check(!unknown.id().equals(absent.id()), "target state did not change plan id");
        check(!absent.id().equals(existing.id()), "target hash did not change plan id");
    }

    private void invalidWrapperFails() {
        InstructionSource root = source(
                "root", InstructionSourceKind.PROJECT_GUIDANCE, "AGENTS.md", 0);
        var rendered = new BoundedCandidateRenderer()
                .renderClaudeProjectImportWrapper("OTHER.md");
        var candidate = new CandidateValidationPipeline().validateCodexRootClaudeWrapper(
                root, rendered, TargetInventoryEntry.absent("CLAUDE.md"));
        equal(ValidationStatus.FAILED, candidate.targetValidation(), "target validation");
        equal(ValidationStatus.NOT_RUN, candidate.semanticRoundTrip(), "round trip");
        check(!candidate.fullyValidated(), "invalid wrapper looked validated");
    }

    private static ConversionPlan plan(
            InstructionIr source, String targetProfile, TargetInventory inventory) {
        String sourceProfile = source.sources().stream().findFirst()
                .map(value -> ProjectSemanticProfile.forHost(value.identity().hostId()).id())
                .orElse(targetProfile.equals(CODEX) ? CLAUDE : CODEX);
        return new ConversionPlanner().plan(source, sourceProfile, targetProfile, inventory);
    }

    private static InstructionIr analyze(
            String host, Path root, Path cwd, Optional<Path> target) throws IOException {
        var request = new ContextCompileRequest(
                host, root, cwd, Optional.empty(), target);
        return new InstructionAnalysisEngine().analyze(
                new EffectiveInstructionCompiler().compile(request)).instructionIr();
    }

    private static InstructionIr ir(String host, List<InstructionSource> sources) {
        return new InstructionIr(
                InstructionIr.CURRENT_SCHEMA_VERSION, "ir-fixture", IrResolutionStatus.COMPLETE,
                sources, List.of(), List.of(), List.of());
    }

    private static InstructionSource source(
            String id, InstructionSourceKind kind, String path, int order) {
        String hash = sha256(id + path);
        ScopeKind scopeKind = path.contains("/") ? ScopeKind.DIRECTORY_TREE : ScopeKind.PROJECT;
        InstructionScope scope = scopeKind == ScopeKind.PROJECT
                ? InstructionScope.project()
                : new InstructionScope(scopeKind, path.substring(0, path.lastIndexOf('/')) + "/**");
        return new InstructionSource(
                new SourceIdentity("codex", "source-" + id), kind, InstructionSourceState.ACTIVE,
                hash, hash, 10, 10, path, scope, order,
                List.of(new ActivationEvidence(
                        ActivationEvidenceKind.ALWAYS, ActivationOutcome.MATCHED, "")));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void write(Path root, String relative, String content) throws IOException {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private void withTempDirectory(ThrowingConsumer<Path> test) throws Exception {
        Path root = Files.createTempDirectory("acw-conversion-plan-");
        try {
            test.accept(root);
        } finally {
            deleteOwnedTempTree(root);
        }
    }

    private static void deleteOwnedTempTree(Path root) throws IOException {
        if (!root.getFileName().toString().startsWith("acw-conversion-plan-")
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing unexpected test path: " + root);
        }
        try (var paths = Files.walk(root)) {
            List<Path> owned = new ArrayList<>(paths.toList());
            owned.sort(Comparator.reverseOrder());
            for (Path path : owned) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void run(String name, ThrowingRunnable test) throws Exception {
        test.run();
        passed++;
        System.out.println("PASS " + name);
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

    private static void expectThrows(Class<? extends Throwable> type, ThrowingRunnable action) {
        try {
            action.run();
            throw new AssertionError("Expected " + type.getSimpleName());
        } catch (Throwable failure) {
            if (!type.isInstance(failure)) {
                throw new AssertionError("Unexpected failure: " + failure, failure);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T value) throws Exception;
    }
}
