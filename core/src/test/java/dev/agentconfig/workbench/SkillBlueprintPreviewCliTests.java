package dev.agentconfig.workbench;

import dev.agentconfig.workbench.cli.Cli;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SkillBlueprintPreviewCliTests {
    private int passed;

    public static void main(String[] args) throws Exception {
        new SkillBlueprintPreviewCliTests().runAll();
    }

    private void runAll() throws Exception {
        run("one-shot guided signal produces confirmed prompt triage", this::oneShotPrompt);
        run("unknown guided intent remains unconfirmed", this::unknownIntent);
        run("unconfirmed Skill recommendation has no blueprint", this::unconfirmedSkill);
        run("complete confirmed Skill produces Blueprint v1", this::completeSkill);
        run("incomplete Skill reports missing fields without blueprint", this::incompleteSkill);
        run("executable automation blocks blueprint generation", this::executableBlocked);
        run("unsafe supporting path is rejected", this::unsafeSupportingPath);
        run("preview is deterministic and performs zero writes", this::deterministicZeroWrite);
        run("malformed UTF-8 is rejected without a report", this::malformedUtf8);
        run("persistent guidance produces Instruction", this::instructionTriage);
        run("isolated responsibility produces Agent", this::agentTriage);
        run("deterministic enforcement produces Tool Policy", this::toolPolicyTriage);
        run("conflicting guided signals remain unknown", this::conflictingSignals);
        run("unsupported scope cannot be confirmed", this::unsupportedScope);
        run("high permission intent cannot claim low risk", this::permissionRisk);
        run("unknown field does not leak its key", this::unknownFieldRedaction);
        run("bounded stdin rejects oversized request", this::oversizedInput);
        run("material Blueprint change changes stable ID", this::materialChangeChangesId);
        run("trigger and exclusion overlap blocks Blueprint", this::triggerExclusionOverlap);
        run("Windows reserved supporting path is rejected", this::windowsReservedPath);
        System.out.printf("Skill blueprint preview CLI tests: %d passed%n", passed);
    }

    private void oneShotPrompt() throws Exception {
        withTempDirectory(root -> {
            write(root, "request.intent", "duration: one-shot\n"
                    + "confirmed-artifact: prompt\nconfirmed-scope: session\n");
            Invocation result = invoke(root, "request.intent");
            equal(0, result.exitCode(), "exit");
            check(result.stdout().contains("\"recommendedArtifact\": \"PROMPT\""),
                    "prompt recommendation missing");
            check(result.stdout().contains("\"status\": \"TRIAGE_READY\""),
                    "triage status missing");
            check(result.stdout().contains("\"blueprint\": null"),
                    "non-Skill produced a blueprint");
        });
    }

    private void unknownIntent() throws Exception {
        withTempDirectory(root -> {
            write(root, "request.intent", "goal: Review code safely\n");
            Invocation result = invoke(root, "request.intent");
            equal(3, result.exitCode(), "exit");
            check(result.stdout().contains("\"recommendedArtifact\": \"UNKNOWN\""),
                    "unknown recommendation missing");
            check(result.stdout().contains("INSUFFICIENT_GUIDED_SIGNALS"),
                    "evidence code missing");
        });
    }

    private void unconfirmedSkill() throws Exception {
        withTempDirectory(root -> {
            write(root, "request.intent", skillSignals());
            Invocation result = invoke(root, "request.intent");
            equal(3, result.exitCode(), "exit");
            check(result.stdout().contains("\"recommendedArtifact\": \"SKILL\""),
                    "Skill recommendation missing");
            check(result.stdout().contains("\"status\": \"NEEDS_CONFIRMATION\""),
                    "confirmation status missing");
            check(result.stdout().contains("\"blueprint\": null"),
                    "unconfirmed Skill produced a blueprint");
        });
    }

    private void completeSkill() throws Exception {
        withTempDirectory(root -> {
            String commentSecret = "raw-comment-secret-771";
            write(root, "request.intent", "# " + commentSecret + "\n" + completeSkillRequest());
            Invocation result = invoke(root, "request.intent");
            equal(0, result.exitCode(), "exit");
            check(result.stdout().contains("\"status\": \"BLUEPRINT_READY\""),
                    "ready status missing");
            check(result.stdout().contains("\"schemaVersion\": 1"), "schema missing");
            check(result.stdout().contains("\"name\": \"review-api-change\""),
                    "blueprint content missing");
            check(result.stdout().contains("\"workspaceContentIncluded\": false"),
                    "workspace content boundary missing");
            check(result.stdout().contains("\"userProvidedContentIncluded\": true"),
                    "user content boundary missing");
            check(result.stdout().contains("\"rawRequestIncluded\": false"),
                    "raw request boundary missing");
            check(result.stdout().contains("\"llmUsed\": false"), "LLM flag missing");
            check(result.stdout().contains("\"writesPerformed\": false"), "write flag missing");
            check(result.stdout().contains("\"applyEligible\": false"), "apply flag missing");
            check(!result.stdout().contains(commentSecret), "raw comment leaked");
        });
    }

    private void incompleteSkill() throws Exception {
        withTempDirectory(root -> {
            String request = completeSkillRequest()
                    .replace("should-trigger: Review a backend endpoint\n", "")
                    .replace("should-trigger: Review an API migration\n", "");
            write(root, "request.intent", request);
            Invocation result = invoke(root, "request.intent");
            equal(3, result.exitCode(), "exit");
            check(result.stdout().contains("\"status\": \"INCOMPLETE\""),
                    "incomplete status missing");
            check(result.stdout().contains("should-trigger[3]"), "missing field code absent");
            check(result.stdout().contains("\"blueprint\": null"),
                    "incomplete request produced a blueprint");
        });
    }

    private void executableBlocked() throws Exception {
        withTempDirectory(root -> {
            String request = completeSkillRequest() + "executable-automation: true\n";
            write(root, "request.intent", request);
            Invocation result = invoke(root, "request.intent");
            equal(3, result.exitCode(), "exit");
            check(result.stdout().contains("HIGH_RISK_EXECUTABLE_PROPOSAL"),
                    "high-risk recommendation missing");
            check(result.stdout().contains("\"status\": \"BLOCKED\""),
                    "blocked status missing");
            check(result.stdout().contains("\"blueprint\": null"),
                    "executable request produced a blueprint");
        });
    }

    private void unsafeSupportingPath() throws Exception {
        withTempDirectory(root -> {
            write(root, "request.intent", completeSkillRequest()
                    .replace("supporting-file: references/checklist.md",
                            "supporting-file: ../outside.md"));
            Invocation result = invoke(root, "request.intent");
            equal(2, result.exitCode(), "exit");
            check(result.stdout().isEmpty(), "invalid request emitted a preview");
            check(result.stderr().contains("portable package-relative path"),
                    "safe-path explanation missing");
        });
    }

    private void deterministicZeroWrite() throws Exception {
        withTempDirectory(root -> {
            Path request = write(root, "request.intent", completeSkillRequest());
            byte[] before = Files.readAllBytes(request);
            var modified = Files.getLastModifiedTime(request);
            Invocation first = invoke(root, "request.intent");
            Invocation second = invoke(root, "request.intent");
            equal(0, first.exitCode(), "first exit");
            equal(first.stdout(), second.stdout(), "deterministic output");
            check(java.util.Arrays.equals(before, Files.readAllBytes(request)),
                    "request bytes changed");
            equal(modified, Files.getLastModifiedTime(request), "request mtime");
            equal(List.of("request.intent"), listRelative(root), "workspace entries");
        });
    }

    private void malformedUtf8() throws Exception {
        withTempDirectory(root -> {
            Files.write(root.resolve("request.intent"), new byte[] {(byte) 0xc3, (byte) 0x28});
            Invocation result = invoke(root, "request.intent");
            equal(2, result.exitCode(), "exit");
            check(result.stdout().isEmpty(), "malformed request emitted a report");
            check(result.stderr().contains("strict UTF-8"), "encoding explanation missing");
        });
    }

    private void instructionTriage() {
        Invocation result = invokeText("duration: persistent\n"
                + "confirmed-artifact: instruction\nconfirmed-scope: project\n");
        equal(0, result.exitCode(), "exit");
        check(result.stdout().contains("\"recommendedArtifact\": \"INSTRUCTION\""),
                "instruction recommendation missing");
    }

    private void agentTriage() {
        Invocation result = invokeText("isolated-context: true\n"
                + "confirmed-artifact: agent\nconfirmed-scope: project\n");
        equal(0, result.exitCode(), "exit");
        check(result.stdout().contains("\"recommendedArtifact\": \"AGENT\""),
                "agent recommendation missing");
    }

    private void toolPolicyTriage() {
        Invocation result = invokeText("deterministic-enforcement: true\n"
                + "confirmed-artifact: tool-policy\nconfirmed-scope: project\n");
        equal(0, result.exitCode(), "exit");
        check(result.stdout().contains("DETERMINISTIC_TOOL_POLICY"),
                "tool-policy recommendation missing");
    }

    private void conflictingSignals() {
        Invocation result = invokeText(skillSignals() + "duration: one-shot\n"
                + "confirmed-artifact: skill\nconfirmed-scope: project\n");
        equal(3, result.exitCode(), "exit");
        check(result.stdout().contains("\"recommendedArtifact\": \"UNKNOWN\""),
                "conflict was hard-classified");
        check(result.stdout().contains("CONFLICTING_GUIDED_SIGNALS"),
                "conflict evidence missing");
    }

    private void unsupportedScope() {
        Invocation result = invokeText("duration: one-shot\n"
                + "confirmed-artifact: prompt\nconfirmed-scope: bananas\n");
        equal(3, result.exitCode(), "exit");
        check(result.stdout().contains("UNSUPPORTED_SCOPE"), "scope finding missing");
        check(result.stdout().contains("\"status\": \"NEEDS_CONFIRMATION\""),
                "invalid scope became ready");
    }

    private void permissionRisk() {
        Invocation result = invokeText(completeSkillRequest()
                .replace("permission: NONE\nrisk: LOW", "permission: SHELL\nrisk: LOW"));
        equal(3, result.exitCode(), "exit");
        check(result.stdout().contains("risk=HIGH-for-write-network-shell-or-script-intent"),
                "permission risk finding missing");
        check(result.stdout().contains("\"blueprint\": null"),
                "unsafe low-risk Blueprint was produced");
    }

    private void unknownFieldRedaction() {
        String secretKey = "secret-token-unknown-key-887";
        Invocation result = invokeText(secretKey + ": value\n");
        equal(2, result.exitCode(), "exit");
        check(!result.stderr().contains(secretKey), "unknown key leaked to stderr");
        check(result.stderr().contains("line 1"), "safe line evidence missing");
    }

    private void oversizedInput() {
        byte[] bytes = new byte[(32 * 1024) + 1];
        java.util.Arrays.fill(bytes, (byte) 'x');
        Invocation result = invoke(bytes);
        equal(2, result.exitCode(), "exit");
        check(result.stdout().isEmpty(), "oversized input emitted a report");
        check(result.stderr().contains("32768-byte limit"), "budget message missing");
    }

    private void materialChangeChangesId() {
        Invocation first = invokeText(completeSkillRequest());
        Invocation second = invokeText(completeSkillRequest().replace(
                "Confirm every finding cites an input file.",
                "Confirm every result cites an input file."));
        equal(0, first.exitCode(), "first exit");
        equal(0, second.exitCode(), "second exit");
        check(!firstId(first.stdout()).equals(firstId(second.stdout())),
                "material validation change did not change preview ID");
    }

    private void triggerExclusionOverlap() {
        Invocation result = invokeText(completeSkillRequest().replace(
                "Do not use for UI-only changes.", "Use when an API contract changes."));
        equal(3, result.exitCode(), "exit");
        check(result.stdout().contains("trigger-exclusion=non-overlapping"),
                "boundary overlap was not blocked");
    }

    private void windowsReservedPath() {
        Invocation result = invokeText(completeSkillRequest().replace(
                "references/checklist.md", "references/NUL.txt"));
        equal(2, result.exitCode(), "exit");
        check(result.stdout().isEmpty(), "reserved path emitted a Blueprint");
    }

    private static String skillSignals() {
        return "repeated-workflow: true\nclear-trigger: true\nsuccess-criteria: true\n";
    }

    private static String completeSkillRequest() {
        return skillSignals()
                + "confirmed-artifact: skill\nconfirmed-scope: project\n"
                + "name: review-api-change\n"
                + "description: Review API changes when backend contracts are modified.\n"
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
                + "permission: NONE\nrisk: LOW\n"
                + "supporting-file: references/checklist.md\n";
    }

    private static Path write(Path root, String relative, String text) throws IOException {
        Path path = root.resolve(relative);
        Files.writeString(path, text, StandardCharsets.UTF_8);
        return path;
    }

    private static Invocation invoke(Path root, String request) {
        try {
            return invoke(Files.readAllBytes(root.resolve(request)));
        } catch (IOException exception) {
            throw new IllegalStateException("test request could not be read", exception);
        }
    }

    private static Invocation invoke(byte[] request) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = Cli.phaseOneDefaults().run(
                new String[] {"skill-blueprint-preview", "codex"},
                new ByteArrayInputStream(request),
                new PrintWriter(stdout, true, StandardCharsets.UTF_8),
                new PrintWriter(stderr, true, StandardCharsets.UTF_8));
        return new Invocation(exit, stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private static Invocation invokeText(String request) {
        return invoke(request.getBytes(StandardCharsets.UTF_8));
    }

    private static String firstId(String json) {
        String prefix = "\"id\": \"";
        int start = json.indexOf(prefix);
        if (start < 0) {
            throw new AssertionError("preview id missing");
        }
        start += prefix.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }

    private static List<String> listRelative(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> !path.equals(root))
                    .map(root::relativize).map(Path::toString).sorted().toList();
        }
    }

    private void withTempDirectory(ThrowingConsumer<Path> test) throws Exception {
        Path root = Files.createTempDirectory("acw-blueprint-cli-");
        try {
            test.accept(root);
        } finally {
            deleteOwnedTempTree(root);
        }
    }

    private static void deleteOwnedTempTree(Path root) throws IOException {
        if (!root.getFileName().toString().startsWith("acw-blueprint-cli-")
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing to clean unexpected test path: " + root);
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

    private static void equal(Object expected, Object actual, String name) {
        if (!expected.equals(actual)) {
            throw new AssertionError(name + ": expected " + expected + " but got " + actual);
        }
    }

    private record Invocation(int exitCode, String stdout, String stderr) {}

    @FunctionalInterface
    private interface ThrowingRunnable { void run() throws Exception; }

    @FunctionalInterface
    private interface ThrowingConsumer<T> { void accept(T value) throws Exception; }
}
