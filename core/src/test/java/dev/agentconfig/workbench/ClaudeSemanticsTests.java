package dev.agentconfig.workbench;

import dev.agentconfig.workbench.context.claude.ClaudeDiagnostic;
import dev.agentconfig.workbench.context.claude.ClaudeImportParser;
import dev.agentconfig.workbench.context.claude.ClaudeImportScan;
import dev.agentconfig.workbench.context.claude.ClaudeRuleApplicability;
import dev.agentconfig.workbench.context.claude.ClaudeRuleDefinition;
import dev.agentconfig.workbench.context.claude.ClaudeRuleEvaluation;
import dev.agentconfig.workbench.context.claude.ClaudeRuleEvaluator;
import java.util.List;
import java.util.Objects;

public final class ClaudeSemanticsTests {
    private int passed;

    public static void main(String[] args) throws Exception {
        new ClaudeSemanticsTests().runAll();
    }

    private void runAll() throws Exception {
        run("imports are found anywhere in prose", this::importsAreFoundInProse);
        run("code spans and fences suppress imports", this::codeSuppressesImports);
        run("non-relative imports are diagnosed", this::nonRelativeImportsAreDiagnosed);
        run("import parsing is bounded", this::importParsingIsBounded);
        run("multiline paths frontmatter is parsed", this::multilinePathsAreParsed);
        run("inline paths and brace globs match", this::inlinePathsAndBraceGlobsMatch);
        run("rules without paths always apply", this::rulesWithoutPathsAlwaysApply);
        run("conditional no-match is explicit", this::conditionalNoMatchIsExplicit);
        run("invalid inputs produce INVALID", this::invalidInputsProduceInvalid);
        run("invalid glob does not poison valid siblings", this::invalidGlobDoesNotPoisonSiblings);
        System.out.printf("Claude semantics tests: %d passed%n", passed);
    }

    private void importsAreFoundInProse() {
        ClaudeImportScan scan = new ClaudeImportParser().parse(
                "See @README.md for context and follow @docs/team-rules.md today.\n"
                        + "- workflow @../shared/workflow.md\n"
                        + "mail person@example.com\n");
        equal(List.of("README.md", "docs/team-rules.md", "../shared/workflow.md"),
                scan.imports().stream().map(value -> value.rawPath()).toList(), "imports");
        equal(4, scan.maximumRecursiveHops(), "official hop limit");
        equal(1, scan.imports().get(0).line(), "first line");
        equal(5, scan.imports().get(0).column(), "first column");
    }

    private void codeSuppressesImports() {
        String markdown = "Use `@literal.md` but @active.md.\n"
                + "```md\n@inside-fence.md\n```not-a-close\n@still-inside.md\n```\n"
                + "~~~\n@inside-tilde.md\n~~~\n"
                + "After @last.md\n";
        ClaudeImportScan scan = new ClaudeImportParser().parse(markdown);
        equal(List.of("active.md", "last.md"),
                scan.imports().stream().map(value -> value.rawPath()).toList(), "active imports");
    }

    private void nonRelativeImportsAreDiagnosed() {
        ClaudeImportScan scan = new ClaudeImportParser().parse(
                "@/etc/policy.md @~/personal.md @C:\\rules\\global.md @local.md");
        equal(List.of("local.md"),
                scan.imports().stream().map(value -> value.rawPath()).toList(), "relative only");
        equal(3L, diagnostics(scan.diagnostics(), "UNSUPPORTED_NON_RELATIVE_IMPORT"),
                "unsupported diagnostics");
    }

    private void importParsingIsBounded() {
        ClaudeImportScan truncated = new ClaudeImportParser(10, 10).parse("@one.md plus @two.md");
        check(truncated.inputTruncated(), "truncation flag");
        equal(1L, diagnostics(truncated.diagnostics(), "IMPORT_INPUT_TRUNCATED"),
                "truncation diagnostic");

        ClaudeImportScan limited = new ClaudeImportParser(1_000, 1).parse("@one.md @two.md");
        equal(1, limited.imports().size(), "import count");
        equal(1L, diagnostics(limited.diagnostics(), "IMPORT_COUNT_LIMIT"),
                "count diagnostic");
    }

