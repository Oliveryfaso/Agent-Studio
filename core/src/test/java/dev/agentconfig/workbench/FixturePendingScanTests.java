package dev.agentconfig.workbench;

import dev.agentconfig.workbench.blueprint.BlueprintPreviewService;
import dev.agentconfig.workbench.skilldraft.CodexSkillDraftPreview;
import dev.agentconfig.workbench.skilldraft.CodexSkillDraftService;
import dev.agentconfig.workbench.transaction.FixturePendingScanReport;
import dev.agentconfig.workbench.transaction.FixturePendingScanRequest;
import dev.agentconfig.workbench.transaction.FixtureSkillApplyReceipt;
import dev.agentconfig.workbench.transaction.FixtureSkillTransactionService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Adversarial checks for the explicit, read-only fixture transaction discovery API. */
public final class FixturePendingScanTests {
    private int passed;
    private int skipped;

    public static void main(String[] args) throws Exception {
        new FixturePendingScanTests().runAll();
    }

    private void runAll() throws Exception {
        run("scan is read-only and classifies every pending phase", this::classifiesReadOnly);
        run("pending output has stable transaction ordering", this::stableOrdering);
        run("manifest budget paginates without gaps", this::manifestPagination);
        run("direct entry budget returns no partial inventory", this::directEntryBudget);
        run("junk and linked entries are counted but never followed", this::junkAndLinks);
        System.out.printf("Fixture pending scan tests: %d passed, %d skipped%n", passed, skipped);
    }

    private void classifiesReadOnly() throws Exception {
        withFixture(fixture -> {
            PendingSet pending = createPendingSet(fixture);
            Map<String, String> workspaceBefore = fingerprint(fixture.workspace());
            Map<String, String> stateBefore = fingerprint(fixture.state());

            FixturePendingScanReport report = fixture.scan(FixturePendingScanRequest.defaults());

            equal(FixturePendingScanReport.Status.COMPLETE, report.status(), "status");
            equal(3, report.pendingTransactions().size(), "pending count");
            assertPending(report, pending.prepared(), FixturePendingScanReport.Action.RECOVER_APPLY,
                    FixturePendingScanReport.Phase.PREPARED);
            assertPending(report, pending.commitIntent(),
                    FixturePendingScanReport.Action.RECOVER_APPLY,
                    FixturePendingScanReport.Phase.COMMIT_INTENT);
            assertPending(report, pending.rollbackIntent(),
                    FixturePendingScanReport.Action.RECOVER_ROLLBACK,
                    FixturePendingScanReport.Phase.ROLLBACK_INTENT);
            check(report.fixtureOnly(), "scan lost fixture-only boundary");
            check(!report.contentIncluded(), "scan returned content");
            check(!report.writesPerformed(), "scan claimed writes");
            equal(workspaceBefore, fingerprint(fixture.workspace()), "workspace changed by scan");
            equal(stateBefore, fingerprint(fixture.state()), "transaction state changed by scan");
            check(Files.exists(fixture.target(), LinkOption.NOFOLLOW_LINKS),
                    "scan automatically recovered rollback intent");
        });
    }

    private void stableOrdering() throws Exception {
        withFixture(fixture -> {
            createPendingSet(fixture);
            FixturePendingScanReport first = fixture.scan(FixturePendingScanRequest.defaults());
            FixturePendingScanReport second = fixture.scan(FixturePendingScanRequest.defaults());
            List<String> ids = transactionIds(first);
            List<String> sorted = ids.stream().sorted().toList();
            equal(sorted, ids, "transaction order");
            equal(first.pendingTransactions(), second.pendingTransactions(), "repeat scan order");
        });
    }

