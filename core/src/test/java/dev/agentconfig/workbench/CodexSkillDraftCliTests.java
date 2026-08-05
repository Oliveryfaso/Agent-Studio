package dev.agentconfig.workbench;

import dev.agentconfig.workbench.cli.Cli;
import dev.agentconfig.workbench.blueprint.BlueprintPreviewService;
import dev.agentconfig.workbench.skilldraft.CodexSkillDraftPreview;
import dev.agentconfig.workbench.skilldraft.CodexSkillDraftService;
import dev.agentconfig.workbench.skilldraft.CodexSkillDraftValidator;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class CodexSkillDraftCliTests {
    private int passed;

    public static void main(String[] args) throws Exception {
        new CodexSkillDraftCliTests().runAll();
    }

    private void runAll() throws Exception {
        run("ready draft exposes metadata without content", this::readyMetadata);
        run("content export is exact SKILL.md", this::contentExport);
        run("synthetic diff is explicitly a new file", this::diffExport);
        run("prompt export identifies unchecked target", this::promptExport);
        run("supporting proposal requires review", this::supportingReview);
        run("review-required candidates cannot use raw exports", this::reviewBlocksExports);
        run("source not ready has no candidate", this::sourceNotReady);
        run("YAML and Markdown structures stay inert", this::injectionStaysInert);
        run("unsafe description is an invalid draft", this::unsafeDescription);
        run("validator rejects mutated final bytes", this::mutatedBytesFailValidation);
        run("schema rejects forged validation and oversized candidates", this::schemaForgery);
        run("output is deterministic", this::deterministic);
        run("invalid export mode is rejected", this::invalidMode);
        run("malformed Skill names stop before drafting", this::malformedName);
        System.out.printf("Codex Skill draft CLI tests: %d passed%n", passed);
    }

    private void readyMetadata() {
        Invocation result = invoke(baseRequest(), new String[0]);
        equal(0, result.exitCode(), "exit");
        contains(result.stdout(), "\"status\": \"READY\"");
        contains(result.stdout(), "\"logicalPath\": \".agents/skills/review-api-change/SKILL.md\"");
        contains(result.stdout(), "\"candidateContentIncluded\": false");
        contains(result.stdout(), "\"routingEvalPerformed\": false");
        contains(result.stdout(), "\"writesPerformed\": false");
        check(!result.stdout().contains("## Workflow"), "metadata leaked candidate content");
    }

    private void contentExport() {
        Invocation result = invoke(baseRequest(), "--export", "content");
        equal(0, result.exitCode(), "exit");
        check(result.stdout().startsWith("---\nname: review-api-change\n"),
                "frontmatter is not first");
        contains(result.stdout(), "description: 'Review API changes: verify contracts. Use when:");
        contains(result.stdout(), "## Scope and boundaries\n");
        contains(result.stdout(), "## Workflow\n\n1. Identify changed contracts\n");
        check(result.stdout().endsWith("\n"), "terminal LF missing");
        check(!result.stdout().contains("\r"), "non-LF newline emitted");
        check(!result.stdout().contains("should-trigger"), "eval cases leaked into runtime body");
    }

    private void diffExport() {
        Invocation result = invoke(baseRequest(), "--export", "diff");
        equal(0, result.exitCode(), "exit");
        check(result.stdout().startsWith(
                "# diffMode=SYNTHETIC_NEW_FILE targetState=NOT_CHECKED applyEligible=false\n"),
                "synthetic target-state marker missing");
        contains(result.stdout(), "diff --git a/.agents/skills/review-api-change/SKILL.md");
        contains(result.stdout(), "new file mode 100644");
        contains(result.stdout(), "--- /dev/null");
        contains(result.stdout(), "+---\n+name: review-api-change\n");
        contains(result.stdout(), "+\n+# review-api-change\n");
        check(!result.stdout().contains("\r"), "diff must use LF on every platform");
    }

    private void promptExport() {
        Invocation result = invoke(baseRequest(), "--export", "prompt");
        equal(0, result.exitCode(), "exit");
        contains(result.stdout(), "The target workspace was not inspected");
        contains(result.stdout(), "Do not modify any other file");
        contains(result.stdout(), "Expected SHA-256:");
        check(!result.stdout().contains("\r"), "prompt must use LF on every platform");
    }

    private void supportingReview() {
        Invocation result = invoke(baseRequest()
                + "supporting-file: references/checklist.md\n", new String[0]);
        equal(3, result.exitCode(), "exit");
        contains(result.stdout(), "\"status\": \"REVIEW_REQUIRED\"");
        contains(result.stdout(), "SUPPORTING_FILES_ARE_PROPOSALS_ONLY");
        check(!result.stdout().contains("references/checklist.md"),
                "metadata exposed or linked an ungenerated supporting file");
    }

    private void reviewBlocksExports() {
        String request = baseRequest() + "supporting-file: references/checklist.md\n";
        for (String mode : new String[] {"content", "diff", "prompt"}) {
            Invocation result = invoke(request, "--export", mode);
            equal(3, result.exitCode(), mode + " exit");
            contains(result.stdout(), "\"status\": \"REVIEW_REQUIRED\"");
            contains(result.stdout(), "\"candidateContentIncluded\": false");
            check(!result.stdout().contains("## Workflow"), mode + " leaked candidate content");
        }
    }

    private void sourceNotReady() {
        Invocation result = invoke("repeated-workflow: true\nclear-trigger: true\n"
                + "success-criteria: true\n", new String[0]);
        equal(3, result.exitCode(), "exit");
        contains(result.stdout(), "\"status\": \"SOURCE_NOT_READY\"");
        contains(result.stdout(), "\"candidate\": null");
    }

    private void injectionStaysInert() {
        String request = baseRequest()
                .replace("Review API changes: verify contracts.",
                        "Review API changes: # verify 'contracts'.")
                .replace("Changed API files", "--- > 1. &#10; ```yaml # heading [link](bad)");
        Invocation result = invoke(request, "--export", "content");
        equal(0, result.exitCode(), "exit");
        contains(result.stdout(), "# verify ''contracts''");
        contains(result.stdout(),
                "- \\-\\-\\- \\> 1\\. \\&\\#10; \\`\\`\\`yaml \\# heading \\[link\\]\\(bad\\)");
        equal(1, occurrences(result.stdout(), "## Inputs\n"), "Inputs heading count");
    }

    private void unsafeDescription() {
        Invocation result = invoke(baseRequest().replace(
                "Review API changes: verify contracts.", "Review <API> changes."), new String[0]);
        equal(3, result.exitCode(), "exit");
        contains(result.stdout(), "\"status\": \"INVALID\"");
        contains(result.stdout(), "\"code\": \"DESCRIPTION_SAFE_SCALAR\", \"passed\": false");
        check(!result.stdout().contains("Review <API>"), "invalid description leaked");
    }

    private void mutatedBytesFailValidation() throws Exception {
        var source = new BlueprintPreviewService().preview(new ByteArrayInputStream(
                baseRequest().getBytes(StandardCharsets.UTF_8)));
        CodexSkillDraftPreview preview = new CodexSkillDraftService().draft(source);
        CodexSkillDraftPreview.Candidate original = preview.candidate().orElseThrow();
        byte[] bytes = original.content().replace("## Goal", "## Removed goal")
                .getBytes(StandardCharsets.UTF_8);
        CodexSkillDraftPreview.Candidate mutated = new CodexSkillDraftPreview.Candidate(
                "skc_mutation_test", original.blueprintId(), original.logicalPath(), bytes,
                sha256(bytes), original.rendererProfileId(),
                Math.toIntExact(new String(bytes, StandardCharsets.UTF_8).chars()
                        .filter(character -> character == '\n').count()));
        var validation = new CodexSkillDraftValidator()
                .validate(source.blueprint().orElseThrow(), mutated);
        equal(CodexSkillDraftPreview.ValidationStatus.FAILED, validation.status(), "status");
        check(validation.checks().stream().anyMatch(check ->
                        "CANONICAL_CONTENT".equals(check.code()) && !check.passed()),
                "mutated content passed canonical validation");
    }

    private void schemaForgery() throws Exception {
        var source = new BlueprintPreviewService().preview(new ByteArrayInputStream(
                baseRequest().getBytes(StandardCharsets.UTF_8)));
        CodexSkillDraftPreview preview = new CodexSkillDraftService().draft(source);
        CodexSkillDraftPreview.Candidate candidate = preview.candidate().orElseThrow();
        boolean forgedRejected = false;
        try {
            new CodexSkillDraftPreview.Validation(1, "codex-project-skill-static-v1",
                    CodexSkillDraftPreview.ValidationStatus.PASSED,
                    java.util.Optional.of(candidate.sha256()), java.util.List.of());
        } catch (IllegalArgumentException expected) {
            forgedRejected = true;
        }
        check(forgedRejected, "empty PASSED validation evidence was accepted");

        boolean oversizedRejected = false;
        byte[] oversized = new byte[(128 * 1024) + 1];
        try {
            new CodexSkillDraftPreview.Candidate("skc_oversized", candidate.blueprintId(),
                    candidate.logicalPath(), oversized, sha256(oversized),
                    candidate.rendererProfileId(), 1);
        } catch (IllegalArgumentException expected) {
            oversizedRejected = true;
        }
        check(oversizedRejected, "oversized candidate was accepted");
    }

    private void deterministic() {
        Invocation first = invoke(baseRequest(), new String[0]);
        Invocation second = invoke(baseRequest(), new String[0]);
        equal(first.stdout(), second.stdout(), "metadata bytes");
        Invocation content = invoke(baseRequest(), "--export", "content");
        Invocation contentAgain = invoke(baseRequest(), "--export", "content");
        equal(content.stdout(), contentAgain.stdout(), "candidate bytes");
    }

    private void invalidMode() {
        Invocation result = invoke(baseRequest(), "--export", "file");
        equal(2, result.exitCode(), "exit");
        check(result.stdout().isEmpty(), "invalid mode emitted stdout");
        contains(result.stderr(), "Usage:");
    }

    private void malformedName() {
        Invocation result = invoke(baseRequest().replace(
                "name: review-api-change", "name: review--api-change"), new String[0]);
        equal(3, result.exitCode(), "exit");
        contains(result.stdout(), "\"status\": \"SOURCE_NOT_READY\"");
    }

    private static String baseRequest() {
        return "repeated-workflow: true\nclear-trigger: true\nsuccess-criteria: true\n"
                + "confirmed-artifact: skill\nconfirmed-scope: project\n"
                + "name: review-api-change\n"
                + "description: Review API changes: verify contracts.\n"
                + "goal: Produce a bounded API change review.\n"
                + "input: Changed API files\noutput: Review findings\n"
                + "trigger: Use when an API contract changes.\n"
                + "exclusion: Do not use for UI-only changes.\n"
                + "boundary-example: A documentation typo is outside scope.\n"
                + "should-trigger: Review a backend endpoint\n"
                + "should-trigger: Review an API migration\n"
                + "should-trigger: Review a schema compatibility change\n"
                + "should-not-trigger: Review CSS colors\n"
                + "should-not-trigger: Draft a marketing page\n"
                + "should-not-trigger: Rename an image\n"
                + "step: Identify changed contracts\nstep: Check compatibility\n"
                + "completion: Every changed contract has a supported finding or pass result.\n"
                + "validation: Confirm every finding cites an input file.\n"
                + "permission: NONE\nrisk: LOW\n";
    }

    private static Invocation invoke(String request, String... options) {
        String[] args = new String[2 + options.length];
        args[0] = "skill-draft-preview";
        args[1] = "codex";
        System.arraycopy(options, 0, args, 2, options.length);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = Cli.phaseOneDefaults().run(args,
                new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)),
                new PrintWriter(stdout, true, StandardCharsets.UTF_8),
                new PrintWriter(stderr, true, StandardCharsets.UTF_8));
        return new Invocation(exit, stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private void run(String name, ThrowingRunnable test) throws Exception {
        test.run();
        passed++;
        System.out.println("PASS " + name);
    }

    private static int occurrences(String value, String marker) {
        int count = 0;
        for (int index = value.indexOf(marker); index >= 0;
                index = value.indexOf(marker, index + marker.length())) count++;
        return count;
    }

    private static void contains(String value, String marker) {
        check(value.contains(marker), "missing: " + marker);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String name) {
        if (!expected.equals(actual)) {
            throw new AssertionError(name + ": expected " + expected + " but got " + actual);
        }
    }

    private record Invocation(int exitCode, String stdout, String stderr) {}

    @FunctionalInterface
    private interface ThrowingRunnable { void run() throws Exception; }
}
