package dev.agentconfig.workbench.localweb;

import dev.agentconfig.workbench.blueprint.BlueprintPreviewService;
import dev.agentconfig.workbench.skill.CodexSkillInventory;
import dev.agentconfig.workbench.skill.CodexSkillInventoryService;
import dev.agentconfig.workbench.skill.SkillTaxonomyReport;
import dev.agentconfig.workbench.skill.SkillTaxonomyService;
import dev.agentconfig.workbench.skill.CodexSkillContent;
import dev.agentconfig.workbench.skill.CodexSkillContentService;
import dev.agentconfig.workbench.skilldraft.CodexSkillFormProjection;
import dev.agentconfig.workbench.skilldraft.CodexSkillDraftPreview;
import dev.agentconfig.workbench.skilldraft.CodexSkillDraftService;
import dev.agentconfig.workbench.transaction.ControlledExistingSkillService;
import dev.agentconfig.workbench.transaction.ControlledExistingSkillService.Mode;
import dev.agentconfig.workbench.transaction.ControlledSkillApplyReceipt;
import dev.agentconfig.workbench.transaction.ControlledSkillChangePlan;
import dev.agentconfig.workbench.transaction.ControlledSkillRollbackReceipt;
import dev.agentconfig.workbench.transaction.ControlledSkillCandidate;
import dev.agentconfig.workbench.transaction.RawCodexSkillCandidateService;
import dev.agentconfig.workbench.transaction.PreparedControlledSkillChange;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Typed HTTP projection over Codex Skill inventory and the single-Skill workflow. */
final class SkillChangeHttpApi {
    private final Path stateRoot;

    SkillChangeHttpApi(Path stateRoot) {
        this.stateRoot = stateRoot;
    }

    ApiResponse inventory(Map<String, Object> request, String requestId) {
        try {
            allowKeys(request, Set.of("hostId", "workspacePath"));
            requireHost(request);
            Path workspace = path(request, "workspacePath");
            if (!Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("workspacePath");
            }
            CodexSkillInventory inventory = new CodexSkillInventoryService().inspect(workspace);
            long warnings = inventory.findings().stream()
                    .filter(finding -> finding.severity() == CodexSkillInventory.Severity.WARNING)
                    .count();
            long errors = inventory.findings().stream()
                    .filter(finding -> finding.severity() == CodexSkillInventory.Severity.ERROR)
                    .count();
            long blocking = inventory.findings().stream()
                    .filter(finding -> finding.severity() == CodexSkillInventory.Severity.BLOCKING)
                    .count();
            StringBuilder result = begin(requestId, "skill-inventory")
                    .append("  \"hostId\": \"codex\",\n")
                    .append("  \"status\": ").append(json(inventory.status().name())).append(",\n")
                    .append("  \"contentIncluded\": false,\n")
                    .append("  \"writesPerformed\": false,\n")
                    .append("  \"skills\": [\n");
            for (int index = 0; index < inventory.packages().size(); index++) {
                CodexSkillInventory.SkillPackage skill = inventory.packages().get(index);
                result.append("    {\"name\": ").append(json(skill.directoryName()))
                        .append(", \"logicalPath\": ").append(json(skill.logicalPath()))
                        .append(", \"state\": ").append(json(skill.state().name()))
                        .append(", \"availableForPreview\": ")
                        .append(skill.state() != CodexSkillInventory.PackageState.PARTIAL)
                        .append(", \"supportingFileCount\": ")
                        .append(skill.supportingFileCount()).append(", \"risks\": [");
                var risks = skill.risks().stream().sorted().toList();
                for (int riskIndex = 0; riskIndex < risks.size(); riskIndex++) {
                    if (riskIndex > 0) result.append(", ");
                    result.append(json(risks.get(riskIndex).name()));
                }
                result.append("]}").append(index + 1 < inventory.packages().size() ? ",\n" : "\n");
            }
            result.append("  ],\n  \"findingCounts\": {\"warning\": ").append(warnings)
                    .append(", \"error\": ").append(errors)
                    .append(", \"blocking\": ").append(blocking).append("}\n}");
            return new ApiResponse(200, result.toString());
        } catch (IllegalArgumentException exception) {
            return error(400, requestId, "INPUT_INVALID", false);
        } catch (IOException exception) {
            return error(500, requestId, "CORE_IO_FAILED", true);
        }
    }