    private void manifestPagination() throws Exception {
        withFixture(fixture -> {
            List<String> expected = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                expected.add(fixture.apply(FixtureSkillTransactionService.FailurePoint.AFTER_SNAPSHOT)
                        .transactionId().orElseThrow());
            }
            expected.sort(String::compareTo);

            List<String> actual = new ArrayList<>();
            Optional<String> cursor = Optional.empty();
            int partialPages = 0;
            while (true) {
                FixturePendingScanReport page = fixture.scan(new FixturePendingScanRequest(
                        1, 32, 1, cursor));
                actual.addAll(transactionIds(page));
                if (page.status() == FixturePendingScanReport.Status.COMPLETE) {
                    check(page.nextCursor().isEmpty(), "complete page exposed cursor");
                    break;
                }
                equal(FixturePendingScanReport.Status.PARTIAL_MANIFEST_BUDGET,
                        page.status(), "page status");
                equal(1, page.manifestsInspected(), "page manifest count");
                cursor = page.nextCursor();
                check(cursor.isPresent(), "partial page omitted cursor");
                partialPages++;
                check(partialPages <= expected.size(), "pagination did not converge");
            }
            equal(expected, actual, "paginated transaction ids");
            equal((long) expected.size(), actual.stream().distinct().count(),
                    "duplicate page entries");
        });
    }

    private void directEntryBudget() throws Exception {
        withFixture(fixture -> {
            fixture.apply(FixtureSkillTransactionService.FailurePoint.AFTER_SNAPSHOT);
            Files.writeString(fixture.state().resolve("junk-entry"), "do not inspect\n");

            FixturePendingScanReport report = fixture.scan(
                    new FixturePendingScanRequest(1, 1, 16, Optional.empty()));

            equal(FixturePendingScanReport.Status.DIRECT_ENTRY_BUDGET_EXCEEDED,
                    report.status(), "status");
            check(report.pendingTransactions().isEmpty(), "partial inventory escaped budget");
            equal(0, report.manifestsInspected(), "manifest inspected past direct budget");
            check(report.nextCursor().isEmpty(), "entry-budget response exposed cursor");
            check(!report.contentIncluded() && !report.writesPerformed(), "unsafe report flags");
        });
    }

    private void junkAndLinks() throws Exception {
        withFixture(fixture -> {
            String valid = fixture.apply(FixtureSkillTransactionService.FailurePoint.AFTER_SNAPSHOT)
                    .transactionId().orElseThrow();
            Files.writeString(fixture.state().resolve("notes.txt"), "secret junk\n");
            Files.createDirectory(fixture.state().resolve("not-a-transaction"));
            Path invalidManifest = Files.createDirectory(
                    fixture.state().resolve(UUID.randomUUID().toString()));
            Files.writeString(invalidManifest.resolve("manifest.properties"), "invalid\n");

            Path outside = Files.createDirectory(fixture.base().resolve("outside-state"));
            Files.writeString(outside.resolve("manifest.properties"), "must not be read\n");
            Path linked = fixture.state().resolve(UUID.randomUUID().toString());
            boolean linkedCreated = trySymlink(linked, outside);

            FixturePendingScanReport report = fixture.scan(FixturePendingScanRequest.defaults());

            equal(FixturePendingScanReport.Status.COMPLETE, report.status(), "status");
            equal(List.of(valid), transactionIds(report), "valid pending transaction");
            equal(2 + (linkedCreated ? 1 : 0), report.invalidEntries(), "invalid entry count");
            equal(1, report.invalidTransactions(), "invalid manifest count");
            check(report.pendingTransactions().stream()
                    .noneMatch(entry -> entry.transactionId().equals(linked.getFileName().toString())),
                    "linked transaction was followed");
            if (!linkedCreated) skip("symbolic links are unavailable on this platform");
        });
    }

    private static PendingSet createPendingSet(Fixture fixture) throws Exception {
        String prepared = fixture.apply(FixtureSkillTransactionService.FailurePoint.AFTER_SNAPSHOT)
                .transactionId().orElseThrow();
        String commitIntent = fixture.apply(
                FixtureSkillTransactionService.FailurePoint.AFTER_COMMIT_INTENT)
                .transactionId().orElseThrow();
        FixtureSkillApplyReceipt applied = fixture.apply(
                FixtureSkillTransactionService.FailurePoint.NONE);
        String rollbackIntent = applied.transactionId().orElseThrow();
        fixture.service().rollback(fixture.workspace(), fixture.state(), rollbackIntent,
                FixtureSkillTransactionService.RollbackFailurePoint.AFTER_ROLLBACK_INTENT);
        return new PendingSet(prepared, commitIntent, rollbackIntent);
    }

    private static void assertPending(FixturePendingScanReport report, String transactionId,
            FixturePendingScanReport.Action action, FixturePendingScanReport.Phase phase) {
        check(report.pendingTransactions().contains(
                new FixturePendingScanReport.PendingTransaction(transactionId, action, phase)),
                "missing pending state " + phase);
    }

    private static List<String> transactionIds(FixturePendingScanReport report) {
        return report.pendingTransactions().stream()
                .map(FixturePendingScanReport.PendingTransaction::transactionId).toList();
    }

    private void withFixture(ThrowingConsumer<Fixture> test) throws Exception {
        Path base = Files.createTempDirectory("acw-s3-pending-");
        try {
            Path workspace = Files.createDirectory(base.resolve("workspace"));
            Path state = Files.createDirectory(base.resolve("state"));
            Files.writeString(workspace.resolve(FixtureSkillTransactionService.WORKSPACE_MARKER),
                    FixtureSkillTransactionService.WORKSPACE_MARKER_CONTENT);
            Files.writeString(state.resolve(FixtureSkillTransactionService.STATE_MARKER),
                    FixtureSkillTransactionService.STATE_MARKER_CONTENT);
            Path target = Files.createDirectories(
                    workspace.resolve(".agents/skills/review-api-change")).resolve("SKILL.md");
            test.accept(new Fixture(base, workspace, state, target, draft(),
                    new FixtureSkillTransactionService()));
        } finally {
            deleteOwned(base);
        }
    }

    private static CodexSkillDraftPreview draft() throws IOException {
        String request = "repeated-workflow: true\nclear-trigger: true\nsuccess-criteria: true\n"
                + "confirmed-artifact: skill\nconfirmed-scope: project\n"
                + "name: review-api-change\ndescription: Review API contract changes.\n"
                + "goal: Produce a bounded review.\ninput: Changed files\noutput: Findings\n"
                + "trigger: Use for API changes.\nexclusion: Do not use for UI changes.\n"
                + "boundary-example: A CSS edit is outside scope.\n"
                + "should-trigger: Review endpoint\nshould-trigger: Review schema\n"
                + "should-trigger: Review migration\nshould-not-trigger: Review CSS\n"
                + "should-not-trigger: Draft marketing\nshould-not-trigger: Rename image\n"
                + "step: Inspect contracts\ncompletion: Every contract has a result.\n"
                + "validation: Confirm findings cite inputs.\npermission: NONE\nrisk: LOW\n";
        var blueprint = new BlueprintPreviewService().preview(new ByteArrayInputStream(
                request.getBytes(StandardCharsets.UTF_8)));
        return new CodexSkillDraftService().draft(blueprint);
    }

    private static Map<String, String> fingerprint(Path root) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                BasicFileAttributes attributes = Files.readAttributes(
                        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                String kind = attributes.isDirectory() ? "DIRECTORY"
                        : attributes.isRegularFile() ? sha256(Files.readAllBytes(path))
                        : attributes.isSymbolicLink() ? "LINK" : "OTHER";
                result.put(root.relativize(path).toString(), kind + ":" + attributes.size()
                        + ":" + attributes.lastModifiedTime().toMillis());
            }
        }
        return result;
    }

    private static boolean trySymlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            return false;
        }
    }

    private static void deleteOwned(Path root) throws IOException {
        if (!root.getFileName().toString().startsWith("acw-s3-pending-")
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing cleanup outside owned fixture: " + root);
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void run(String name, ThrowingRunnable test) throws Exception {
        test.run();
        passed++;
        System.out.println("PASS " + name);
    }

    private void skip(String reason) {
        skipped++;
        System.out.println("SKIP " + reason);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String name) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(name + ": expected " + expected + " but got " + actual);
        }
    }

    private record Fixture(Path base, Path workspace, Path state, Path target,
            CodexSkillDraftPreview draft, FixtureSkillTransactionService service) {
        FixtureSkillApplyReceipt apply(FixtureSkillTransactionService.FailurePoint point)
                throws IOException {
            var preview = service.prepare(workspace, draft);
            return service.apply(workspace, state, draft, preview.plan(), point);
        }

        FixturePendingScanReport scan(FixturePendingScanRequest request) throws IOException {
            return service.scanPendingTransactions(workspace, state, request);
        }
    }

    private record PendingSet(String prepared, String commitIntent, String rollbackIntent) {}

    @FunctionalInterface
    private interface ThrowingRunnable { void run() throws Exception; }

    @FunctionalInterface
    private interface ThrowingConsumer<T> { void accept(T value) throws Exception; }
}
