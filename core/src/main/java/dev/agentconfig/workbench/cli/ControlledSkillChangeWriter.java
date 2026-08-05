package dev.agentconfig.workbench.cli;

import dev.agentconfig.workbench.transaction.ControlledSkillApplyReceipt;
import dev.agentconfig.workbench.transaction.ControlledSkillChangePlan;
import dev.agentconfig.workbench.transaction.ControlledSkillRollbackReceipt;
import java.io.PrintWriter;

/** Content-free JSON receipts plus an explicit local exact-Diff export. */
final class ControlledSkillChangeWriter {
    private ControlledSkillChangeWriter() {}

    static void writePlan(ControlledSkillChangePlan plan, PrintWriter output) {
        output.println("{");
        output.println("  \"schemaVersion\": 1,");
        output.println("  \"command\": \"skill-change-preview\",");
        output.println("  \"hostId\": \"codex\",");
        output.printf("  \"planId\": %s,%n", json(plan.id()));
        output.printf("  \"status\": %s,%n", json(plan.status().name()));
        output.printf("  \"logicalPath\": %s,%n", json(plan.logicalPath()));
        output.printf("  \"candidateSha256\": %s,%n", json(plan.candidateSha256()));
        output.printf("  \"preimageSha256\": %s,%n",
                plan.preimageSha256().map(ControlledSkillChangeWriter::json).orElse("null"));
        output.printf("  \"diffSha256\": %s,%n",
                plan.diffSha256().map(ControlledSkillChangeWriter::json).orElse("null"));
        output.printf("  \"approvalToken\": %s,%n",
                plan.approvalToken().map(ControlledSkillChangeWriter::json).orElse("null"));
        output.printf("  \"blockedReason\": %s,%n",
                plan.blockedReason().map(ControlledSkillChangeWriter::json).orElse("null"));
        output.println("  \"existingTargetRequired\": true,");
        output.println("  \"contentIncluded\": false,");
        output.println("  \"writesPerformed\": false,");
        output.printf("  \"applyEligible\": %s%n", plan.applyEligible());
        output.println("}");
        output.flush();
    }

    static void writeDiff(ControlledSkillChangePlan plan, String diff, PrintWriter output) {
        output.printf("# planId=%s%n", plan.id());
        output.printf("# approvalToken=%s%n", plan.approvalToken().orElseThrow());
        output.printf("# candidateSha256=%s%n", plan.candidateSha256());
        output.print(diff);
        output.flush();
    }

    static void writeApplyReceipt(ControlledSkillApplyReceipt receipt, PrintWriter output) {
        output.println("{");
        output.println("  \"schemaVersion\": 1,");
        output.println("  \"command\": \"skill-change-apply\",");
        output.printf("  \"status\": %s,%n", json(receipt.status().name()));
        output.printf("  \"transactionId\": %s,%n",
                receipt.transactionId().map(ControlledSkillChangeWriter::json).orElse("null"));
        output.printf("  \"planId\": %s,%n", json(receipt.planId()));
        output.printf("  \"logicalPath\": %s,%n", json(receipt.logicalPath()));
        output.printf("  \"targetWritesPerformed\": %s,%n",
                receipt.targetWritesPerformed());
        output.printf("  \"stateWritesPerformed\": %s,%n",
                receipt.stateWritesPerformed());
        output.printf("  \"rollbackAvailable\": %s,%n", receipt.rollbackAvailable());
        output.printf("  \"recoveryRequired\": %s,%n", receipt.recoveryRequired());
        output.printf("  \"detail\": %s%n", json(receipt.detail()));
        output.println("}");
        output.flush();
    }

    static void writeRollbackReceipt(ControlledSkillRollbackReceipt receipt,
            PrintWriter output) {
        output.println("{");
        output.println("  \"schemaVersion\": 1,");
        output.println("  \"command\": \"skill-change-rollback\",");
        output.printf("  \"status\": %s,%n", json(receipt.status().name()));
        output.printf("  \"transactionId\": %s,%n", json(receipt.transactionId()));
        output.printf("  \"logicalPath\": %s,%n", json(receipt.logicalPath()));
        output.printf("  \"targetWritesPerformed\": %s,%n",
                receipt.targetWritesPerformed());
        output.printf("  \"stateWritesPerformed\": %s,%n",
                receipt.stateWritesPerformed());
        output.printf("  \"recoveryRequired\": %s,%n", receipt.recoveryRequired());
        output.printf("  \"detail\": %s%n", json(receipt.detail()));
        output.println("}");
        output.flush();
    }

    private static String json(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
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
