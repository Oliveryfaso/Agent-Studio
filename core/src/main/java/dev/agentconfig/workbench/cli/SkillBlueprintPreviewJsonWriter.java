package dev.agentconfig.workbench.cli;

import dev.agentconfig.workbench.blueprint.SkillBlueprintPreview;
import dev.agentconfig.workbench.blueprint.SkillBlueprintPreview.SkillBlueprint;
import java.io.PrintWriter;
import java.util.Iterator;

/** Writes the deterministic S1 preview, including only bounded user-provided form fields. */
final class SkillBlueprintPreviewJsonWriter {
    private SkillBlueprintPreviewJsonWriter() {}

    static void write(SkillBlueprintPreview preview, PrintWriter output) {
        output.println("{");
        output.printf("  \"schemaVersion\": %d,%n", preview.schemaVersion());
        output.printf("  \"id\": %s,%n", json(preview.id()));
        output.println("  \"operation\": \"SKILL_BLUEPRINT_PREVIEW\",");
        output.printf("  \"classificationProfileId\": %s,%n",
                json(preview.classificationProfileId()));
        output.println("  \"hostId\": \"codex\",");
        output.printf("  \"status\": %s,%n", json(preview.status().name()));
        output.println("  \"decision\": {");
        output.printf("    \"recommendedArtifact\": %s,%n",
                json(preview.decision().recommendedArtifact().name()));
        output.printf("    \"status\": %s,%n", json(preview.decision().status().name()));
        output.printf("    \"confidence\": %s,%n",
                json(preview.decision().confidence().name()));
        strings("evidence", preview.decision().evidence().iterator(), output, 4, true);
        output.print("    \"alternatives\": [");
        Iterator<SkillBlueprintPreview.ArtifactType> alternatives =
                preview.decision().alternatives().iterator();
        while (alternatives.hasNext()) {
            output.print(json(alternatives.next().name()));
            if (alternatives.hasNext()) {
                output.print(", ");
            }
        }
        output.println("],");
        output.printf("    \"userConfirmationRequired\": %s,%n",
                preview.decision().userConfirmationRequired());
        output.printf("    \"userConfirmed\": %s,%n", preview.decision().userConfirmed());
        output.printf("    \"confirmedArtifact\": %s,%n",
                preview.decision().confirmedArtifact().map(Enum::name)
                        .map(SkillBlueprintPreviewJsonWriter::json).orElse("null"));
        output.printf("    \"confirmedScope\": %s%n",
                preview.decision().confirmedScope()
                        .map(SkillBlueprintPreviewJsonWriter::json).orElse("null"));
        output.println("  },");
        if (preview.blueprint().isPresent()) {
            blueprint(preview.blueprint().orElseThrow(), output);
        } else {
            output.println("  \"blueprint\": null,");
        }
        strings("missingFields", preview.missingFields().iterator(), output, 2, true);
        strings("findings", preview.findings().iterator(), output, 2, true);
        output.printf("  \"workspaceContentIncluded\": %s,%n",
                preview.workspaceContentIncluded());
        output.printf("  \"userProvidedContentIncluded\": %s,%n",
                preview.userProvidedContentIncluded());
        output.printf("  \"rawRequestIncluded\": %s,%n", preview.rawRequestIncluded());
        output.printf("  \"llmUsed\": %s,%n", preview.llmUsed());
        output.printf("  \"writesPerformed\": %s,%n", preview.writesPerformed());
        output.printf("  \"applyEligible\": %s%n", preview.applyEligible());
        output.println("}");
        output.flush();
    }

    private static void blueprint(SkillBlueprint value, PrintWriter output) {
        output.println("  \"blueprint\": {");
        output.printf("    \"schemaVersion\": %d,%n", value.schemaVersion());
        output.printf("    \"id\": %s,%n", json(value.id()));
        output.printf("    \"name\": %s,%n", json(value.name()));
        output.printf("    \"description\": %s,%n", json(value.description()));
        output.printf("    \"goal\": %s,%n", json(value.goal()));
        output.printf("    \"scope\": %s,%n", json(value.scope()));
        strings("inputs", value.inputs().iterator(), output, 4, true);
        strings("outputs", value.outputs().iterator(), output, 4, true);
        strings("triggers", value.triggers().iterator(), output, 4, true);
        strings("exclusions", value.exclusions().iterator(), output, 4, true);
        strings("boundaryExamples", value.boundaryExamples().iterator(), output, 4, true);
        strings("shouldTriggerCases", value.shouldTriggerCases().iterator(), output, 4, true);
        strings("shouldNotTriggerCases", value.shouldNotTriggerCases().iterator(), output, 4, true);
        strings("coreSteps", value.coreSteps().iterator(), output, 4, true);
        output.printf("    \"completionDefinition\": %s,%n",
                json(value.completionDefinition()));
        strings("validation", value.validation().iterator(), output, 4, true);
        strings("tools", value.tools().iterator(), output, 4, true);
        strings("permissionIntents", value.permissionIntents().iterator(), output, 4, true);
        output.printf("    \"risk\": %s,%n", json(value.risk()));
        strings("supportingFiles", value.supportingFiles().iterator(), output, 4, true);
        strings("hostExtensions", value.hostExtensions().iterator(), output, 4, true);
        strings("provenance", value.provenance().iterator(), output, 4, false);
        output.println("  },");
    }

    private static void strings(
            String name, Iterator<String> values, PrintWriter output, int spaces, boolean comma) {
        String indent = " ".repeat(spaces);
        output.printf("%s\"%s\": [", indent, name);
        while (values.hasNext()) {
            output.print(json(values.next()));
            if (values.hasNext()) {
                output.print(", ");
            }
        }
        output.printf("]%s%n", comma ? "," : "");
    }

    private static String json(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (character < 0x20) {
                        builder.append(String.format("\\u%04x", (int) character));
                    } else {
                        builder.append(character);
                    }
                }
            }
        }
        return builder.append('"').toString();
    }
}
