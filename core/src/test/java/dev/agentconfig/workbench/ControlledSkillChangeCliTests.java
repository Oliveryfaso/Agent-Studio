package dev.agentconfig.workbench;

import dev.agentconfig.workbench.cli.Cli;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ControlledSkillChangeCliTests {
    private int passed;
    private int skipped;

    public static void main(String[] args) throws Exception {
        new ControlledSkillChangeCliTests().runAll();
    }

    private void runAll() throws Exception {
        run("real preview is read-only and returns an approval", this::previewIsReadOnly);
        run("real diff export binds the approval token", this::diffExport);
        run("approved existing Skill applies and rolls back", this::applyAndRollback);
        run("wrong or stale approval never writes", this::approvalGuards);
        run("external edit blocks rollback", this::rollbackGuard);
        run("same-byte target replacement blocks rollback", this::rollbackIdentityGuard);
        run("missing targets and in-workspace state are blocked", this::scopeGuards);
        run("non-canonical existing Skill text is blocked", this::textGuards);
        run("linked existing target is blocked", this::linkedTarget);
        System.out.printf("Controlled Skill change CLI tests: %d passed, %d skipped%n",
                passed, skipped);
    }

    private void previewIsReadOnly() throws Exception {
        withFixture(fixture -> {
            byte[] before = Files.readAllBytes(fixture.target());
            CliResult result = fixture.run(new String[] {
                    "skill-change-preview", "codex", fixture.workspace().toString()
            }, request());
            equal(0, result.code(), "exit");
            contains(result.output(), "\"status\": \"READY_REPLACE\"");
            contains(result.output(), "\"approvalToken\": \"acw_apply1_");
            contains(result.output(), "\"writesPerformed\": false");
            check(java.util.Arrays.equals(before, Files.readAllBytes(fixture.target())),
                    "preview changed target");
            equal(0L, Files.list(fixture.state()).count(), "preview changed state");
        });
    }

    private void diffExport() throws Exception {
        withFixture(fixture -> {
            CliResult result = fixture.run(new String[] {
                    "skill-change-preview", "codex", fixture.workspace().toString(),
                    "--export", "diff"
            }, request());
            equal(0, result.code(), "exit");
            contains(result.output(), "# approvalToken=acw_apply1_");
            contains(result.output(), "diffMode=REAL_TARGET_EXACT_REPLACEMENT");
            contains(result.output(), "-old skill body");
            contains(result.output(), "+# review-api-change");
        });
    }

    private void applyAndRollback() throws Exception {
        withFixture(fixture -> {
            byte[] original = Files.readAllBytes(fixture.target());
            String token = approval(fixture.preview());
            CliResult applied = fixture.run(new String[] {
                    "skill-change-apply", "codex", fixture.workspace().toString(),
                    fixture.state().toString(), "--approve", token
            }, request());
            equal(0, applied.code(), "apply exit");
            contains(applied.output(), "\"status\": \"VERIFIED_APPLIED\"");
            contains(applied.output(), "\"rollbackAvailable\": true");
            String transactionId = capture(applied.output(),
                    "\\\"transactionId\\\": \\\"([0-9a-f-]{36})\\\"");
            check(!java.util.Arrays.equals(original, Files.readAllBytes(fixture.target())),
                    "apply did not replace target");

            CliResult rolledBack = fixture.run(new String[] {
                    "skill-change-rollback", "codex", fixture.workspace().toString(),
                    fixture.state().toString(), transactionId
            }, "");
            equal(0, rolledBack.code(), "rollback exit");
            contains(rolledBack.output(), "\"status\": \"ROLLED_BACK\"");
            check(java.util.Arrays.equals(original, Files.readAllBytes(fixture.target())),
                    "rollback did not restore original bytes");

            CliResult repeated = fixture.run(new String[] {
                    "skill-change-rollback", "codex", fixture.workspace().toString(),
                    fixture.state().toString(), transactionId
            }, "");
            equal(0, repeated.code(), "repeat exit");
            contains(repeated.output(), "\"status\": \"ALREADY_ROLLED_BACK\"");
        });
    }

    private void approvalGuards() throws Exception {
        withFixture(fixture -> {
            byte[] original = Files.readAllBytes(fixture.target());
            CliResult wrong = fixture.run(new String[] {
                    "skill-change-apply", "codex", fixture.workspace().toString(),
                    fixture.state().toString(), "--approve", "wrong"
            }, request());
            equal(3, wrong.code(), "wrong-token exit");
            contains(wrong.output(), "\"status\": \"APPROVAL_MISMATCH\"");
            check(java.util.Arrays.equals(original, Files.readAllBytes(fixture.target())),
                    "wrong token changed target");

            String token = approval(fixture.preview());
            Files.writeString(fixture.target(), "external edit\n", StandardCharsets.UTF_8);
            CliResult stale = fixture.run(new String[] {
                    "skill-change-apply", "codex", fixture.workspace().toString(),
                    fixture.state().toString(), "--approve", token
            }, request());
            equal(3, stale.code(), "stale exit");
            contains(stale.output(), "\"status\": \"APPROVAL_MISMATCH\"");
            equal("external edit\n", Files.readString(fixture.target()), "stale overwrite");
        });
    }

    private void rollbackGuard() throws Exception {
        withFixture(fixture -> {
            String token = approval(fixture.preview());
            CliResult applied = fixture.run(new String[] {
                    "skill-change-apply", "codex", fixture.workspace().toString(),
                    fixture.state().toString(), "--approve", token
            }, request());
            String tx = capture(applied.output(),
                    "\\\"transactionId\\\": \\\"([0-9a-f-]{36})\\\"");
            Files.writeString(fixture.target(), "later user edit\n", StandardCharsets.UTF_8);
            CliResult rollback = fixture.run(new String[] {
                    "skill-change-rollback", "codex", fixture.workspace().toString(),
                    fixture.state().toString(), tx
            }, "");
            equal(3, rollback.code(), "exit");
            contains(rollback.output(), "\"status\": \"CURRENT_TARGET_CHANGED\"");
            equal("later user edit\n", Files.readString(fixture.target()), "rollback overwrite");
        });
    }

    private void rollbackIdentityGuard() throws Exception {
        withFixture(fixture -> {
            String token = approval(fixture.preview());
            CliResult applied = fixture.run(new String[] {
                    "skill-change-apply", "codex", fixture.workspace().toString(),
                    fixture.state().toString(), "--approve", token
            }, request());
            String tx = capture(applied.output(),
                    "\\\"transactionId\\\": \\\"([0-9a-f-]{36})\\\"");
            byte[] candidate = Files.readAllBytes(fixture.target());
            Files.delete(fixture.target());
            Files.write(fixture.target(), candidate);
            CliResult rollback = fixture.run(new String[] {
                    "skill-change-rollback", "codex", fixture.workspace().toString(),
                    fixture.state().toString(), tx
            }, "");
            equal(3, rollback.code(), "exit");
            contains(rollback.output(), "\"status\": \"CURRENT_TARGET_CHANGED\"");
            check(java.util.Arrays.equals(candidate, Files.readAllBytes(fixture.target())),
                    "rollback changed same-byte replacement");
        });
    }

    private void textGuards() throws Exception {
        withFixture(fixture -> {
            Files.write(fixture.target(), new byte[] {(byte) 0xc3, (byte) 0x28, '\n'});
            CliResult malformed = fixture.preview();
            equal(3, malformed.code(), "malformed exit");
            contains(malformed.output(), "TARGET_MUST_BE_UTF8_LF_WITH_FINAL_NEWLINE");
        });
        withFixture(fixture -> {
            Files.writeString(fixture.target(), "no final newline", StandardCharsets.UTF_8);
            CliResult unterminated = fixture.preview();
            equal(3, unterminated.code(), "unterminated exit");
            contains(unterminated.output(), "TARGET_MUST_BE_UTF8_LF_WITH_FINAL_NEWLINE");
        });
    }

    private void scopeGuards() throws Exception {
        withFixture(fixture -> {
            Files.delete(fixture.target());
            CliResult missing = fixture.preview();
            equal(3, missing.code(), "missing exit");
            contains(missing.output(), "EXISTING_TARGET_REQUIRED");
        });
        withFixture(fixture -> {
            String token = approval(fixture.preview());
            Path inside = Files.createDirectory(fixture.workspace().resolve("state"));
            CliResult result = fixture.run(new String[] {
                    "skill-change-apply", "codex", fixture.workspace().toString(),
                    inside.toString(), "--approve", token
            }, request());
            equal(3, result.code(), "inside-state exit");
            contains(result.output(), "STATE_ROOT_INVALID_OR_NOT_SEPARATE");
        });
    }

    private void linkedTarget() throws Exception {
        withFixture(fixture -> {
            Path outside = fixture.base().resolve("outside.md");
            Files.writeString(outside, "outside secret\n", StandardCharsets.UTF_8);
            Files.delete(fixture.target());
            try {
                Files.createSymbolicLink(fixture.target(), outside);
            } catch (IOException | UnsupportedOperationException | SecurityException exception) {
                skip("symbolic links unavailable");
                return;
            }
            CliResult result = fixture.preview();
            equal(3, result.code(), "exit");
            contains(result.output(), "TARGET_LINK_OR_REPARSE");
            equal("outside secret\n", Files.readString(outside), "linked destination changed");
        });
    }

    private void withFixture(ThrowingConsumer<Fixture> test) throws Exception {
        Path base = Files.createTempDirectory("acw-controlled-cli-");
        try {
            Path workspace = Files.createDirectory(base.resolve("workspace"));
            Path state = Files.createDirectory(base.resolve("state"));
            Path target = Files.createDirectories(
                    workspace.resolve(".agents/skills/review-api-change")).resolve("SKILL.md");
            Files.writeString(target, "old skill body\n", StandardCharsets.UTF_8);
            test.accept(new Fixture(base, workspace, state, target));
        } finally {
            deleteOwned(base);
        }
    }

    private static String request() {
        return "repeated-workflow: true\nclear-trigger: true\nsuccess-criteria: true\n"
                + "confirmed-artifact: skill\nconfirmed-scope: project\n"
                + "name: review-api-change\ndescription: Review API changes.\n"
                + "goal: Produce a bounded review.\ninput: Changed files\noutput: Findings\n"
                + "trigger: Use for API changes.\nexclusion: Do not use for UI changes.\n"
                + "boundary-example: A CSS edit is outside scope.\n"
                + "should-trigger: Review endpoint\nshould-trigger: Review schema\n"
                + "should-trigger: Review migration\nshould-not-trigger: Review CSS\n"
                + "should-not-trigger: Draft marketing\nshould-not-trigger: Rename image\n"
                + "step: Inspect contracts\ncompletion: Every contract has a result.\n"
                + "validation: Confirm findings cite inputs.\npermission: NONE\nrisk: LOW\n";
    }

    private static String approval(CliResult result) {
        equal(0, result.code(), "preview exit");
        return capture(result.output(), "\\\"approvalToken\\\": \\\"(acw_apply1_[0-9a-f]{64})\\\"");
    }

    private static String capture(String text, String expression) {
        Matcher matcher = Pattern.compile(expression).matcher(text);
        if (!matcher.find()) throw new AssertionError("missing pattern " + expression + " in " + text);
        return matcher.group(1);
    }

    private static void deleteOwned(Path root) throws IOException {
        if (!root.getFileName().toString().startsWith("acw-controlled-cli-")
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("unsafe cleanup root");
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record Fixture(Path base, Path workspace, Path state, Path target) {
        CliResult preview() {
            return run(new String[] {"skill-change-preview", "codex", workspace.toString()}, request());
        }
        CliResult run(String[] args, String input) {
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            int code = Cli.phaseOneDefaults().run(args,
                    new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                    new PrintWriter(stdout, true, StandardCharsets.UTF_8),
                    new PrintWriter(stderr, true, StandardCharsets.UTF_8));
            return new CliResult(code, stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8));
        }
    }

    private record CliResult(int code, String output, String error) {}
    @FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }
    @FunctionalInterface private interface ThrowingConsumer<T> { void accept(T value) throws Exception; }

    private void run(String name, ThrowingRunnable test) throws Exception {
        test.run();
        passed++;
        System.out.println("PASS " + name);
    }
    private void skip(String reason) { skipped++; System.out.println("SKIP " + reason); }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void contains(String value, String expected) {
        if (!value.contains(expected)) throw new AssertionError("missing " + expected + " in " + value);
    }
    private static void equal(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }
}
