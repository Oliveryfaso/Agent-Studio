package dev.agentconfig.workbench;

import dev.agentconfig.workbench.ir.ActivationEvidence;
import dev.agentconfig.workbench.ir.ActivationEvidenceKind;
import dev.agentconfig.workbench.ir.ActivationOutcome;
import dev.agentconfig.workbench.ir.DirectivePolarity;
import dev.agentconfig.workbench.ir.DirectiveUnit;
import dev.agentconfig.workbench.ir.InstructionIr;
import dev.agentconfig.workbench.ir.InstructionScope;
import dev.agentconfig.workbench.ir.InstructionSource;
import dev.agentconfig.workbench.ir.InstructionSourceKind;
import dev.agentconfig.workbench.ir.InstructionSourceState;
import dev.agentconfig.workbench.ir.IrNodeRef;
import dev.agentconfig.workbench.ir.IrResolutionStatus;
import dev.agentconfig.workbench.ir.ProvenanceEdge;
import dev.agentconfig.workbench.ir.ProvenanceKind;
import dev.agentconfig.workbench.ir.ScopeKind;
import dev.agentconfig.workbench.ir.SourceIdentity;
import java.util.ArrayList;
import java.util.List;

public final class InstructionIrTests {
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private int passed;

    public static void main(String[] args) throws Exception {
        new InstructionIrTests().runAll();
    }

    private void runAll() throws Exception {
        run("constructs a complete host-independent IR", this::constructsCompleteIr);
        run("collections are defensively copied", this::collectionsAreDefensivelyCopied);
        run("source metadata is validated", this::sourceMetadataIsValidated);
        run("directive contains fingerprints but no raw text field", this::directiveIsContentFree);
        run("directive source references are validated", this::directiveReferencesAreValidated);
        run("load order is unique", this::loadOrderIsUnique);
        run("provenance endpoint types and references are validated", this::provenanceIsValidated);
        run("resolution status agrees with limitations", this::resolutionStatusIsValidated);
        System.out.printf("Instruction IR tests: %d passed%n", passed);
    }

    private void constructsCompleteIr() {
        InstructionSource root = source("codex", "src-root", "AGENTS.md", 0);
        InstructionSource imported = source("claude-code", "src-import", "docs/rules.md", 1);
        DirectiveUnit directive = new DirectiveUnit(
                "directive-formatting",
                root.identity(),
                HASH_B,
                DirectivePolarity.REQUIRE,
                12);
        InstructionIr ir = new InstructionIr(
                InstructionIr.CURRENT_SCHEMA_VERSION,
                "ir-demo",
                IrResolutionStatus.COMPLETE,
                List.of(root, imported),
                List.of(directive),
                List.of(
                        new ProvenanceEdge(
                                ProvenanceKind.IMPORTS,
                                IrNodeRef.source(root.identity().sourceId()),
                                IrNodeRef.source(imported.identity().sourceId())),
                        new ProvenanceEdge(
                                ProvenanceKind.DERIVED_FROM,
                                IrNodeRef.directive(directive.id()),
                                IrNodeRef.source(root.identity().sourceId()))),
                List.of());

        equal(1, ir.schemaVersion(), "schema");
        equal("codex", ir.sources().getFirst().identity().hostId(), "host identity");
        equal("AGENTS.md", ir.sources().getFirst().logicalPath(), "logical path");
        equal(ScopeKind.PROJECT, ir.sources().getFirst().scope().kind(), "scope");
        equal(2, ir.provenance().size(), "provenance");
    }

    private void collectionsAreDefensivelyCopied() {
        List<ActivationEvidence> evidence = new ArrayList<>();
        evidence.add(always());
        InstructionSource source = new InstructionSource(
                new SourceIdentity("codex", "source-one"),
                InstructionSourceKind.PROJECT_GUIDANCE,
                InstructionSourceState.ACTIVE,
                HASH_A,
                HASH_A,
                10,
                10,
                "AGENTS.md",
                InstructionScope.project(),
                0,
                evidence);
        evidence.clear();
        equal(1, source.activationEvidence().size(), "source defensive copy");
        expectThrows(UnsupportedOperationException.class,
                () -> source.activationEvidence().add(always()));

        List<InstructionSource> sources = new ArrayList<>(List.of(source));
        InstructionIr ir = complete(sources, List.of(), List.of());
        sources.clear();
        equal(1, ir.sources().size(), "IR defensive copy");
        expectThrows(UnsupportedOperationException.class, () -> ir.sources().clear());
    }

    private void sourceMetadataIsValidated() {
        expectThrows(IllegalArgumentException.class,
                () -> new SourceIdentity("bad host", "source"));
        expectThrows(IllegalArgumentException.class,
                () -> new InstructionSource(
                        new SourceIdentity("codex", "source"),
                        InstructionSourceKind.PROJECT_GUIDANCE,
                        InstructionSourceState.ACTIVE,
                        "ABC",
                        HASH_A,
                        10,
                        10,
                        "AGENTS.md",
                        InstructionScope.project(),
                        0,
                        List.of(always())));
        expectThrows(IllegalArgumentException.class,
                () -> new InstructionSource(
                        new SourceIdentity("codex", "source"),
                        InstructionSourceKind.PROJECT_GUIDANCE,
                        InstructionSourceState.ACTIVE,
                        HASH_A,
                        HASH_A,
                        10,
                        10,
                        "../AGENTS.md",
                        InstructionScope.project(),
                        0,
                        List.of(always())));
        expectThrows(IllegalArgumentException.class,
                () -> new InstructionSource(
                        new SourceIdentity("codex", "source"),
                        InstructionSourceKind.PROJECT_GUIDANCE,
                        InstructionSourceState.SHADOWED,
                        HASH_A,
                        "",
                        10,
                        0,
                        "AGENTS.md",
                        InstructionScope.project(),
                        0,
                        List.of()));
    }

