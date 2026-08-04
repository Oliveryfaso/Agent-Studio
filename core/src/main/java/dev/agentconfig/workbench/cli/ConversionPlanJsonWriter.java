package dev.agentconfig.workbench.cli;

import dev.agentconfig.workbench.conversion.CapabilityDelta;
import dev.agentconfig.workbench.conversion.ConversionPlan;
import dev.agentconfig.workbench.conversion.LossItem;
import dev.agentconfig.workbench.conversion.MappingItem;
import dev.agentconfig.workbench.conversion.TargetCandidate;
import dev.agentconfig.workbench.conversion.UnresolvedQuestion;
import dev.agentconfig.workbench.ir.IrNodeRef;
import java.io.PrintWriter;
import java.util.Iterator;

/** Serializes ConversionPlan v2 without raw source, candidate, or existing-target content. */
final class ConversionPlanJsonWriter {
    private ConversionPlanJsonWriter() {}

    static void write(ConversionPlan plan, PrintWriter output) {
        output.println("{");
        output.printf("  \"schemaVersion\": %d,%n", plan.schemaVersion());
        output.printf("  \"id\": %s,%n", json(plan.id()));
        output.printf("  \"operation\": %s,%n", json(plan.operation().name()));
        output.printf("  \"writesPerformed\": %s,%n", plan.writesPerformed());
        output.printf("  \"applyEligible\": %s,%n", plan.applyEligible());
        output.printf("  \"status\": %s,%n", json(plan.status().name()));
        output.println("  \"request\": {");
        output.printf("    \"id\": %s,%n", json(plan.request().id()));
        output.printf("    \"sourceIrId\": %s,%n", json(plan.request().sourceIrId()));
        output.printf("    \"sourceIrSha256\": %s,%n", json(plan.request().sourceIrSha256()));
        output.printf("    \"sourceIrSchemaVersion\": %d,%n", plan.request().sourceIrSchemaVersion());
        output.printf("    \"sourceResolutionStatus\": %s,%n",
                json(plan.request().sourceResolutionStatus().name()));
        output.printf("    \"sourceSemanticProfile\": %s,%n",
                json(plan.request().sourceSemanticProfile()));
        output.printf("    \"targetSemanticProfile\": %s,%n",
                json(plan.request().targetSemanticProfile()));
        output.println("    \"recipe\": {");
        output.printf("      \"id\": %s,%n", json(plan.request().recipe().id()));
        output.printf("      \"version\": %d%n", plan.request().recipe().version());
        output.println("    }");
        output.println("  },");
        writeMappings(plan.mappings().iterator(), output);
        writeLosses(plan.losses().iterator(), output);
        writeQuestions(plan.unresolvedQuestions().iterator(), output);
        output.println("}");
        output.flush();
    }

    private static void writeMappings(Iterator<MappingItem> values, PrintWriter output) {
        output.println("  \"mappings\": [");
        while (values.hasNext()) {
            MappingItem mapping = values.next();
            output.println("    {");
            output.printf("      \"id\": %s,%n", json(mapping.id()));
            output.printf("      \"grade\": %s,%n", json(mapping.grade().name()));
            writeRefs("sourceProvenance", mapping.sourceProvenance().iterator(), output, 6, true);
            if (mapping.targetCandidate().isPresent()) {
                writeCandidate(mapping.targetCandidate().orElseThrow(), output);
            } else {
                output.println("      \"targetCandidate\": null,");
            }
            writeCapability(mapping.capabilityDelta(), output);
            writeStrings("lossIds", mapping.lossIds().iterator(), output, 6, true);
            writeStrings("unresolvedQuestionIds", mapping.unresolvedQuestionIds().iterator(),
                    output, 6, false);
            output.printf("    }%s%n", values.hasNext() ? "," : "");
        }
        output.println("  ],");
    }

