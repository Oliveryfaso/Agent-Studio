package dev.agentconfig.workbench.skilldraft;

import dev.agentconfig.workbench.blueprint.SkillBlueprintPreview;
import dev.agentconfig.workbench.blueprint.SkillBlueprintPreview.SkillBlueprint;
import dev.agentconfig.workbench.skilldraft.CodexSkillDraftPreview.Candidate;
import dev.agentconfig.workbench.skilldraft.CodexSkillDraftPreview.Check;
import dev.agentconfig.workbench.skilldraft.CodexSkillDraftPreview.Status;
import dev.agentconfig.workbench.skilldraft.CodexSkillDraftPreview.Validation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** Deterministically renders and validates one Codex project SKILL.md in memory. */
public final class CodexSkillDraftService {
    public static final String RENDERER_PROFILE = "codex-project-skill-template-v1";
    public static final String VALIDATOR_PROFILE = "codex-project-skill-static-v1";

    public CodexSkillDraftPreview draft(SkillBlueprintPreview source) {
        if (source.status() != SkillBlueprintPreview.PreviewStatus.BLUEPRINT_READY
                || source.blueprint().isEmpty()) {
            Validation validation = CodexSkillDraftValidator.notRun();
            return preview(source, Status.SOURCE_NOT_READY, Optional.empty(), validation,
                    List.of("SOURCE_BLUEPRINT_NOT_READY"));
        }
        SkillBlueprint blueprint = source.blueprint().orElseThrow();
        Rendered rendered = render(blueprint);
        Candidate candidate = new Candidate(
                "skc_" + hash(tuple("candidate:v1", blueprint.id(), rendered.logicalPath(),
                        RENDERER_PROFILE, "UTF-8", "LF", rendered.sha256())),
                blueprint.id(), rendered.logicalPath(), rendered.bytes(), rendered.sha256(),
                RENDERER_PROFILE, rendered.lineCount());
        Validation validation = new CodexSkillDraftValidator().validate(blueprint, candidate);
        boolean review = requiresReview(blueprint);
        Status status = validation.status() == CodexSkillDraftPreview.ValidationStatus.FAILED
                ? Status.INVALID : review ? Status.REVIEW_REQUIRED : Status.READY;
        List<String> unresolved = review ? reviewReasons(blueprint) : List.of();
        return preview(source, status, Optional.of(candidate), validation, unresolved);
    }

    private static Rendered render(SkillBlueprint blueprint) {
        preflight(blueprint);
        validateScalar(blueprint.name(), "name");
        String description = derivedDescription(blueprint);
        validateScalar(description, "description", 65_536);
        StringBuilder text = new StringBuilder();
        text.append("---\nname: ").append(blueprint.name()).append("\ndescription: '")
                .append(description.replace("'", "''")).append("'\n---\n\n")
                .append("# ").append(blueprint.name()).append("\n\n")
                .append("## Goal\n\n");
        bullet(text, blueprint.goal());
        section(text, "Inputs", blueprint.inputs());
        section(text, "Outputs", blueprint.outputs());
        text.append("\n## Scope and boundaries\n\n");
        for (String value : blueprint.exclusions()) {
            bullet(text, "Do not use for: " + value);
        }
        for (String value : blueprint.boundaryExamples()) {
            bullet(text, "Boundary example: " + value);
        }
        text.append("\n## Workflow\n\n");
        int index = 1;
        for (String value : blueprint.coreSteps()) {
            text.append(index++).append(". ").append(markdown(value)).append('\n');
        }
        text.append("\n## Completion\n\n");
        bullet(text, blueprint.completionDefinition());
        section(text, "Validation", blueprint.validation());
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        return new Rendered(".agents/skills/" + blueprint.name() + "/SKILL.md", bytes,
                hash(bytes), countLines(text.toString()));
    }

    private static CodexSkillDraftPreview preview(SkillBlueprintPreview source, Status status,
            Optional<Candidate> candidate, Validation validation, List<String> unresolved) {
        String candidateHash = candidate.map(Candidate::sha256).orElse("none");
        List<String> identity = new ArrayList<>(List.of("draft-preview:v1", source.id(),
                status.name(), candidateHash, validation.profileId(), validation.status().name(),
                validation.candidateSha256().orElse("none")));
        for (Check check : validation.checks()) {
            identity.add(check.code());
            identity.add(Boolean.toString(check.passed()));
            identity.add(check.detail());
        }
        identity.addAll(unresolved);
        String id = "sdp_" + hash(tuple(identity.toArray(String[]::new)));
        return new CodexSkillDraftPreview(1, id, status, source.id(), candidate, validation,
                unresolved, false, true, false, false, false, false, false, false, false);
    }

