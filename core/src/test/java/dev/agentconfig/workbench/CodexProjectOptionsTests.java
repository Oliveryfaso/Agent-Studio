package dev.agentconfig.workbench;

import dev.agentconfig.workbench.context.codex.CodexProjectOptions;
import dev.agentconfig.workbench.context.codex.CodexProjectOptionsDiagnosticCode;
import dev.agentconfig.workbench.context.codex.CodexProjectOptionsParseResult;
import dev.agentconfig.workbench.context.codex.CodexProjectOptionsParser;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class CodexProjectOptionsTests {
    private int passed;

    public static void main(String[] args) {
        new CodexProjectOptionsTests().runAll();
    }

    private void runAll() {
        run("defaults and unknown keys", this::defaultsAndUnknownKeys);
        run("valid values and TOML strings", this::validValuesAndStrings);
        run("multiline arrays and comments", this::multilineArraysAndComments);
        run("quoted target keys", this::quotedTargetKeys);
        run("table-scoped lookalikes are ignored", this::tableScopedLookalikesIgnored);
        run("invalid target values are diagnosed", this::invalidTargetValues);
        run("duplicate target keys are diagnosed", this::duplicateTargetKeys);
        run("oversized and malformed UTF-8 snapshots are rejected", this::boundedUtf8Input);
        run("diagnostics do not leak source values", this::diagnosticsAreRedacted);
        System.out.printf("Codex project options tests: %d passed%n", passed);
    }

    private void defaultsAndUnknownKeys() {
        CodexProjectOptionsParseResult result = parser().parse("model = \"example\"\n");
        check(result.valid(), "unknown key should be ignored");
        equal(List.of(), result.options().fallbackFilenames(), "default fallbacks");
        equal(CodexProjectOptions.DEFAULT_MAX_BYTES, result.options().maxBytes(), "default bytes");
    }

    private void validValuesAndStrings() {
        CodexProjectOptionsParseResult result = parser().parse("""
                project_doc_fallback_filenames = ["TEAM_GUIDE.md", '.agents.md', "nested\\tname"]
                project_doc_max_bytes = +65_536
                """);
        check(result.valid(), "valid configuration rejected");
        equal(List.of("TEAM_GUIDE.md", ".agents.md", "nested\tname"),
                result.options().fallbackFilenames(), "fallback values");
        equal(65_536L, result.options().maxBytes(), "max bytes");
    }

    private void multilineArraysAndComments() {
        CodexProjectOptionsParseResult result = parser().parse("""
                project_doc_fallback_filenames = [
                  "TEAM#GUIDE.md", # comment
                  ".agents.md",
                ]
                project_doc_max_bytes = 40000 # comment
                """);
        check(result.valid(), "multiline array rejected");
        equal(List.of("TEAM#GUIDE.md", ".agents.md"),
                result.options().fallbackFilenames(), "multiline values");
        equal(40_000L, result.options().maxBytes(), "commented integer");
    }

    private void quotedTargetKeys() {
        CodexProjectOptionsParseResult result = parser().parse("""
                "project_doc_fallback_filenames" = ["GUIDE.md"]
                'project_doc_max_bytes' = 40000
                """);
        check(result.valid(), "quoted keys rejected");
        equal(List.of("GUIDE.md"), result.options().fallbackFilenames(), "quoted fallback key");
        equal(40_000L, result.options().maxBytes(), "quoted max key");
    }

    private void tableScopedLookalikesIgnored() {
        CodexProjectOptionsParseResult result = parser().parse("""
                [profile.demo]
                project_doc_fallback_filenames = false
                project_doc_max_bytes = -1
                """);
        check(result.valid(), "non-root keys should be ignored");
        equal(CodexProjectOptions.defaults(), result.options(), "table values changed defaults");
    }

    private void invalidTargetValues() {
        CodexProjectOptionsParseResult result = parser().parse("""
                project_doc_fallback_filenames = ["ok", 42]
                project_doc_max_bytes = 1__000
                """);
        check(!result.valid(), "invalid values accepted");
        equal(2, result.diagnostics().size(), "diagnostic count");
        check(result.diagnostics().stream().allMatch(diagnostic ->
                        diagnostic.code() == CodexProjectOptionsDiagnosticCode.INVALID_TARGET_VALUE),
                "wrong diagnostic code");
        equal(CodexProjectOptions.defaults(), result.options(), "invalid values changed defaults");
    }

    private void duplicateTargetKeys() {
        CodexProjectOptionsParseResult result = parser().parse("""
                project_doc_max_bytes = 40000
                "project_doc_max_bytes" = 50000
                project_doc_fallback_filenames = ["FIRST.md"]
                project_doc_fallback_filenames = ["SECOND.md"]
                """);
        check(!result.valid(), "duplicate accepted");
        equal(40_000L, result.options().maxBytes(), "first value should win");
        equal(List.of("FIRST.md"), result.options().fallbackFilenames(), "first list should win");
        equal(2, result.diagnostics().size(), "duplicate diagnostic count");
        equal(CodexProjectOptionsDiagnosticCode.DUPLICATE_TARGET_KEY,
                result.diagnostics().getFirst().code(), "duplicate code");
        equal(2, result.diagnostics().getFirst().line(), "duplicate line");
    }

    private void boundedUtf8Input() {
        byte[] oversized = new byte[CodexProjectOptionsParser.MAX_SNAPSHOT_BYTES + 1];
        CodexProjectOptionsParseResult tooLarge = parser().parse(oversized);
        equal(CodexProjectOptionsDiagnosticCode.SNAPSHOT_TOO_LARGE,
                tooLarge.diagnostics().getFirst().code(), "size code");

        CodexProjectOptionsParseResult malformed = parser().parse(new byte[] {(byte) 0xC3, 0x28});
        equal(CodexProjectOptionsDiagnosticCode.INVALID_UTF8,
                malformed.diagnostics().getFirst().code(), "UTF-8 code");

        byte[] exactLimit = new byte[CodexProjectOptionsParser.MAX_SNAPSHOT_BYTES];
        CodexProjectOptionsParseResult accepted = parser().parse(exactLimit);
        check(accepted.valid(), "exactly 1 MiB should be accepted");
    }

    private void diagnosticsAreRedacted() {
        String secret = "do-not-repeat-this-value";
        CodexProjectOptionsParseResult result = parser().parse(
                "project_doc_max_bytes = \"" + secret + "\"");
        check(!result.valid(), "invalid secret value accepted");
        String rendered = result.diagnostics().toString();
        check(!rendered.contains(secret), "diagnostic leaked source value");
        check(!rendered.contains("project_doc_max_bytes ="), "diagnostic leaked source line");
    }

    private static CodexProjectOptionsParser parser() {
        return new CodexProjectOptionsParser();
    }

    private void run(String name, Runnable test) {
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
}