    ApiResponse content(Map<String, Object> request, String requestId) {
        try {
            allowKeys(request, Set.of("hostId", "workspacePath", "logicalPath"));
            requireHost(request);
            CodexSkillContent content = new CodexSkillContentService().read(
                    path(request, "workspacePath"), text(request, "logicalPath", 256));
            CodexSkillFormProjection projection = content.projection();
            StringBuilder result = begin(requestId, "skill-content")
                    .append("  \"hostId\": \"codex\",\n")
                    .append("  \"status\": ").append(json(projection.status().name())).append(",\n")
                    .append("  \"logicalPath\": ").append(json(content.logicalPath())).append(",\n")
                    .append("  \"sourceSha256\": ").append(json(content.sha256())).append(",\n")
                    .append("  \"byteSize\": ").append(content.byteSize()).append(",\n")
                    .append("  \"rendererProfileId\": ")
                    .append(json(projection.rendererProfileId())).append(",\n")
                    .append("  \"missingFormFields\": [");
            for (int index = 0; index < projection.missingFields().size(); index++) {
                if (index > 0) result.append(", ");
                result.append(json(projection.missingFields().get(index)));
            }
            result.append("],\n  \"losses\": [");
            if (projection.status() == CodexSkillFormProjection.Status.PARTIAL_FORM) {
                result.append("\"ROUTING_EVAL_CASES_NOT_PERSISTED\", ")
                        .append("\"DESCRIPTION_LIST_BOUNDARIES_FLATTENED\"");
            }
            result.append("],\n  \"form\": ");
            if (projection.form().isPresent()) appendForm(result, projection.form().orElseThrow());
            else result.append("null");
            result.append(",\n  \"rawContent\": ").append(json(content.content())).append(",\n")
                    .append("  \"contentIncluded\": true,\n")
                    .append("  \"writesPerformed\": false\n}");
            return new ApiResponse(200, result.toString());
        } catch (IllegalArgumentException exception) {
            return error(400, requestId, "INPUT_INVALID", false);
        } catch (CodexSkillContentService.UnavailableException exception) {
            return error(404, requestId, "SKILL_NOT_AVAILABLE", false);
        } catch (CodexSkillContentService.ChangedException exception) {
            return error(409, requestId, "TARGET_CHANGED_SINCE_INVENTORY", true);
        } catch (CodexSkillContentService.TooLargeException exception) {
            return error(422, requestId, "CONTENT_TOO_LARGE", false);
        } catch (CodexSkillContentService.InvalidUtf8Exception exception) {
            return error(422, requestId, "CONTENT_NOT_UTF8", false);
        } catch (IOException exception) {
            return error(500, requestId, "CORE_IO_FAILED", true);
        }
    }

    ApiResponse classifications(Map<String, Object> request, String requestId) {
        try {
            allowKeys(request, Set.of("hostId", "workspacePath"));
            requireHost(request);
            SkillTaxonomyReport report = new SkillTaxonomyService().classify(
                    path(request, "workspacePath"));
            StringBuilder result = begin(requestId, "skill-classifications")
                    .append("  \"hostId\": \"codex\",\n")
                    .append("  \"classifierProfileId\": ")
                    .append(json(report.classifierProfileId())).append(",\n")
                    .append("  \"status\": ").append(json(report.status().name())).append(",\n")
                    .append("  \"contentIncluded\": false,\n")
                    .append("  \"writesPerformed\": false,\n")
                    .append("  \"llmUsed\": false,\n")
                    .append("  \"categories\": [");
            for (int index = 0; index < report.categories().size(); index++) {
                if (index > 0) result.append(", ");
                result.append(json(report.categories().get(index).name()));
            }
            result.append("],\n  \"skills\": [\n");
            for (int index = 0; index < report.skills().size(); index++) {
                SkillTaxonomyReport.Classification skill = report.skills().get(index);
                result.append("    {\"name\": ").append(json(skill.name()))
                        .append(", \"logicalPath\": ").append(json(skill.logicalPath()))
                        .append(", \"sourceSha256\": ").append(json(skill.sourceSha256()))
                        .append(", \"category\": ")
                        .append(skill.category() == null ? "null" : json(skill.category().name()))
                        .append(", \"confidence\": ").append(json(skill.confidence().name()))
                        .append(", \"score\": ").append(skill.score())
                        .append(", \"margin\": ").append(skill.margin())
                        .append(", \"evidenceSources\": [");
                for (int evidence = 0; evidence < skill.evidenceSources().size(); evidence++) {
                    if (evidence > 0) result.append(", ");
                    result.append(json(skill.evidenceSources().get(evidence).name()));
                }
                result.append("]}").append(index + 1 < report.skills().size() ? ",\n" : "\n");
            }
            result.append("  ],\n  \"unclassifiedCount\": ")
                    .append(report.unclassifiedCount()).append("\n}");
            return new ApiResponse(200, result.toString());
        } catch (IllegalArgumentException exception) {
            return error(400, requestId, "INPUT_INVALID", false);
        } catch (IOException exception) {
            return error(500, requestId, "CORE_IO_FAILED", true);
        }
    }

