package dev.agentconfig.workbench.cli;

import dev.agentconfig.workbench.skill.CodexSkillInventory;
import java.io.PrintWriter;
import java.util.Iterator;

/** Serializes Skill inventory v2 without SKILL.md or supporting-file content. */
final class SkillInventoryJsonWriter {
    private SkillInventoryJsonWriter() {}

    static void write(CodexSkillInventory inventory, PrintWriter output) {
        output.println("{");
        output.printf("  \"schemaVersion\": %d,%n", inventory.schemaVersion());
        output.printf("  \"referenceProfileId\": %s,%n",
                json(inventory.referenceProfileId()));
        output.println("  \"command\": \"skill-inventory\",");
        output.println("  \"hostId\": \"codex\",");
        output.printf("  \"status\": %s,%n", json(inventory.status().name()));
        output.printf("  \"contentIncluded\": %s,%n", inventory.contentIncluded());
        output.printf("  \"writesPerformed\": %s,%n", inventory.writesPerformed());
        writePackages(inventory.packages().iterator(), output);
        writeReferences(inventory.references().iterator(), output);
        writeFindings(inventory.findings().iterator(), output);
        output.println("}");
        output.flush();
    }

    private static void writePackages(
            Iterator<CodexSkillInventory.SkillPackage> packages, PrintWriter output) {
        output.println("  \"packages\": [");
        while (packages.hasNext()) {
            CodexSkillInventory.SkillPackage skillPackage = packages.next();
            output.println("    {");
            output.printf("      \"directoryName\": %s,%n", json(skillPackage.directoryName()));
            output.printf("      \"declaredName\": %s,%n",
                    skillPackage.declaredName().isEmpty() ? "null" : json(skillPackage.declaredName()));
            output.printf("      \"logicalPath\": %s,%n", json(skillPackage.logicalPath()));
            output.printf("      \"byteSize\": %d,%n", skillPackage.byteSize());
            output.printf("      \"sha256\": %s,%n", json(skillPackage.sha256()));
            output.printf("      \"descriptionPresent\": %s,%n",
                    skillPackage.descriptionPresent());
            output.printf("      \"supportingFileCount\": %d,%n",
                    skillPackage.supportingFileCount());
            output.print("      \"risks\": [");
            Iterator<CodexSkillInventory.Risk> risks = skillPackage.risks().stream()
                    .sorted().iterator();
            while (risks.hasNext()) {
                output.print(json(risks.next().name()));
                if (risks.hasNext()) {
                    output.print(", ");
                }
            }
            output.println("],");
            output.printf("      \"state\": %s%n", json(skillPackage.state().name()));
            output.printf("    }%s%n", packages.hasNext() ? "," : "");
        }
        output.println("  ],");
    }

    private static void writeReferences(
            Iterator<CodexSkillInventory.Reference> references, PrintWriter output) {
        output.println("  \"references\": [");
        while (references.hasNext()) {
            CodexSkillInventory.Reference reference = references.next();
            output.println("    {");
            output.printf("      \"sourceLogicalPath\": %s,%n",
                    json(reference.sourceLogicalPath()));
            output.printf("      \"targetLogicalPath\": %s,%n",
                    reference.targetLogicalPath().isEmpty()
                            ? "null" : json(reference.targetLogicalPath()));
            output.printf("      \"line\": %d,%n", reference.line());
            output.printf("      \"column\": %d,%n", reference.column());
            output.printf("      \"kind\": %s,%n", json(reference.kind().name()));
            output.printf("      \"resolution\": %s%n",
                    json(reference.resolution().name()));
            output.printf("    }%s%n", references.hasNext() ? "," : "");
        }
        output.println("  ],");
    }

    private static void writeFindings(
            Iterator<CodexSkillInventory.Finding> findings, PrintWriter output) {
        output.println("  \"findings\": [");
        while (findings.hasNext()) {
            CodexSkillInventory.Finding finding = findings.next();
            output.println("    {");
            output.printf("      \"severity\": %s,%n", json(finding.severity().name()));
            output.printf("      \"code\": %s,%n", json(finding.code().name()));
            output.printf("      \"logicalPath\": %s,%n", json(finding.logicalPath()));
            output.printf("      \"summary\": %s%n", json(finding.summary()));
            output.printf("    }%s%n", findings.hasNext() ? "," : "");
        }
        output.println("  ]");
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
