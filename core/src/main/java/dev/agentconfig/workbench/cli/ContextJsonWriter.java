package dev.agentconfig.workbench.cli;

import dev.agentconfig.workbench.context.ContextSource;
import dev.agentconfig.workbench.context.ContextFinding;
import dev.agentconfig.workbench.context.ContextRelation;
import dev.agentconfig.workbench.context.EffectiveInstructionContext;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Iterator;

final class ContextJsonWriter {
    private ContextJsonWriter() {}

    static void write(EffectiveInstructionContext context, PrintWriter output) {
        output.println("{");
        output.println("  \"schemaVersion\": 2,");
        output.printf("  \"hostId\": %s,%n", json(context.hostId()));
        output.printf("  \"semanticProfile\": %s,%n", json(context.semanticProfile()));
        output.printf("  \"supportLevel\": %s,%n", json(context.supportLevel()));
        output.printf("  \"orderingModel\": %s,%n", json(context.orderingModel()));
        output.printf("  \"resolutionStatus\": %s,%n", json(context.resolutionStatus().name()));
        output.printf("  \"logicalRoot\": %s,%n", json(context.logicalRoot().toString()));
        output.printf("  \"realRoot\": %s,%n", json(context.realRoot().toString()));
        output.printf("  \"currentDirectory\": %s,%n", json(context.currentDirectory().toString()));
        output.printf("  \"targetFile\": %s,%n",
                context.targetFile() == null ? "null" : json(portable(context.targetFile())));
        output.printf("  \"explicitConfigSnapshot\": %s,%n", context.explicitConfigSnapshot());
        output.printf("  \"compiledAt\": %s,%n", json(context.compiledAt().toString()));
        output.printf("  \"maxCombinedBytes\": %s,%n",
                context.maxCombinedBytes() == 0 ? "null" : Long.toString(context.maxCombinedBytes()));
        output.printf("  \"includedBytes\": %d,%n", context.includedBytes());
        output.println("  \"sources\": [");
        Iterator<ContextSource> sources = context.sources().iterator();
        while (sources.hasNext()) {
            ContextSource source = sources.next();
            output.println("    {");
            output.printf("      \"logicalPath\": %s,%n", json(portable(source.logicalPath())));
            output.printf("      \"realPath\": %s,%n",
                    source.realPath() == null ? "null" : json(source.realPath().toString()));
            output.printf("      \"kind\": %s,%n", json(source.kind().name()));
            output.printf("      \"state\": %s,%n", json(source.state().name()));
            output.printf("      \"loadOrder\": %d,%n", source.precedence());
            output.printf("      \"byteSize\": %d,%n", source.byteSize());
            output.printf("      \"includedBytes\": %d,%n", source.includedBytes());
            output.printf("      \"sha256\": %s,%n",
                    source.sha256().isEmpty() ? "null" : json(source.sha256()));
            output.printf("      \"detail\": %s%n", json(source.detail()));
            output.printf("    }%s%n", sources.hasNext() ? "," : "");
        }
        output.println("  ],");
        output.println("  \"relations\": [");
        Iterator<ContextRelation> relations = context.relations().iterator();
        while (relations.hasNext()) {
            ContextRelation relation = relations.next();
            output.println("    {");
            output.printf("      \"kind\": %s,%n", json(relation.kind().name()));
            output.printf("      \"fromLogicalPath\": %s,%n",
                    json(portable(relation.fromLogicalPath())));
            output.printf("      \"toLogicalPath\": %s,%n",
                    json(portable(relation.toLogicalPath())));
            output.printf("      \"detail\": %s%n", json(relation.detail()));
            output.printf("    }%s%n", relations.hasNext() ? "," : "");
        }
        output.println("  ],");
        output.println("  \"findings\": [");
        Iterator<ContextFinding> findings = context.findings().iterator();
        while (findings.hasNext()) {
            ContextFinding finding = findings.next();
            output.println("    {");
            output.printf("      \"severity\": %s,%n", json(finding.severity().name()));
            output.printf("      \"code\": %s,%n", json(finding.code()));
            output.printf("      \"logicalPath\": %s,%n",
                    finding.logicalPath() == null ? "null" : json(portable(finding.logicalPath())));
            output.printf("      \"detail\": %s%n", json(finding.detail()));
            output.printf("    }%s%n", findings.hasNext() ? "," : "");
        }
        output.println("  ],");
        output.println("  \"limitations\": [");
        Iterator<String> limitations = context.limitations().iterator();
        while (limitations.hasNext()) {
            output.printf("    %s%s%n", json(limitations.next()), limitations.hasNext() ? "," : "");
        }
        output.println("  ]");
        output.println("}");
        output.flush();
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
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