    private static void writeCandidate(TargetCandidate candidate, PrintWriter output) {
        output.println("      \"targetCandidate\": {");
        output.printf("        \"logicalPath\": %s,%n", json(candidate.logicalPath()));
        output.printf("        \"renderState\": %s,%n", json(candidate.renderState().name()));
        output.printf("        \"candidateSha256\": %s,%n", nullable(candidate.candidateSha256()));
        output.printf("        \"candidateByteSize\": %s,%n",
                candidate.candidateByteSize() < 0 ? "null" : Long.toString(candidate.candidateByteSize()));
        output.printf("        \"rendererProfile\": %s,%n",
                nullable(candidate.rendererProfile()));
        output.printf("        \"conflictState\": %s,%n", json(candidate.conflictState().name()));
        output.printf("        \"existingTargetSha256\": %s,%n",
                nullable(candidate.existingTargetSha256()));
        output.printf("        \"existingTargetByteSize\": %s,%n",
                candidate.existingTargetByteSize() < 0 ? "null"
                        : Long.toString(candidate.existingTargetByteSize()));
        output.printf("        \"targetValidation\": %s,%n",
                json(candidate.targetValidation().name()));
        output.printf("        \"targetValidatorProfile\": %s,%n",
                nullable(candidate.targetValidatorProfile()));
        output.printf("        \"targetValidationSubjectSha256\": %s,%n",
                nullable(candidate.targetValidationSubjectSha256()));
        output.printf("        \"semanticRoundTrip\": %s,%n",
                json(candidate.semanticRoundTrip().name()));
        output.printf("        \"semanticRoundTripProfile\": %s,%n",
                nullable(candidate.semanticRoundTripProfile()));
        output.printf("        \"semanticRoundTripSubjectSha256\": %s,%n",
                nullable(candidate.semanticRoundTripSubjectSha256()));
        output.printf("        \"threeWayReview\": %s,%n",
                json(candidate.threeWayReview().name()));
        output.printf("        \"threeWayReviewProfile\": %s,%n",
                nullable(candidate.threeWayReviewProfile()));
        output.printf("        \"threeWayReviewSubjectSha256\": %s%n",
                nullable(candidate.threeWayReviewSubjectSha256()));
        output.println("      },");
    }

    private static void writeCapability(CapabilityDelta delta, PrintWriter output) {
        output.println("      \"capabilityDelta\": {");
        output.printf("        \"tools\": %s,%n", json(delta.tools().name()));
        output.printf("        \"permissions\": %s,%n", json(delta.permissions().name()));
        output.printf("        \"network\": %s,%n", json(delta.network().name()));
        output.printf("        \"modelInvocation\": %s,%n", json(delta.modelInvocation().name()));
        output.printf("        \"automaticInvocation\": %s,%n",
                json(delta.automaticInvocation().name()));
        output.printf("        \"executableBehavior\": %s%n",
                json(delta.executableBehavior().name()));
        output.println("      },");
    }

    private static void writeLosses(Iterator<LossItem> values, PrintWriter output) {
        output.println("  \"losses\": [");
        while (values.hasNext()) {
            LossItem loss = values.next();
            output.println("    {");
            output.printf("      \"id\": %s,%n", json(loss.id()));
            output.printf("      \"code\": %s,%n", json(loss.code()));
            output.printf("      \"severity\": %s,%n", json(loss.severity().name()));
            output.printf("      \"summary\": %s,%n", json(loss.summary()));
            writeRefs("provenance", loss.provenance().iterator(), output, 6, false);
            output.printf("    }%s%n", values.hasNext() ? "," : "");
        }
        output.println("  ],");
    }

    private static void writeQuestions(Iterator<UnresolvedQuestion> values, PrintWriter output) {
        output.println("  \"unresolvedQuestions\": [");
        while (values.hasNext()) {
            UnresolvedQuestion question = values.next();
            output.println("    {");
            output.printf("      \"id\": %s,%n", json(question.id()));
            output.printf("      \"code\": %s,%n", json(question.code()));
            output.printf("      \"prompt\": %s,%n", json(question.prompt()));
            writeRefs("provenance", question.provenance().iterator(), output, 6, false);
            output.printf("    }%s%n", values.hasNext() ? "," : "");
        }
        output.println("  ]");
    }

    private static void writeRefs(
            String name, Iterator<IrNodeRef> values, PrintWriter output, int spaces, boolean comma) {
        String indent = " ".repeat(spaces);
        output.printf("%s\"%s\": [%n", indent, name);
        while (values.hasNext()) {
            IrNodeRef ref = values.next();
            output.printf("%s  {\"kind\": %s, \"id\": %s}%s%n", indent,
                    json(ref.kind().name()), json(ref.id()), values.hasNext() ? "," : "");
        }
        output.printf("%s]%s%n", indent, comma ? "," : "");
    }

    private static void writeStrings(
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

    private static String nullable(String value) {
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
