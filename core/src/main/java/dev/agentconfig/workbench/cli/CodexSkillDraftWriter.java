package dev.agentconfig.workbench.cli;

import dev.agentconfig.workbench.skilldraft.CodexSkillDraftPreview;
import dev.agentconfig.workbench.skilldraft.CodexSkillDraftPreview.Candidate;
import dev.agentconfig.workbench.skilldraft.CodexSkillDraftPreview.Check;
import java.io.PrintWriter;
import java.util.Iterator;

/** Serializes S2 metadata or an explicit, content-bearing stdout export. */
final class CodexSkillDraftWriter {
    private CodexSkillDraftWriter() {}

    static void writeMetadata(CodexSkillDraftPreview preview, PrintWriter output) {
        output.println("{");
        output.printf("  \"schemaVersion\": %d,%n", preview.schemaVersion());
        output.println("  \"command\": \"skill-draft-preview\",");
        output.println("  \"hostId\": \"codex\",");
        output.printf("  \"id\": %s,%n", json(preview.id()));
        output.printf("  \"sourcePreviewId\": %s,%n", json(preview.sourcePreviewId()));
        output.printf("  \"status\": %s,%n", json(preview.status().name()));
        if (preview.candidate().isEmpty()) {
            output.println("  \"candidate\": null,");
        } else {
            Candidate candidate = preview.candidate().orElseThrow();
            output.println("  \"candidate\": {");
            output.printf("    \"id\": %s,%n", json(candidate.id()));
            output.printf("    \"blueprintId\": %s,%n", json(candidate.blueprintId()));
            output.printf("    \"logicalPath\": %s,%n", json(candidate.logicalPath()));
            output.println("    \"encoding\": \"UTF-8\",");
            output.println("    \"lineEnding\": \"LF\",");
            output.printf("    \"byteSize\": %d,%n", candidate.byteSize());
            output.printf("    \"lineCount\": %d,%n", candidate.lineCount());
            output.printf("    \"sha256\": %s,%n", json(candidate.sha256()));
            output.printf("    \"rendererProfileId\": %s%n",
                    json(candidate.rendererProfileId()));
            output.println("  },");
        }
        output.println("  \"validation\": {");
        output.printf("    \"schemaVersion\": %d,%n", preview.validation().schemaVersion());
        output.printf("    \"profileId\": %s,%n", json(preview.validation().profileId()));
        output.printf("    \"status\": %s,%n", json(preview.validation().status().name()));
        output.printf("    \"candidateSha256\": %s,%n",
                preview.validation().candidateSha256().map(CodexSkillDraftWriter::json).orElse("null"));
        output.println("    \"checks\": [");
        Iterator<Check> checks = preview.validation().checks().iterator();
        while (checks.hasNext()) {
            Check check = checks.next();
            output.printf("      {\"code\": %s, \"passed\": %s, \"detail\": %s}%s%n",
                    json(check.code()), check.passed(), json(check.detail()), checks.hasNext() ? "," : "");
        }
        output.println("    ]");
        output.println("  },");
        output.print("  \"unresolved\": [");
        Iterator<String> unresolved = preview.unresolved().iterator();
        while (unresolved.hasNext()) {
            output.print(json(unresolved.next()));
            if (unresolved.hasNext()) output.print(", ");
        }
        output.println("],");
        output.println("  \"workspaceContentIncluded\": false,");
        output.println("  \"userProvidedContentIncluded\": true,");
        output.println("  \"candidateContentIncluded\": false,");
        output.println("  \"routingEvalPerformed\": false,");
        output.println("  \"llmUsed\": false,");
        output.println("  \"networkUsed\": false,");
        output.println("  \"processesStarted\": false,");
        output.println("  \"writesPerformed\": false,");
        output.println("  \"applyEligible\": false");
        output.println("}");
        output.flush();
    }

    static void writeContent(Candidate candidate, PrintWriter output) {
        output.print(candidate.content());
        output.flush();
    }

    static void writeSyntheticDiff(Candidate candidate, PrintWriter output) {
        StringBuilder diff = new StringBuilder();
        diff.append("# diffMode=SYNTHETIC_NEW_FILE targetState=NOT_CHECKED applyEligible=false\n")
                .append("diff --git a/").append(candidate.logicalPath()).append(" b/")
                .append(candidate.logicalPath()).append('\n')
                .append("new file mode 100644\n--- /dev/null\n+++ b/")
                .append(candidate.logicalPath()).append("\n@@ -0,0 +1,")
                .append(candidate.lineCount()).append(" @@\n");
        String[] lines = candidate.content().split("\n", -1);
        for (int index = 0; index < lines.length - 1; index++) {
            diff.append('+').append(lines[index]).append('\n');
        }
        output.print(diff);
        output.flush();
    }

    static void writePrompt(Candidate candidate, PrintWriter output) {
        output.print("Create exactly one project Skill after reviewing the candidate below.\n"
                + "Do not modify any other file and do not execute scripts.\n"
                + "Target: " + candidate.logicalPath() + "\n"
                + "Expected SHA-256: " + candidate.sha256() + "\n"
                + "The target workspace was not inspected; report any existing-file conflict.\n\n"
                + candidate.content());
        output.flush();
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
                    if (character < 0x20) builder.append(String.format("\\u%04x", (int) character));
                    else builder.append(character);
                }
            }
        }
        return builder.append('"').toString();
    }
}
