package dev.agentconfig.workbench.localweb;

import dev.agentconfig.workbench.blueprint.BlueprintPreviewService;
import dev.agentconfig.workbench.skilldraft.CodexSkillDraftPreview;
import dev.agentconfig.workbench.skilldraft.CodexSkillDraftService;
import dev.agentconfig.workbench.transaction.ControlledExistingSkillService;
import dev.agentconfig.workbench.transaction.ControlledSkillApplyReceipt;
import dev.agentconfig.workbench.transaction.ControlledSkillChangePlan;
import dev.agentconfig.workbench.transaction.ControlledSkillRollbackReceipt;
import dev.agentconfig.workbench.transaction.PreparedControlledSkillChange;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Typed HTTP projection over the deterministic existing-Skill workflow. */
final class SkillChangeHttpApi {
    private final Path stateRoot;

    SkillChangeHttpApi(Path stateRoot) {
        this.stateRoot = stateRoot;
    }

    ApiResponse preview(Map<String, Object> request, String requestId) {
        try {
            allowKeys(request, Set.of("hostId", "workspacePath", "guidedRequest", "includeDiff"));
            requireHost(request);
            Path workspace = path(request, "workspacePath");
            String guidedRequest = text(request, "guidedRequest", 32 * 1024);
            boolean includeDiff = bool(request, "includeDiff", false);
            PreparedControlledSkillChange prepared = new ControlledExistingSkillService()
                    .prepare(workspace, draft(guidedRequest));
            ControlledSkillChangePlan plan = prepared.plan();
            int status = switch (plan.status()) {
                case READY_REPLACE, NO_CHANGE -> 200;
                case BLOCKED -> 422;
            };
            String canonicalRoot = plan.status() == ControlledSkillChangePlan.Status.BLOCKED
                    ? null : canonical(workspace).orElse(null);
            String target = canonicalRoot == null ? null
                    : Path.of(canonicalRoot).resolve(plan.logicalPath()).normalize().toString();
            String diff = includeDiff && plan.applyEligible()
                    ? prepared.exactReplacementDiff().orElseThrow() : null;
            StringBuilder json = begin(requestId, "skill-change-preview")
                    .append("  \"hostId\": \"codex\",\n")
                    .append("  \"authorizedRoot\": ").append(nullable(canonicalRoot)).append(",\n")
                    .append("  \"targetPath\": ").append(nullable(target)).append(",\n")
                    .append("  \"plan\": {\n")
                    .append("    \"planId\": ").append(json(plan.id())).append(",\n")
                    .append("    \"status\": ").append(json(plan.status().name())).append(",\n")
                    .append("    \"logicalPath\": ").append(json(plan.logicalPath())).append(",\n")
                    .append("    \"candidateSha256\": ").append(json(plan.candidateSha256())).append(",\n")
                    .append("    \"preimageSha256\": ").append(optional(plan.preimageSha256())).append(",\n")
                    .append("    \"diffSha256\": ").append(optional(plan.diffSha256())).append(",\n")
                    .append("    \"approvalToken\": ").append(optional(plan.approvalToken())).append(",\n")
                    .append("    \"blockedReason\": ").append(optional(plan.blockedReason())).append(",\n")
                    .append("    \"existingTargetRequired\": true,\n")
                    .append("    \"applyEligible\": ").append(plan.applyEligible()).append(",\n")
                    .append("    \"writesPerformed\": false\n")
                    .append("  },\n")
                    .append("  \"diffIncluded\": ").append(diff != null).append(",\n")
                    .append("  \"exactReplacementDiff\": ").append(nullable(diff)).append("\n}");
            return new ApiResponse(status, json.toString());
        } catch (IllegalArgumentException exception) {
            return error(400, requestId, "INPUT_INVALID", false);
        } catch (IOException exception) {
            return error(500, requestId, "CORE_IO_FAILED", true);
        }
    }

    ApiResponse apply(Map<String, Object> request, String requestId) {
        try {
            allowKeys(request, Set.of("hostId", "workspacePath", "guidedRequest",
                    "approvalToken"));
            requireHost(request);
            Path workspace = path(request, "workspacePath");
            String guidedRequest = text(request, "guidedRequest", 32 * 1024);
            String approvalToken = text(request, "approvalToken", 128);
            ControlledSkillApplyReceipt receipt = new ControlledExistingSkillService().apply(
                    workspace, stateRoot, draft(guidedRequest), approvalToken);
            int status = switch (receipt.status()) {
                case VERIFIED_APPLIED -> 200;
                case APPROVAL_MISMATCH, STALE_PREIMAGE -> 409;
                case BLOCKED -> 422;
                case WRITE_FAILED -> 500;
                case RECOVERY_REQUIRED -> 503;
            };
            String json = begin(requestId, "skill-change-apply")
                    .append("  \"status\": ").append(json(receipt.status().name())).append(",\n")
                    .append("  \"transactionId\": ").append(optional(receipt.transactionId())).append(",\n")
                    .append("  \"planId\": ").append(json(receipt.planId())).append(",\n")
                    .append("  \"logicalPath\": ").append(json(receipt.logicalPath())).append(",\n")
                    .append("  \"targetWritesPerformed\": ").append(receipt.targetWritesPerformed()).append(",\n")
                    .append("  \"stateWritesPerformed\": ").append(receipt.stateWritesPerformed()).append(",\n")
                    .append("  \"rollbackAvailable\": ").append(receipt.rollbackAvailable()).append(",\n")
                    .append("  \"recoveryRequired\": ").append(receipt.recoveryRequired()).append(",\n")
                    .append("  \"detail\": ").append(json(receipt.detail())).append("\n}").toString();
            return new ApiResponse(status, json);
        } catch (IllegalArgumentException exception) {
            return error(400, requestId, "INPUT_INVALID", false);
        } catch (IOException exception) {
            return error(500, requestId, "CORE_IO_FAILED", true);
        }
    }

