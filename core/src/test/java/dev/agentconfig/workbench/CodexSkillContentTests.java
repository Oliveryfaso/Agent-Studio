package dev.agentconfig.workbench;

import dev.agentconfig.workbench.blueprint.BlueprintPreviewService;
import dev.agentconfig.workbench.skill.CodexSkillContent;
import dev.agentconfig.workbench.skill.CodexSkillContentService;
import dev.agentconfig.workbench.skilldraft.CodexSkillDraftService;
import dev.agentconfig.workbench.skilldraft.CodexSkillFormProjection;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class CodexSkillContentTests {
    private static int passed;

    private CodexSkillContentTests() {}

    public static void main(String[] arguments) throws Exception {
        run("template v1 is honestly projected as a partial form",
                CodexSkillContentTests::projectsCanonicalTemplate);
        run("custom Skill falls back to exact source", CodexSkillContentTests::keepsCustomSource);
        run("template lookalikes do not claim recoverable fields",
                CodexSkillContentTests::rejectsTemplateLookalikes);
        run("logical path cannot escape the Skill inventory", CodexSkillContentTests::rejectsPath);
        System.out.println("Codex Skill content tests: " + passed + " passed");
    }

    private static void projectsCanonicalTemplate() throws Exception {
        withWorkspace(root -> {
            Path skill = root.resolve(".agents/skills/review-api-change/SKILL.md");
            Files.createDirectories(skill.getParent());
            byte[] bytes = new CodexSkillDraftService().draft(new BlueprintPreviewService().preview(
                    new ByteArrayInputStream(request().getBytes(StandardCharsets.UTF_8))))
                    .candidate().orElseThrow().bytes();
            Files.write(skill, bytes);
            CodexSkillContent content = new CodexSkillContentService().read(
                    root, ".agents/skills/review-api-change/SKILL.md");
            check(content.content().equals(new String(bytes, StandardCharsets.UTF_8)),
                    "content changed");
            check(content.projection().status()
                    == CodexSkillFormProjection.Status.PARTIAL_FORM, "not partial form");
            var form = content.projection().form().orElseThrow();
            check(form.name().equals("review-api-change"), "name missing");
            check(form.description().equals("Review API changes"), "description missing");
            check(form.inputs().equals(java.util.List.of("Changed files")), "inputs missing");
            check(content.projection().missingFields().equals(java.util.List.of(
                    "shouldTriggerCases", "shouldNotTriggerCases")), "loss not explicit");
        });
    }

    private static void keepsCustomSource() throws Exception {
        withWorkspace(root -> {
            Path skill = root.resolve(".agents/skills/custom-review/SKILL.md");
            Files.createDirectories(skill.getParent());
            String source = "---\nname: custom-review\ndescription: Custom\n---\n\n# Notes\n";
            Files.writeString(skill, source, StandardCharsets.UTF_8);
            CodexSkillContent content = new CodexSkillContentService().read(
                    root, ".agents/skills/custom-review/SKILL.md");
            check(content.projection().status()
                    == CodexSkillFormProjection.Status.ADVANCED_ONLY, "custom source guessed");
            check(content.projection().form().isEmpty(), "custom form was fabricated");
            check(content.content().equals(source), "custom source changed");
        });
    }

    private static void rejectsPath() throws Exception {
        withWorkspace(root -> {
            try {
                new CodexSkillContentService().read(root, "../AGENTS.md");
                throw new AssertionError("unsafe path accepted");
            } catch (IllegalArgumentException expected) {
                // expected
            }
        });
    }

    private static void rejectsTemplateLookalikes() throws Exception {
        withWorkspace(root -> {
            Path skill = root.resolve(".agents/skills/review-api-change/SKILL.md");
            Files.createDirectories(skill.getParent());
            byte[] bytes = new CodexSkillDraftService().draft(new BlueprintPreviewService().preview(
                    new ByteArrayInputStream(request().getBytes(StandardCharsets.UTF_8))))
                    .candidate().orElseThrow().bytes();
            String invalid = new String(bytes, StandardCharsets.UTF_8).replace(
                    "Use when: Review an API.", "Use when: Review an API; ; another.");
            Files.writeString(skill, invalid, StandardCharsets.UTF_8);
            CodexSkillContent content = new CodexSkillContentService().read(
                    root, ".agents/skills/review-api-change/SKILL.md");
            check(content.projection().status()
                    == CodexSkillFormProjection.Status.ADVANCED_ONLY,
                    "empty trigger was treated as canonical");
        });
    }

    private static String request() {
        return "repeated-workflow: true\nclear-trigger: true\nsuccess-criteria: true\n"
                + "confirmed-artifact: skill\nconfirmed-scope: project\n"
                + "name: review-api-change\ndescription: Review API changes\n"
                + "goal: Produce findings\ninput: Changed files\noutput: Review report\n"
                + "trigger: Review an API\nexclusion: Edit CSS\n"
                + "boundary-example: A style-only change\n"
                + "should-trigger: Review endpoint\nshould-trigger: Review schema\n"
                + "should-trigger: Review migration\nshould-not-trigger: Review CSS\n"
                + "should-not-trigger: Write copy\nshould-not-trigger: Rename image\n"
                + "step: Inspect contracts\ncompletion: Every API has a conclusion\n"
                + "validation: Check each finding\npermission: NONE\nrisk: LOW\n";
    }

    private static void withWorkspace(CheckedConsumer task) throws Exception {
        Path root = Files.createTempDirectory("agent-config-skill-content-");
        try {
            task.accept(root);
        } finally {
            try (var paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.delete(path); }
                    catch (Exception exception) { throw new IllegalStateException(exception); }
                });
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
    @FunctionalInterface private interface CheckedConsumer { void accept(Path path) throws Exception; }
}
