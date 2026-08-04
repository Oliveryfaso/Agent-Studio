package dev.agentconfig.workbench.cli;

import dev.agentconfig.workbench.analyze.AnalysisFinding;
import dev.agentconfig.workbench.analyze.AnalysisNotice;
import dev.agentconfig.workbench.analyze.AnalysisReference;
import dev.agentconfig.workbench.analyze.AnalysisSummary;
import dev.agentconfig.workbench.analyze.InstructionAnalysisReport;
import dev.agentconfig.workbench.ir.ActivationEvidence;
import dev.agentconfig.workbench.ir.DirectiveUnit;
import dev.agentconfig.workbench.ir.InstructionIr;
import dev.agentconfig.workbench.ir.InstructionSource;
import dev.agentconfig.workbench.ir.ProvenanceEdge;
import java.io.PrintWriter;
import java.util.Iterator;

/** Serializes the content-free analysis contract. Raw instruction text is never represented. */
final class AnalysisJsonWriter {
    private AnalysisJsonWriter() {}

    static void write(InstructionAnalysisReport report, PrintWriter output) {
        output.println("{");
        output.printf("  \"schemaVersion\": %d,%n", report.schemaVersion());
        output.printf("  \"contextSchemaVersion\": %d,%n", report.contextSchemaVersion());
        output.printf("  \"semanticProfile\": %s,%n", json(report.semanticProfile()));
        writeIr(report.instructionIr(), output);
        writeFindings(report.findings().iterator(), output);
        writeNotices(report.notices().iterator(), output);
        writeSummary(report.summary(), output);
        output.println("}");
        output.flush();
    }

    private static void writeIr(InstructionIr ir, PrintWriter output) {
        output.println("  \"instructionIr\": {");
        output.printf("    \"schemaVersion\": %d,%n", ir.schemaVersion());
        output.printf("    \"id\": %s,%n", json(ir.id()));
        output.printf("    \"resolutionStatus\": %s,%n", json(ir.resolutionStatus().name()));
        writeSources(ir.sources().iterator(), output);
        writeDirectives(ir.directives().iterator(), output);
        writeProvenance(ir.provenance().iterator(), output);
        output.println("    \"limitations\": [");
        Iterator<String> limitations = ir.limitations().iterator();
        while (limitations.hasNext()) {
            output.printf("      %s%s%n", json(limitations.next()), limitations.hasNext() ? "," : "");
        }
        output.println("    ]");
        output.println("  },");
    }

    private static void writeSources(Iterator<InstructionSource> sources, PrintWriter output) {
        output.println("    \"sources\": [");
        while (sources.hasNext()) {
            InstructionSource source = sources.next();
            output.println("      {");
            output.println("        \"identity\": {");
            output.printf("          \"hostId\": %s,%n", json(source.identity().hostId()));
            output.printf("          \"sourceId\": %s%n", json(source.identity().sourceId()));
            output.println("        },");
            output.printf("        \"kind\": %s,%n", json(source.kind().name()));
            output.printf("        \"state\": %s,%n", json(source.state().name()));
            output.printf("        \"revisionSha256\": %s,%n", nullableHash(source.revisionSha256()));
            output.printf("        \"effectiveSha256\": %s,%n", nullableHash(source.effectiveSha256()));
            output.printf("        \"byteSize\": %d,%n", source.byteSize());
            output.printf("        \"includedBytes\": %d,%n", source.includedBytes());
            output.printf("        \"logicalPath\": %s,%n", json(source.logicalPath()));
            output.println("        \"scope\": {");
            output.printf("          \"kind\": %s,%n", json(source.scope().kind().name()));
            output.printf("          \"expression\": %s%n", json(source.scope().expression()));
            output.println("        },");
            output.printf("        \"loadOrder\": %s,%n",
                    source.loadOrder() < 0 ? "null" : Integer.toString(source.loadOrder()));
            output.println("        \"activationEvidence\": [");
            Iterator<ActivationEvidence> evidence = source.activationEvidence().iterator();
            while (evidence.hasNext()) {
                ActivationEvidence item = evidence.next();
                output.println("          {");
                output.printf("            \"kind\": %s,%n", json(item.kind().name()));
                output.printf("            \"outcome\": %s,%n", json(item.outcome().name()));
                output.printf("            \"expression\": %s%n", json(item.expression()));
                output.printf("          }%s%n", evidence.hasNext() ? "," : "");
            }
            output.println("        ]");
            output.printf("      }%s%n", sources.hasNext() ? "," : "");
        }
        output.println("    ],");
    }