    ApiResponse rollback(Map<String, Object> request, String requestId) {
        try {
            allowKeys(request, Set.of("hostId", "workspacePath", "transactionId"));
            requireHost(request);
            ControlledSkillRollbackReceipt receipt = new ControlledExistingSkillService().rollback(
                    path(request, "workspacePath"), stateRoot,
                    text(request, "transactionId", 64));
            int status = switch (receipt.status()) {
                case ROLLED_BACK, ALREADY_ROLLED_BACK -> 200;
                case CURRENT_TARGET_CHANGED -> 409;
                case INVALID_TRANSACTION -> 404;
                case WRITE_FAILED -> 500;
                case RECOVERY_REQUIRED -> 503;
            };
            String json = begin(requestId, "skill-change-rollback")
                    .append("  \"status\": ").append(json(receipt.status().name())).append(",\n")
                    .append("  \"transactionId\": ").append(json(receipt.transactionId())).append(",\n")
                    .append("  \"logicalPath\": ").append(json(receipt.logicalPath())).append(",\n")
                    .append("  \"targetWritesPerformed\": ").append(receipt.targetWritesPerformed()).append(",\n")
                    .append("  \"stateWritesPerformed\": ").append(receipt.stateWritesPerformed()).append(",\n")
                    .append("  \"recoveryRequired\": ").append(receipt.recoveryRequired()).append(",\n")
                    .append("  \"detail\": ").append(json(receipt.detail())).append("\n}").toString();
            return new ApiResponse(status, json);
        } catch (IllegalArgumentException exception) {
            return error(400, requestId, "INPUT_INVALID", false);
        } catch (IOException exception) {
            return error(500, requestId, "CORE_IO_FAILED", true);
        }
    }

    private static CodexSkillDraftPreview draft(String guidedRequest) throws IOException {
        return new CodexSkillDraftService().draft(new BlueprintPreviewService().preview(
                new ByteArrayInputStream(guidedRequest.getBytes(StandardCharsets.UTF_8))));
    }

    private static void requireHost(Map<String, Object> request) {
        if (!"codex".equals(text(request, "hostId", 32))) {
            throw new IllegalArgumentException("hostId");
        }
    }

    private static void allowKeys(Map<String, Object> request, Set<String> allowed) {
        if (!allowed.containsAll(request.keySet())) throw new IllegalArgumentException("field");
    }

    private static Path path(Map<String, Object> request, String field) {
        return Path.of(text(request, field, 4096));
    }

    private static String text(Map<String, Object> request, String field, int maxLength) {
        Object value = request.get(field);
        if (!(value instanceof String text) || text.isBlank() || text.length() > maxLength) {
            throw new IllegalArgumentException(field);
        }
        return text;
    }

    private static boolean bool(Map<String, Object> request, String field, boolean fallback) {
        Object value = request.get(field);
        if (value == null) return fallback;
        if (!(value instanceof Boolean bool)) throw new IllegalArgumentException(field);
        return bool;
    }

    private static Optional<String> canonical(Path path) {
        try {
            return Optional.of(path.toRealPath().toString());
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private static StringBuilder begin(String requestId, String command) {
        return new StringBuilder("{\n  \"schemaVersion\": 1,\n")
                .append("  \"requestId\": ").append(json(requestId)).append(",\n")
                .append("  \"command\": ").append(json(command)).append(",\n");
    }

    private static ApiResponse error(int status, String requestId, String code,
            boolean retryable) {
        String body = "{\n  \"schemaVersion\": 1,\n  \"requestId\": " + json(requestId)
                + ",\n  \"error\": {\"code\": " + json(code)
                + ", \"retryable\": " + retryable + "}\n}";
        return new ApiResponse(status, body);
    }

    private static String optional(Optional<String> value) {
        return value.map(SkillChangeHttpApi::json).orElse("null");
    }

    private static String nullable(String value) {
        return value == null ? "null" : json(value);
    }

    static String json(String value) {
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

    record ApiResponse(int statusCode, String body) {}
}
