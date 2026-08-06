package dev.agentconfig.workbench.skilldraft;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loss-aware projection of a template-v1 Codex SKILL.md into guided form fields. */
public record CodexSkillFormProjection(
        Status status,
        Optional<Form> form,
        List<String> missingFields,
        String rendererProfileId) {
    private static final Pattern TEMPLATE = Pattern.compile(
            "\\A---\\nname: ([a-z0-9]+(?:-[a-z0-9]+)*)\\ndescription: '((?:[^'\\n\\r]|'')*)'\\n---\\n\\n"
            + "# \\1\\n\\n## Goal\\n\\n- (.+)\\n\\n"
            + "## Inputs\\n\\n((?:- .+\\n)+)\\n"
            + "## Outputs\\n\\n((?:- .+\\n)+)\\n"
            + "## Scope and boundaries\\n\\n((?:- .+\\n)+)\\n"
            + "## Workflow\\n\\n((?:[1-9][0-9]*\\. .+\\n)+)\\n"
            + "## Completion\\n\\n- (.+)\\n\\n"
            + "## Validation\\n\\n((?:- .+\\n)+)\\z");

    public CodexSkillFormProjection {
        Objects.requireNonNull(status, "status");
        form = Objects.requireNonNull(form, "form");
        missingFields = List.copyOf(Objects.requireNonNull(missingFields, "missingFields"));
        rendererProfileId = Objects.requireNonNull(rendererProfileId, "rendererProfileId");
        if (status == Status.PARTIAL_FORM && form.isEmpty()) {
            throw new IllegalArgumentException("partial projection requires form fields");
        }
        if (status == Status.ADVANCED_ONLY && (form.isPresent() || !missingFields.isEmpty())) {
            throw new IllegalArgumentException("advanced-only projection cannot claim form data");
        }
    }

    public static CodexSkillFormProjection parse(String content) {
        Matcher matcher = TEMPLATE.matcher(content);
        if (!matcher.matches()) return advancedOnly();
        try {
            String name = matcher.group(1);
            List<String> inputs = bullets(matcher.group(4));
            List<String> outputs = bullets(matcher.group(5));
            List<String> scope = bullets(matcher.group(6));
            List<String> exclusions = prefixed(scope, "Do not use for: ");
            List<String> boundaries = prefixed(scope, "Boundary example: ");
            if (exclusions.isEmpty() || boundaries.isEmpty()
                    || exclusions.size() + boundaries.size() != scope.size()) {
                return advancedOnly();
            }
            List<String> steps = numbered(matcher.group(7));
            List<String> validations = bullets(matcher.group(9));
            String goal = unmarkdown(matcher.group(3));
            String completion = unmarkdown(matcher.group(8));
            String descriptionWithRouting = matcher.group(2).replace("''", "'");
            List<String> triggers = descriptionTriggers(descriptionWithRouting, exclusions);
            if (triggers.isEmpty()) return advancedOnly();
            String suffix = " Use when: " + String.join("; ", triggers)
                    + ". Do not use when: " + String.join("; ", exclusions) + ".";
            if (!descriptionWithRouting.endsWith(suffix)) return advancedOnly();
            String description = descriptionWithRouting.substring(
                    0, descriptionWithRouting.length() - suffix.length());
            if (description.isBlank()) return advancedOnly();
            Form form = new Form(name, description, goal, inputs, outputs, triggers, exclusions,
                    boundaries, steps, completion, validations);
            if (!valid(form)) return advancedOnly();
            String derivedDescription = form.description() + " Use when: "
                    + String.join("; ", form.triggers()) + ". Do not use when: "
                    + String.join("; ", form.exclusions()) + ".";
            if (!derivedDescription.equals(descriptionWithRouting)
                    || !render(form, derivedDescription).equals(content)) return advancedOnly();
            return new CodexSkillFormProjection(Status.PARTIAL_FORM, Optional.of(form),
                    List.of("shouldTriggerCases", "shouldNotTriggerCases"),
                    CodexSkillDraftService.RENDERER_PROFILE);
        } catch (IllegalArgumentException exception) {
            return advancedOnly();
        }
    }

    private static CodexSkillFormProjection advancedOnly() {
        return new CodexSkillFormProjection(Status.ADVANCED_ONLY, Optional.empty(), List.of(),
                "unknown");
    }

    private static List<String> descriptionTriggers(String description, List<String> exclusions) {
        String marker = ". Do not use when: " + String.join("; ", exclusions) + ".";
        int end = description.lastIndexOf(marker);
        int start = description.lastIndexOf(" Use when: ", end);
        if (start < 0 || end < 0 || end <= start + 11) return List.of();
        return List.of(description.substring(start + 11, end).split("; ", -1));
    }

    private static List<String> bullets(String block) {
        return block.lines().map(line -> unmarkdown(line.substring(2))).toList();
    }

    private static List<String> numbered(String block) {
        int[] expected = {1};
        return block.lines().map(line -> {
            String prefix = expected[0]++ + ". ";
            if (!line.startsWith(prefix)) throw new IllegalArgumentException("workflow order");
            return unmarkdown(line.substring(prefix.length()));
        }).toList();
    }

    private static List<String> prefixed(List<String> values, String prefix) {
        return values.stream().filter(value -> value.startsWith(prefix))
                .map(value -> value.substring(prefix.length())).toList();
    }

    private static String unmarkdown(String value) {
        StringBuilder result = new StringBuilder(value.length());
        String escapable = "\\`*_{}[]()<>#+-.!|~&";
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\\' && index + 1 < value.length()
                    && escapable.indexOf(value.charAt(index + 1)) >= 0) {
                character = value.charAt(++index);
            }
            result.append(character);
        }
        if (!CodexSkillDraftService.markdown(result.toString()).equals(value)) {
            throw new IllegalArgumentException("non-canonical markdown escaping");
        }
        return result.toString();
    }

    private static String render(Form form, String derivedDescription) {
        StringBuilder text = new StringBuilder("---\nname: ").append(form.name())
                .append("\ndescription: '").append(derivedDescription.replace("'", "''"))
                .append("'\n---\n\n# ").append(form.name())
                .append("\n\n## Goal\n\n- ").append(CodexSkillDraftService.markdown(form.goal()))
                .append("\n\n## Inputs\n\n");
        appendBullets(text, form.inputs(), "");
        text.append("\n## Outputs\n\n");
        appendBullets(text, form.outputs(), "");
        text.append("\n## Scope and boundaries\n\n");
        appendBullets(text, form.exclusions(), "Do not use for: ");
        appendBullets(text, form.boundaries(), "Boundary example: ");
        text.append("\n## Workflow\n\n");
        for (int index = 0; index < form.steps().size(); index++) {
            text.append(index + 1).append(". ")
                    .append(CodexSkillDraftService.markdown(form.steps().get(index))).append('\n');
        }
        text.append("\n## Completion\n\n- ")
                .append(CodexSkillDraftService.markdown(form.completion()))
                .append("\n\n## Validation\n\n");
        appendBullets(text, form.validations(), "");
        return text.toString();
    }

    private static void appendBullets(StringBuilder text, List<String> values, String prefix) {
        for (String value : values) {
            text.append("- ").append(CodexSkillDraftService.markdown(prefix + value)).append('\n');
        }
    }

    private static boolean valid(Form form) {
        if (!validScalar(form.description()) || !validScalar(form.goal())
                || !validScalar(form.completion())) return false;
        List<List<String>> lists = List.of(form.inputs(), form.outputs(), form.triggers(),
                form.exclusions(), form.boundaries(), form.steps(), form.validations());
        long characters = form.description().length() + form.goal().length()
                + form.completion().length() + form.name().length();
        for (List<String> values : lists) {
            if (values.isEmpty() || values.size() > 64) return false;
            for (String value : values) {
                if (!validScalar(value)) return false;
                characters += value.length();
                if (characters > 65_536) return false;
            }
        }
        String derived = form.description() + " Use when: " + String.join("; ", form.triggers())
                + ". Do not use when: " + String.join("; ", form.exclusions()) + ".";
        return derived.getBytes(StandardCharsets.UTF_8).length <= 1_024
                && !derived.contains("<") && !derived.contains(">");
    }

    private static boolean validScalar(String value) {
        if (value == null || value.isBlank() || value.length() > 4_096) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\n' || character == '\r' || character == 0
                    || character == '\u2028' || character == '\u2029'
                    || (Character.isISOControl(character) && character != '\t')) return false;
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length()
                        || !Character.isSurrogatePair(character, value.charAt(index + 1))) {
                    return false;
                }
                index++;
            } else if (Character.isLowSurrogate(character)) return false;
        }
        return true;
    }

    public enum Status { PARTIAL_FORM, ADVANCED_ONLY }

    public record Form(
            String name,
            String description,
            String goal,
            List<String> inputs,
            List<String> outputs,
            List<String> triggers,
            List<String> exclusions,
            List<String> boundaries,
            List<String> steps,
            String completion,
            List<String> validations) {
        public Form {
            inputs = List.copyOf(inputs);
            outputs = List.copyOf(outputs);
            triggers = List.copyOf(triggers);
            exclusions = List.copyOf(exclusions);
            boundaries = List.copyOf(boundaries);
            steps = List.copyOf(steps);
            validations = List.copyOf(validations);
        }
    }
}