    private static void writeDirectives(Iterator<DirectiveUnit> directives, PrintWriter output) {
        output.println("    \"directives\": [");
        while (directives.hasNext()) {
            DirectiveUnit directive = directives.next();
            output.println("      {");
            output.printf("        \"id\": %s,%n", json(directive.id()));
            output.println("        \"source\": {");
            output.printf("          \"hostId\": %s,%n", json(directive.source().hostId()));
            output.printf("          \"sourceId\": %s%n", json(directive.source().sourceId()));
            output.println("        },");
            output.printf("        \"normalizedSha256\": %s,%n", json(directive.normalizedHash()));
            output.printf("        \"polarity\": %s,%n", json(directive.polarity().name()));
            output.printf("        \"line\": %d%n", directive.line());
            output.printf("      }%s%n", directives.hasNext() ? "," : "");
        }
        output.println("    ],");
    }

    private static void writeProvenance(Iterator<ProvenanceEdge> edges, PrintWriter output) {
        output.println("    \"provenance\": [");
        while (edges.hasNext()) {
            ProvenanceEdge edge = edges.next();
            output.println("      {");
            output.printf("        \"kind\": %s,%n", json(edge.kind().name()));
            output.println("        \"from\": {");
            output.printf("          \"kind\": %s,%n", json(edge.from().kind().name()));
            output.printf("          \"id\": %s%n", json(edge.from().id()));
            output.println("        },");
            output.println("        \"to\": {");
            output.printf("          \"kind\": %s,%n", json(edge.to().kind().name()));
            output.printf("          \"id\": %s%n", json(edge.to().id()));
            output.println("        }");
            output.printf("      }%s%n", edges.hasNext() ? "," : "");
        }
        output.println("    ],");
    }

    private static void writeFindings(Iterator<AnalysisFinding> findings, PrintWriter output) {
        output.println("  \"findings\": [");
        while (findings.hasNext()) {
            AnalysisFinding finding = findings.next();
            output.println("    {");
            output.printf("      \"id\": %s,%n", json(finding.id()));
            output.printf("      \"type\": %s,%n", json(finding.type().name()));
            output.printf("      \"certainty\": %s,%n", json(finding.certainty().name()));
            output.printf("      \"evidenceSha256\": %s,%n", json(finding.evidenceHash()));
            output.println("      \"references\": [");
            Iterator<AnalysisReference> references = finding.references().iterator();
            while (references.hasNext()) {
                AnalysisReference reference = references.next();
                output.println("        {");
                output.printf("          \"sourceId\": %s,%n", json(reference.sourceId()));
                output.printf("          \"directiveId\": %s,%n",
                        reference.directiveId().isEmpty() ? "null" : json(reference.directiveId()));
                output.printf("          \"line\": %s%n",
                        reference.line() == 0 ? "null" : Integer.toString(reference.line()));
                output.printf("        }%s%n", references.hasNext() ? "," : "");
            }
            output.println("      ]");
            output.printf("    }%s%n", findings.hasNext() ? "," : "");
        }
        output.println("  ],");
    }

    private static void writeNotices(Iterator<AnalysisNotice> notices, PrintWriter output) {
        output.println("  \"notices\": [");
        while (notices.hasNext()) {
            AnalysisNotice notice = notices.next();
            output.println("    {");
            output.printf("      \"code\": %s,%n", json(notice.code()));
            output.printf("      \"sourceId\": %s,%n", json(notice.sourceId()));
            output.printf("      \"line\": %s%n",
                    notice.line() == 0 ? "null" : Integer.toString(notice.line()));
            output.printf("    }%s%n", notices.hasNext() ? "," : "");
        }
        output.println("  ],");
    }

    private static void writeSummary(AnalysisSummary summary, PrintWriter output) {
        output.println("  \"summary\": {");
        output.printf("    \"sourceCount\": %d,%n", summary.sourceCount());
        output.printf("    \"activeSourceCount\": %d,%n", summary.activeSourceCount());
        output.printf("    \"directiveCount\": %d,%n", summary.directiveCount());
        output.printf("    \"deterministicFindingCount\": %d,%n",
                summary.deterministicFindingCount());
        output.printf("    \"heuristicFindingCount\": %d%n", summary.heuristicFindingCount());
        output.println("  }");
    }

    private static String nullableHash(String value) {
        return value.isEmpty() ? "null" : json(value);
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