    private void directiveIsContentFree() {
        DirectiveUnit directive = new DirectiveUnit(
                "directive-one",
                new SourceIdentity("codex", "source-one"),
                HASH_B,
                DirectivePolarity.FORBID,
                7);
        equal(List.of("id", "source", "normalizedHash", "polarity", "line"),
                List.of(DirectiveUnit.class.getRecordComponents()).stream()
                        .map(component -> component.getName()).toList(),
                "directive record fields");
        equal(7, directive.line(), "line");
        expectThrows(IllegalArgumentException.class,
                () -> new DirectiveUnit("directive-two", directive.source(), HASH_B,
                        DirectivePolarity.INFORM, 0));
    }

    private void directiveReferencesAreValidated() {
        InstructionSource source = source("codex", "source-one", "AGENTS.md", 0);
        DirectiveUnit unknown = new DirectiveUnit(
                "directive-one",
                new SourceIdentity("codex", "missing-source"),
                HASH_B,
                DirectivePolarity.INFORM,
                1);
        expectThrows(IllegalArgumentException.class,
                () -> complete(List.of(source), List.of(unknown), List.of()));

        DirectiveUnit wrongHost = new DirectiveUnit(
                "directive-one",
                new SourceIdentity("claude-code", "source-one"),
                HASH_B,
                DirectivePolarity.INFORM,
                1);
        expectThrows(IllegalArgumentException.class,
                () -> complete(List.of(source), List.of(wrongHost), List.of()));
    }

    private void loadOrderIsUnique() {
        InstructionSource first = source("codex", "source-one", "AGENTS.md", 0);
        InstructionSource second = source("codex", "source-two", "nested/AGENTS.md", 0);
        expectThrows(IllegalArgumentException.class,
                () -> complete(List.of(first, second), List.of(), List.of()));
    }

    private void provenanceIsValidated() {
        InstructionSource source = source("codex", "source-one", "AGENTS.md", 0);
        ProvenanceEdge missing = new ProvenanceEdge(
                ProvenanceKind.IMPORTS,
                IrNodeRef.source("source-one"),
                IrNodeRef.source("missing"));
        expectThrows(IllegalArgumentException.class,
                () -> complete(List.of(source), List.of(), List.of(missing)));
        expectThrows(IllegalArgumentException.class,
                () -> new ProvenanceEdge(
                        ProvenanceKind.SHADOWS,
                        IrNodeRef.directive("directive-one"),
                        IrNodeRef.source("source-one")));
        expectThrows(IllegalArgumentException.class,
                () -> new ProvenanceEdge(
                        ProvenanceKind.DERIVED_FROM,
                        IrNodeRef.source("source-one"),
                        IrNodeRef.source("source-one")));
    }

    private void resolutionStatusIsValidated() {
        expectThrows(IllegalArgumentException.class,
                () -> new InstructionIr(1, "ir", IrResolutionStatus.COMPLETE,
                        List.of(), List.of(), List.of(), List.of("unknown import")));
        expectThrows(IllegalArgumentException.class,
                () -> new InstructionIr(1, "ir", IrResolutionStatus.PARTIAL,
                        List.of(), List.of(), List.of(), List.of()));
        InstructionIr partial = new InstructionIr(1, "ir", IrResolutionStatus.PARTIAL,
                List.of(), List.of(), List.of(), List.of("external import approval unknown"));
        equal(IrResolutionStatus.PARTIAL, partial.resolutionStatus(), "partial status");
    }

    private static InstructionIr complete(
            List<InstructionSource> sources,
            List<DirectiveUnit> directives,
            List<ProvenanceEdge> provenance) {
        return new InstructionIr(1, "ir-test", IrResolutionStatus.COMPLETE,
                sources, directives, provenance, List.of());
    }

    private static InstructionSource source(
            String hostId,
            String sourceId,
            String logicalPath,
            int loadOrder) {
        return new InstructionSource(
                new SourceIdentity(hostId, sourceId),
                InstructionSourceKind.PROJECT_GUIDANCE,
                InstructionSourceState.ACTIVE,
                HASH_A,
                HASH_A,
                10,
                10,
                logicalPath,
                new InstructionScope(ScopeKind.PROJECT, ""),
                loadOrder,
                List.of(always()));
    }

    private static ActivationEvidence always() {
        return new ActivationEvidence(
                ActivationEvidenceKind.ALWAYS,
                ActivationOutcome.MATCHED,
                "");
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
                throw new AssertionError("Expected " + type.getSimpleName() + ", got " + failure, failure);
            }
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
