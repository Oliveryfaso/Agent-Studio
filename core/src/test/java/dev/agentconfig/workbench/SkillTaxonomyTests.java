package dev.agentconfig.workbench;

import dev.agentconfig.workbench.skill.SkillTaxonomyReport.Category;
import dev.agentconfig.workbench.skill.SkillTaxonomyReport.Confidence;
import dev.agentconfig.workbench.skill.SkillTaxonomyService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SkillTaxonomyTests {
    private static int passed;

    private SkillTaxonomyTests() {}

    public static void main(String[] arguments) throws Exception {
        run("nine maintained categories classify with explicit phrases",
                SkillTaxonomyTests::nineCategories);
        run("generic and ambiguous descriptions require human review",
                SkillTaxonomyTests::reviewFallback);
        run("invalid and negated Skills remain in human review",
                SkillTaxonomyTests::invalidAndNegated);
        run("classification preserves inventory cardinality beyond sixty four",
                SkillTaxonomyTests::cardinality);
        run("frontmatter classification accepts BOM and CRLF",
                SkillTaxonomyTests::bomAndCrLf);
        run("classification is deterministic content-free and read-only",
                SkillTaxonomyTests::readOnly);
        System.out.println("Skill taxonomy tests: " + passed + " passed");
    }

    private static void nineCategories() throws Exception {
        withWorkspace(root -> {
            Map<String, Category> expected = new LinkedHashMap<>();
            expected.put("api-reference", Category.LIBRARY_API_REFERENCE);
            expected.put("end-to-end-test", Category.PRODUCT_VALIDATION);
            expected.put("sql-query", Category.DATA_QUERY_ANALYSIS);
            expected.put("business-workflow-automation", Category.BUSINESS_WORKFLOW_AUTOMATION);
            expected.put("project-template", Category.CODE_SCAFFOLDING);
            expected.put("code-review", Category.CODE_QUALITY_REVIEW);
            expected.put("ci-pipeline", Category.CI_CD_DEPLOYMENT);
            expected.put("incident-response", Category.RUNBOOK_TROUBLESHOOTING);
            expected.put("cluster-maintenance", Category.INFRASTRUCTURE_OPERATIONS);
            for (String name : expected.keySet()) writeSkill(root, name, name.replace('-', ' '));
            var report = new SkillTaxonomyService().classify(root);
            check(report.categories().equals(Category.buckets()), "category order");
            check(report.skills().size() == 9, "skill count");
            for (var skill : report.skills()) {
                check(skill.category() == expected.get(skill.name()), "wrong category " + skill.name());
                check(skill.confidence() == Confidence.HIGH, "not high " + skill.name());
                check(skill.sourceSha256().matches("[0-9a-f]{64}"), "missing source binding");
            }
        });
    }

    private static void reviewFallback() throws Exception {
        withWorkspace(root -> {
            writeSkill(root, "review-helper", "Review API changes safely");
            writeSkill(root, "release-incident", "Release pipeline and incident response");
            var report = new SkillTaxonomyService().classify(root);
            check(report.unclassifiedCount() == 2, "fallback count");
            check(report.skills().stream().allMatch(skill -> skill.category() == null
                    && skill.confidence() == Confidence.UNCLASSIFIED), "forced classification");
        });
    }

    private static void readOnly() throws Exception {
        withWorkspace(root -> {
            writeSkill(root, "code-review", "Code review for project changes");
            Map<String, String> before = snapshot(root);
            var first = new SkillTaxonomyService().classify(root);
            var second = new SkillTaxonomyService().classify(root);
            check(first.equals(second), "classification changed");
            check(!first.contentIncluded() && !first.writesPerformed() && !first.llmUsed(),
                    "read-only contract");
            check(before.equals(snapshot(root)), "workspace changed");
        });
    }

    private static void invalidAndNegated() throws Exception {
        withWorkspace(root -> {
            writeSkill(root, "not-a-review", "Do not use for code review tasks");
            Path invalid = Files.createDirectories(root.resolve(".agents/skills/invalid-review"))
                    .resolve("SKILL.md");
            Files.writeString(invalid, "---\nname: invalid-review\n"
                    + "description: Code review\ndescription: Security audit\n---\n",
                    StandardCharsets.UTF_8);
            var report = new SkillTaxonomyService().classify(root);
            check(report.skills().size() == 2, "invalid Skill disappeared");
            check(report.unclassifiedCount() == 2, "invalid or negated Skill was classified");
        });
    }

    private static void cardinality() throws Exception {
        withWorkspace(root -> {
            for (int index = 0; index < 65; index++) {
                writeSkill(root, "utility-" + index, "General project helper " + index);
            }
            var report = new SkillTaxonomyService().classify(root);
            check(report.skills().size() == 65, "classification truncated inventory");
            check(report.unclassifiedCount() == 65, "truncated Skills were not reviewable");
        });
    }

    private static void bomAndCrLf() throws Exception {
        withWorkspace(root -> {
            Path file = Files.createDirectories(root.resolve(".agents/skills/code-review"))
                    .resolve("SKILL.md");
            Files.writeString(file, "\ufeff---\r\nname : code-review\r\n"
                    + "description : Code review for API backward compatibility\r\n---\r\n",
                    StandardCharsets.UTF_8);
            var report = new SkillTaxonomyService().classify(root);
            check(report.skills().getFirst().category() == Category.CODE_QUALITY_REVIEW,
                    "BOM/CRLF description was not classified");
        });
    }

    private static void writeSkill(Path root, String name, String description) throws Exception {
        Path file = Files.createDirectories(root.resolve(".agents/skills").resolve(name))
                .resolve("SKILL.md");
        Files.writeString(file, "---\nname: " + name + "\ndescription: " + description
                + "\n---\n\n# " + name + "\n", StandardCharsets.UTF_8);
    }

    private static Map<String, String> snapshot(Path root) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                String relative = root.relativize(path).toString();
                result.put(relative, Files.isRegularFile(path)
                        ? java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                                .digest(Files.readAllBytes(path))) : "directory");
            }
        }
        return result;
    }

    private static void withWorkspace(CheckedConsumer task) throws Exception {
        Path root = Files.createTempDirectory("agent-studio-taxonomy-");
        try { task.accept(root); }
        finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void run(String name, CheckedRunnable test) throws Exception {
        test.run();
        passed++;
        System.out.println("PASS " + name);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface private interface CheckedRunnable { void run() throws Exception; }
    @FunctionalInterface private interface CheckedConsumer { void accept(Path root) throws Exception; }
}
