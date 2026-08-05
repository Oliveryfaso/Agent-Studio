package dev.agentconfig.workbench.skilldraft;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashSet;

/** Versioned, in-memory S2 result. Candidate bytes are never written by this model. */
public record CodexSkillDraftPreview(
        int schemaVersion,
        String id,
        Status status,
        String sourcePreviewId,
        Optional<Candidate> candidate,
        Validation validation,
        List<String> unresolved,
        boolean workspaceContentIncluded,
        boolean userProvidedContentIncluded,
        boolean candidateContentIncluded,
        boolean routingEvalPerformed,
        boolean llmUsed,
        boolean networkUsed,
        boolean processesStarted,
        boolean writesPerformed,
        boolean applyEligible) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public CodexSkillDraftPreview {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported Skill draft preview schema version");
        }
        id = required(id, "id");
        Objects.requireNonNull(status, "status");
        sourcePreviewId = required(sourcePreviewId, "sourcePreviewId");
        candidate = Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(validation, "validation");
        unresolved = List.copyOf(Objects.requireNonNull(unresolved, "unresolved"));
        if (workspaceContentIncluded || candidateContentIncluded || routingEvalPerformed || llmUsed
                || networkUsed || processesStarted || writesPerformed || applyEligible) {
            throw new IllegalArgumentException("draft preview safety flags are invalid");
        }
        if (!userProvidedContentIncluded) {
            throw new IllegalArgumentException("draft preview contains user-provided content");
        }
        if (status == Status.SOURCE_NOT_READY && candidate.isPresent()) {
            throw new IllegalArgumentException("source-not-ready preview cannot contain a candidate");
        }
        if (status != Status.SOURCE_NOT_READY && candidate.isEmpty()) {
            throw new IllegalArgumentException("draft result requires a candidate");
        }
        ValidationStatus expected = switch (status) {
            case SOURCE_NOT_READY -> ValidationStatus.NOT_RUN;
            case READY -> ValidationStatus.PASSED;
            case REVIEW_REQUIRED -> ValidationStatus.REVIEW_REQUIRED;
            case INVALID -> ValidationStatus.FAILED;
        };
        if (validation.status() != expected) {
            throw new IllegalArgumentException("draft and validation status disagree");
        }
        if (candidate.isPresent() && !validation.candidateSha256().equals(
                Optional.of(candidate.orElseThrow().sha256()))) {
            throw new IllegalArgumentException("validation is not bound to candidate bytes");
        }
    }

    public enum Status { SOURCE_NOT_READY, READY, REVIEW_REQUIRED, INVALID }

    public enum ValidationStatus { NOT_RUN, PASSED, REVIEW_REQUIRED, FAILED }

    public record Validation(
            int schemaVersion,
            String profileId,
            ValidationStatus status,
            Optional<String> candidateSha256,
            List<Check> checks) {
        public Validation {
            if (schemaVersion != 1) {
                throw new IllegalArgumentException("unsupported draft validation schema version");
            }
            profileId = required(profileId, "profileId");
            if (!"codex-project-skill-static-v1".equals(profileId)) {
                throw new IllegalArgumentException("unknown draft validation profile");
            }
            Objects.requireNonNull(status, "status");
            candidateSha256 = Objects.requireNonNull(candidateSha256, "candidateSha256");
            checks = List.copyOf(Objects.requireNonNull(checks, "checks"));
            Set<String> codes = new LinkedHashSet<>();
            if (checks.stream().anyMatch(check -> !codes.add(check.code()))) {
                throw new IllegalArgumentException("validation check codes must be unique");
            }
            if (status == ValidationStatus.NOT_RUN) {
                if (candidateSha256.isPresent() || !checks.isEmpty()) {
                    throw new IllegalArgumentException("NOT_RUN cannot contain validation evidence");
                }
            } else {
                if (candidateSha256.isEmpty() || checks.isEmpty()) {
                    throw new IllegalArgumentException("completed validation requires evidence");
                }
                Set<String> required = Set.of("STRICT_UTF8", "UTF8_LF_TERMINATED",
                        "CANDIDATE_BYTE_BUDGET", "DESCRIPTION_BYTE_BUDGET",
                        "DESCRIPTION_SAFE_SCALAR", "LOGICAL_PATH", "RENDERER_PROFILE",
                        "CANONICAL_CONTENT");
                if (!codes.containsAll(required)) {
                    throw new IllegalArgumentException("validation evidence is incomplete");
                }
                boolean anyFailed = checks.stream().anyMatch(check -> !check.passed());
                if ((status == ValidationStatus.FAILED) != anyFailed) {
                    throw new IllegalArgumentException("validation status disagrees with checks");
                }
            }
        }
    }

    public record Check(String code, boolean passed, String detail) {
        public Check {
            code = required(code, "check code");
            detail = required(detail, "check detail");
        }
    }

    public static final class Candidate {
        private final String id;
        private final String blueprintId;
        private final String logicalPath;
        private final byte[] bytes;
        private final String sha256;
        private final String rendererProfileId;
        private final int lineCount;

        public Candidate(String id, String blueprintId, String logicalPath, byte[] bytes,
                String sha256, String rendererProfileId, int lineCount) {
            this.id = required(id, "candidate id");
            this.blueprintId = required(blueprintId, "blueprintId");
            this.logicalPath = required(logicalPath, "logicalPath");
            if (!this.logicalPath.matches("\\.agents/skills/[a-z0-9]+(?:-[a-z0-9]+)*/SKILL\\.md")) {
                throw new IllegalArgumentException("candidate logical path is not canonical");
            }
            Objects.requireNonNull(bytes, "bytes");
            if (bytes.length > 128 * 1024) {
                throw new IllegalArgumentException("candidate exceeds the 131072-byte limit");
            }
            this.bytes = bytes.clone();
            this.sha256 = required(sha256, "sha256");
            if (!this.sha256.equals(hash(this.bytes))) {
                throw new IllegalArgumentException("candidate SHA-256 does not match bytes");
            }
            this.rendererProfileId = required(rendererProfileId, "rendererProfileId");
            if (!"codex-project-skill-template-v1".equals(this.rendererProfileId)) {
                throw new IllegalArgumentException("unknown candidate renderer profile");
            }
            if (lineCount < 1) {
                throw new IllegalArgumentException("lineCount must be positive");
            }
            long actualLines = new String(this.bytes, StandardCharsets.UTF_8).chars()
                    .filter(character -> character == '\n').count();
            if (actualLines != lineCount) {
                throw new IllegalArgumentException("candidate lineCount does not match bytes");
            }
            this.lineCount = lineCount;
        }

        public String id() { return id; }
        public String blueprintId() { return blueprintId; }
        public String logicalPath() { return logicalPath; }
        public byte[] bytes() { return bytes.clone(); }
        public String content() { return new String(bytes, StandardCharsets.UTF_8); }
        public int byteSize() { return bytes.length; }
        public String sha256() { return sha256; }
        public String rendererProfileId() { return rendererProfileId; }
        public int lineCount() { return lineCount; }

        private static String hash(byte[] value) {
            try {
                return HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(value));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 unavailable", exception);
            }
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
