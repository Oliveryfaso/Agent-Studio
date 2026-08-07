package dev.agentconfig.workbench;

import dev.agentconfig.workbench.transaction.ControlledSkillCandidate;
import dev.agentconfig.workbench.transaction.RawCodexSkillCandidateService;

public final class RawCodexSkillCandidateTests {
    private static final String PATH = ".agents/skills/custom-review/SKILL.md";
    private static int passed;

    private RawCodexSkillCandidateTests() {}

    public static void main(String[] arguments) {
        run("valid custom source is preserved exactly", RawCodexSkillCandidateTests::valid);
        run("frontmatter name is bound to the path", () -> invalid(
                source().replace("name: custom-review", "name: other"),
                "RAW_FRONTMATTER_NAME_MISMATCH"));
        run("complex YAML scalars are rejected", () -> invalid(
                source().replace("description: Custom review", "description: |\n  Custom review"),
                "RAW_FRONTMATTER_UNSUPPORTED"));
        run("alternate YAML key spelling cannot bypass duplicates", () -> invalid(
                source().replace("name: custom-review",
                        "name: custom-review\nname : other"),
                "RAW_FRONTMATTER_UNSUPPORTED"));
        run("quoted YAML keys cannot bypass duplicates", () -> invalid(
                source().replace("name: custom-review",
                        "name: custom-review\n\"na\\u006de\": other"),
                "RAW_FRONTMATTER_UNSUPPORTED"));
        run("non-string descriptions are rejected", () -> invalid(
                source().replace("description: Custom review", "description: # comment"),
                "RAW_FRONTMATTER_DESCRIPTION_REQUIRED"));
        run("text format boundaries are enforced", RawCodexSkillCandidateTests::textFormats);
        run("candidate byte budget is enforced", RawCodexSkillCandidateTests::byteBudget);
        System.out.println("Raw Codex Skill candidate tests: " + passed + " passed");
    }

    private static void valid() {
        ControlledSkillCandidate candidate = new RawCodexSkillCandidateService().validate(
                PATH, source());
        check(candidate.logicalPath().equals(PATH), "logical path changed");
        check(candidate.content().equals(source()), "content changed");
        check(candidate.validationProfileId().equals(
                RawCodexSkillCandidateService.VALIDATION_PROFILE), "profile missing");
        check(candidate.sha256().matches("[0-9a-f]{64}"), "hash missing");
    }

    private static void textFormats() {
        invalid("\ufeff" + source(), "RAW_TEXT_FORMAT_INVALID");
        invalid(source().replace("\n", "\r\n"), "RAW_TEXT_FORMAT_INVALID");
        invalid(source().stripTrailing(), "RAW_TEXT_FORMAT_INVALID");
    }

    private static void byteBudget() {
        invalid(source() + "x".repeat(128 * 1024), "RAW_CONTENT_TOO_LARGE");
    }

    private static void invalid(String content, String code) {
        try {
            new RawCodexSkillCandidateService().validate(PATH, content);
            throw new AssertionError("invalid source accepted");
        } catch (RawCodexSkillCandidateService.ValidationException exception) {
            check(exception.code().equals(code), "wrong code: " + exception.code());
        }
    }

    private static String source() {
        return "---\nname: custom-review\ndescription: Custom review\n---\n\n# Notes\n";
    }

    private static void run(String name, Runnable test) {
        test.run();
        passed++;
        System.out.println("PASS " + name);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
