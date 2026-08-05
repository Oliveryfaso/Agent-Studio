package dev.agentconfig.workbench.skilldraft;

import dev.agentconfig.workbench.blueprint.SkillBlueprintPreview.SkillBlueprint;
import dev.agentconfig.workbench.skilldraft.CodexSkillDraftPreview.Candidate;
import dev.agentconfig.workbench.skilldraft.CodexSkillDraftPreview.Check;
import dev.agentconfig.workbench.skilldraft.CodexSkillDraftPreview.Validation;
import dev.agentconfig.workbench.skilldraft.CodexSkillDraftPreview.ValidationStatus;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Validates final candidate bytes independently from the renderer entry point. */
public final class CodexSkillDraftValidator {
    private static final int MAX_CANDIDATE_BYTES = 128 * 1024;
    private static final int MAX_DESCRIPTION_BYTES = 1_024;

    public Validation validate(SkillBlueprint blueprint, Candidate candidate) {
        CodexSkillDraftService.preflight(blueprint);
        List<Check> checks = new ArrayList<>();
        String text = decode(candidate.bytes());
        String description = CodexSkillDraftService.derivedDescription(blueprint);
        checks.add(check("STRICT_UTF8", text != null, "candidate bytes decode as strict UTF-8"));
        if (text == null) text = "";
        checks.add(check("UTF8_LF_TERMINATED", !text.startsWith("\ufeff")
                && !text.contains("\r") && text.endsWith("\n"), "UTF-8, LF, terminal newline"));
        checks.add(check("CANDIDATE_BYTE_BUDGET", candidate.byteSize() <= MAX_CANDIDATE_BYTES,
                "at most 131072 UTF-8 bytes"));
        checks.add(check("DESCRIPTION_BYTE_BUDGET",
                description.getBytes(StandardCharsets.UTF_8).length <= MAX_DESCRIPTION_BYTES,
                "derived trigger description is at most 1024 UTF-8 bytes"));
        checks.add(check("DESCRIPTION_SAFE_SCALAR",
                !description.contains("<") && !description.contains(">"),
                "description contains no angle brackets"));
        checks.add(check("LOGICAL_PATH", candidate.logicalPath().equals(
                ".agents/skills/" + blueprint.name() + "/SKILL.md"),
                "canonical Codex project Skill path"));
        checks.add(check("RENDERER_PROFILE",
                CodexSkillDraftService.RENDERER_PROFILE.equals(candidate.rendererProfileId()),
                "known renderer profile"));
        String expected = expectedContent(blueprint);
        checks.add(check("CANONICAL_CONTENT", text.equals(expected),
                "final bytes exactly preserve every canonical Blueprint field"));
        boolean failed = checks.stream().anyMatch(value -> !value.passed());
        ValidationStatus status = failed ? ValidationStatus.FAILED
                : CodexSkillDraftService.requiresReview(blueprint)
                        ? ValidationStatus.REVIEW_REQUIRED : ValidationStatus.PASSED;
        return new Validation(1, CodexSkillDraftService.VALIDATOR_PROFILE, status,
                Optional.of(candidate.sha256()), checks);
    }

    static Validation notRun() {
        return new Validation(1, CodexSkillDraftService.VALIDATOR_PROFILE,
                ValidationStatus.NOT_RUN, Optional.empty(), List.of());
    }

    private static String expectedContent(SkillBlueprint blueprint) {
        String description = CodexSkillDraftService.derivedDescription(blueprint);
        StringBuilder text = new StringBuilder("---\nname: ").append(blueprint.name())
                .append("\ndescription: '").append(description.replace("'", "''"))
                .append("'\n---\n\n# ").append(blueprint.name()).append("\n\n## Goal\n\n");
        bullet(text, blueprint.goal());
        section(text, "Inputs", blueprint.inputs());
        section(text, "Outputs", blueprint.outputs());
        text.append("\n## Scope and boundaries\n\n");
        for (String value : blueprint.exclusions()) bullet(text, "Do not use for: " + value);
        for (String value : blueprint.boundaryExamples()) bullet(text, "Boundary example: " + value);
        text.append("\n## Workflow\n\n");
        int index = 1;
        for (String value : blueprint.coreSteps()) {
            text.append(index++).append(". ")
                    .append(CodexSkillDraftService.markdown(value)).append('\n');
        }
        text.append("\n## Completion\n\n");
        bullet(text, blueprint.completionDefinition());
        section(text, "Validation", blueprint.validation());
        return text.toString();
    }

    private static void section(StringBuilder text, String heading, List<String> values) {
        text.append("\n## ").append(heading).append("\n\n");
        for (String value : values) bullet(text, value);
    }

    private static void bullet(StringBuilder text, String value) {
        text.append("- ").append(CodexSkillDraftService.markdown(value)).append('\n');
    }

    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            return null;
        }
    }

    private static Check check(String code, boolean passed, String detail) {
        return new Check(code, passed, detail);
    }
}