    private void multilinePathsAreParsed() {
        String rule = "---\npaths:\n  - \"src/api/**/*.ts\"\n  - 'tests/**/*.test.ts'\n---\n# API";
        ClaudeRuleEvaluator evaluator = new ClaudeRuleEvaluator();
        ClaudeRuleDefinition definition = evaluator.parse(rule);
        check(definition.valid(), "definition valid");
        equal(List.of("src/api/**/*.ts", "tests/**/*.test.ts"), definition.paths(), "paths");
        equal(ClaudeRuleApplicability.CONDITIONAL_MATCH,
                evaluator.evaluate(definition, "src/api/users/get.ts").applicability(), "match");
    }

    private void inlinePathsAndBraceGlobsMatch() {
        String rule = "---\npaths: [\"src/**/*.{ts,tsx}\", \"*.md\"]\n---\nbody";
        ClaudeRuleEvaluator evaluator = new ClaudeRuleEvaluator();
        equal(ClaudeRuleApplicability.CONDITIONAL_MATCH,
                evaluator.evaluate(rule, "src/components/Card.tsx").applicability(), "brace match");
        equal(ClaudeRuleApplicability.CONDITIONAL_MATCH,
                evaluator.evaluate(rule, "README.md").applicability(), "root match");
        equal(ClaudeRuleApplicability.CONDITIONAL_NO_MATCH,
                evaluator.evaluate(rule, "docs/README.md").applicability(), "root-only glob");
    }

    private void rulesWithoutPathsAlwaysApply() {
        ClaudeRuleEvaluation result = new ClaudeRuleEvaluator().evaluate(
                "---\ndescription: always\n---\nbody", "any/depth/File.java");
        equal(ClaudeRuleApplicability.ALWAYS, result.applicability(), "always");
    }

    private void conditionalNoMatchIsExplicit() {
        ClaudeRuleEvaluation result = new ClaudeRuleEvaluator().evaluate(
                "---\npaths:\n  - \"src/**/*.java\"\n---\nbody", "docs/guide.md");
        equal(ClaudeRuleApplicability.CONDITIONAL_NO_MATCH, result.applicability(), "no match");
    }

    private void invalidInputsProduceInvalid() {
        ClaudeRuleEvaluator evaluator = new ClaudeRuleEvaluator();
        ClaudeRuleEvaluation malformed = evaluator.evaluate("---\npaths: nope\n", "src/Main.java");
        equal(ClaudeRuleApplicability.INVALID, malformed.applicability(), "malformed frontmatter");
        check(!malformed.diagnostics().isEmpty(), "malformed diagnostic");

        ClaudeRuleEvaluation traversal = evaluator.evaluate(
                "---\npaths: [\"**/*.java\"]\n---", "../outside.java");
        equal(ClaudeRuleApplicability.INVALID, traversal.applicability(), "target traversal");
        equal(1L, diagnostics(traversal.diagnostics(), "INVALID_TARGET_PATH"),
                "target diagnostic");
    }

    private void invalidGlobDoesNotPoisonSiblings() {
        String rule = "---\npaths: [\"photos [2024/**\", \"src/**/*.java\"]\n---";
        ClaudeRuleEvaluation result = new ClaudeRuleEvaluator().evaluate(rule, "src/Main.java");
        equal(ClaudeRuleApplicability.CONDITIONAL_MATCH, result.applicability(), "sibling match");
        equal(1L, diagnostics(result.diagnostics(), "INVALID_GLOB"), "invalid glob diagnostic");
    }

    private static long diagnostics(List<ClaudeDiagnostic> diagnostics, String code) {
        return diagnostics.stream().filter(value -> value.code().equals(code)).count();
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
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