    static boolean requiresReview(SkillBlueprint blueprint) {
        return !blueprint.tools().isEmpty() || !blueprint.permissionIntents().equals(List.of("NONE"))
                || !blueprint.supportingFiles().isEmpty() || !"LOW".equals(blueprint.risk());
    }

    private static List<String> reviewReasons(SkillBlueprint blueprint) {
        List<String> reasons = new ArrayList<>();
        if (!blueprint.tools().isEmpty()) reasons.add("TOOL_INTENT_REQUIRES_REVIEW");
        if (!blueprint.permissionIntents().equals(List.of("NONE"))) {
            reasons.add("PERMISSION_INTENT_REQUIRES_REVIEW");
        }
        if (!blueprint.supportingFiles().isEmpty()) {
            reasons.add("SUPPORTING_FILES_ARE_PROPOSALS_ONLY");
        }
        if (!"LOW".equals(blueprint.risk())) reasons.add("ELEVATED_RISK_REQUIRES_REVIEW");
        return List.copyOf(reasons);
    }

    static String derivedDescription(SkillBlueprint blueprint) {
        return blueprint.description() + " Use when: " + String.join("; ", blueprint.triggers())
                + ". Do not use when: " + String.join("; ", blueprint.exclusions()) + ".";
    }

    private static void section(StringBuilder text, String heading, List<String> values) {
        text.append("\n## ").append(heading).append("\n\n");
        for (String value : values) bullet(text, value);
    }

    private static void bullet(StringBuilder text, String value) {
        validateScalar(value, "blueprint value");
        text.append("- ").append(markdown(value)).append('\n');
    }

    static String markdown(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if ("\\`*_{}[]()<>#+-.!|~&".indexOf(character) >= 0) escaped.append('\\');
            escaped.append(character);
        }
        return escaped.toString();
    }

    private static void validateScalar(String value, String field) {
        validateScalar(value, field, 4_096);
    }

    private static void validateScalar(String value, String field, int maxCharacters) {
        if (value == null || value.isBlank() || value.length() > maxCharacters) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\n' || character == '\r' || character == 0
                    || character == '\u2028' || character == '\u2029'
                    || (Character.isISOControl(character) && character != '\t')
                    || (Character.isSurrogate(character)
                    && (index + 1 >= value.length() || !Character.isSurrogatePair(
                            character, value.charAt(index + 1))))) {
                throw new IllegalArgumentException(field + " contains unsupported characters");
            }
            if (Character.isHighSurrogate(character)) index++;
            else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException(field + " contains unsupported characters");
            }
        }
    }

    static void preflight(SkillBlueprint blueprint) {
        List<List<String>> lists = List.of(blueprint.inputs(), blueprint.outputs(),
                blueprint.triggers(), blueprint.exclusions(), blueprint.boundaryExamples(),
                blueprint.coreSteps(), blueprint.validation(), blueprint.tools(),
                blueprint.permissionIntents(), blueprint.supportingFiles());
        long characters = blueprint.name().length() + blueprint.description().length()
                + blueprint.goal().length() + blueprint.completionDefinition().length();
        for (List<String> values : lists) {
            if (values.size() > 64) {
                throw new IllegalArgumentException("blueprint collection exceeds 64 values");
            }
            for (String value : values) {
                characters += value.length();
                if (characters > 65_536) {
                    throw new IllegalArgumentException("blueprint exceeds the render budget");
                }
            }
        }
    }

    private static int countLines(String value) {
        return Math.toIntExact(value.chars().filter(character -> character == '\n').count());
    }

    private static String hash(String value) {
        return hash(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String tuple(String... values) {
        StringBuilder canonical = new StringBuilder();
        for (String value : values) {
            canonical.append(value.length()).append(':').append(value).append(';');
        }
        return canonical.toString();
    }

    private static String hash(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record Rendered(String logicalPath, byte[] bytes, String sha256, int lineCount) {
        private Rendered {
            bytes = bytes.clone();
        }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
}