    ApiResponse preview(Map<String, Object> request, String requestId) {
        try {
            allowKeys(request, Set.of("hostId", "workspacePath", "guidedRequest", "includeDiff",
                    "operation", "expectedPreimageSha256", "candidateMode", "logicalPath",
                    "rawContent"));
            requireHost(request);
            Path workspace = path(request, "workspacePath");
            boolean includeDiff = bool(request, "includeDiff", false);
            Mode mode = mode(request);
            CandidateMode candidateMode = candidateMode(request);
            if (candidateMode == CandidateMode.RAW_SKILL_MD && mode != Mode.UPDATE_EXISTING) {
                throw new IllegalArgumentException("raw create");
            }
            ControlledExistingSkillService service = new ControlledExistingSkillService();
            PreparedControlledSkillChange prepared = candidateMode == CandidateMode.RAW_SKILL_MD
                    ? service.prepare(workspace, rawCandidate(request), mode)
                    : service.prepare(workspace, draft(guidedRequest(request)), mode);
            ControlledSkillChangePlan plan = prepared.plan();
            Optional<String> expectedPreimage = optionalText(
                    request, "expectedPreimageSha256", 64);
            if (candidateMode == CandidateMode.RAW_SKILL_MD
                    && (expectedPreimage.isEmpty()
                    || !expectedPreimage.orElseThrow().matches("[0-9a-f]{64}"))) {
                throw new IllegalArgumentException("expectedPreimageSha256");
            }
            if (mode == Mode.UPDATE_EXISTING && expectedPreimage.isPresent()
                    && plan.preimageSha256().isPresent()
                    && !plan.preimageSha256().equals(expectedPreimage)) {
                return error(409, requestId, "LOADED_CONTENT_STALE", true);
            }
            int status = switch (plan.status()) {
                case READY_CREATE, READY_REPLACE, NO_CHANGE -> 200;
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
                    .append("  \"candidateMode\": ").append(json(candidateMode.name())).append(",\n")
                    .append("  \"validationProfileId\": ")
                    .append(json(candidateMode == CandidateMode.RAW_SKILL_MD
                            ? RawCodexSkillCandidateService.VALIDATION_PROFILE
                            : "codex-project-skill-static-v1")).append(",\n")
                    .append("  \"operation\": ").append(json(operation(mode))).append(",\n")
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
                    .append("    \"missingParentDirectories\": [");
            for (int index = 0; index < plan.missingParentDirectories().size(); index++) {
                if (index > 0) json.append(", ");
                json.append(json(plan.missingParentDirectories().get(index)));
            }
            json.append("],\n")
                    .append("    \"existingTargetRequired\": ")
                    .append(mode == Mode.UPDATE_EXISTING).append(",\n")
                    .append("    \"applyEligible\": ").append(plan.applyEligible()).append(",\n")
                    .append("    \"writesPerformed\": false\n")
                    .append("  },\n")
                    .append("  \"diffIncluded\": ").append(diff != null).append(",\n")
                    .append("  \"exactReplacementDiff\": ").append(nullable(diff)).append("\n}");
            return new ApiResponse(status, json.toString());
        } catch (RawCodexSkillCandidateService.ValidationException exception) {
            return error(422, requestId, exception.code(), false);
        } catch (IllegalArgumentException exception) {
            return error(400, requestId, "INPUT_INVALID", false);
        } catch (IOException exception) {
            return error(500, requestId, "CORE_IO_FAILED", true);
        }
    }

    ApiResponse apply(Map<String, Object> request, String requestId) {
        try {
            allowKeys(request, Set.of("hostId", "workspacePath", "guidedRequest",
                    "approvalToken", "operation", "candidateMode", "logicalPath",
                    "rawContent"));
            requireHost(request);
            Path workspace = path(request, "workspacePath");
            String approvalToken = text(request, "approvalToken", 128);
            Mode mode = mode(request);
            CandidateMode candidateMode = candidateMode(request);
            if (candidateMode == CandidateMode.RAW_SKILL_MD && mode != Mode.UPDATE_EXISTING) {
                throw new IllegalArgumentException("raw create");
            }
            ControlledExistingSkillService service = new ControlledExistingSkillService();
            ControlledSkillApplyReceipt receipt = candidateMode == CandidateMode.RAW_SKILL_MD
                    ? service.apply(workspace, stateRoot, rawCandidate(request), mode, approvalToken)
                    : service.apply(workspace, stateRoot, draft(guidedRequest(request)), mode,
                            approvalToken);
            int status = switch (receipt.status()) {
                case VERIFIED_APPLIED -> 200;
                case APPROVAL_MISMATCH, STALE_PREIMAGE -> 409;
                case BLOCKED -> 422;
                case WRITE_FAILED -> 500;
                case RECOVERY_REQUIRED -> 503;
            };
            String json = begin(requestId, "skill-change-apply")
                    .append("  \"candidateMode\": ").append(json(candidateMode.name())).append(",\n")
                    .append("  \"operation\": ").append(json(operation(mode))).append(",\n")
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
        } catch (RawCodexSkillCandidateService.ValidationException exception) {
            return error(422, requestId, exception.code(), false);
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

    private static String guidedRequest(Map<String, Object> request) {
        if (request.containsKey("rawContent") || request.containsKey("logicalPath")) {
            throw new IllegalArgumentException("guided fields");
        }
        return text(request, "guidedRequest", 32 * 1024);
    }

    private static ControlledSkillCandidate rawCandidate(Map<String, Object> request) {
        if (request.containsKey("guidedRequest")) throw new IllegalArgumentException("raw fields");
        return new RawCodexSkillCandidateService().validate(
                text(request, "logicalPath", 256), rawText(request, "rawContent"));
    }

    private static String rawText(Map<String, Object> request, String field) {
        Object value = request.get(field);
        if (!(value instanceof String text) || text.length() > 256 * 1024) {
            throw new IllegalArgumentException(field);
        }
        return text;
    }

    private static CandidateMode candidateMode(Map<String, Object> request) {
        Object value = request.get("candidateMode");
        if (value == null || "GUIDED_TEMPLATE".equals(value)) return CandidateMode.GUIDED_TEMPLATE;
        if ("RAW_SKILL_MD".equals(value)) return CandidateMode.RAW_SKILL_MD;
        throw new IllegalArgumentException("candidateMode");
    }

    private static void requireHost(Map<String, Object> request) {
        if (!"codex".equals(text(request, "hostId", 32))) {
            throw new IllegalArgumentException("hostId");
        }
    }

    private static Mode mode(Map<String, Object> request) {
        Object value = request.get("operation");
        if (value == null || "UPDATE".equals(value)) return Mode.UPDATE_EXISTING;
        if ("CREATE".equals(value)) return Mode.CREATE_NEW;
        throw new IllegalArgumentException("operation");
    }

    private static String operation(Mode mode) {
        return mode == Mode.CREATE_NEW ? "CREATE" : "UPDATE";
    }

    private enum CandidateMode { GUIDED_TEMPLATE, RAW_SKILL_MD }

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

    private static Optional<String> optionalText(
            Map<String, Object> request, String field, int maxLength) {
        Object value = request.get(field);
        if (value == null) return Optional.empty();
        if (!(value instanceof String text) || text.isBlank() || text.length() > maxLength) {
            throw new IllegalArgumentException(field);
        }
        return Optional.of(text);
    }

    private static void appendForm(StringBuilder result, CodexSkillFormProjection.Form form) {
        result.append("{\n")
                .append("    \"name\": ").append(json(form.name())).append(",\n")
                .append("    \"description\": ").append(json(form.description())).append(",\n")
                .append("    \"goal\": ").append(json(form.goal())).append(",\n");
        appendStrings(result, "inputs", form.inputs(), true);
        appendStrings(result, "outputs", form.outputs(), true);
        appendStrings(result, "triggers", form.triggers(), true);
        appendStrings(result, "exclusions", form.exclusions(), true);
        appendStrings(result, "boundaries", form.boundaries(), true);
        appendStrings(result, "steps", form.steps(), true);
        result.append("    \"completion\": ").append(json(form.completion())).append(",\n");
        appendStrings(result, "validations", form.validations(), false);
        result.append("  }");
    }

    private static void appendStrings(
            StringBuilder result, String field, java.util.List<String> values, boolean comma) {
        result.append("    ").append(json(field)).append(": [");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) result.append(", ");
            result.append(json(values.get(index)));
        }
        result.append(']').append(comma ? ",\n" : "\n");
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

    static ApiResponse error(int status, String requestId, String code,
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
