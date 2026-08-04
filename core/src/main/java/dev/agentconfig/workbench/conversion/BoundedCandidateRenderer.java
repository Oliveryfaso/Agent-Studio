package dev.agentconfig.workbench.conversion;

import java.nio.charset.StandardCharsets;

/** Pure in-memory renderer for the narrow, versioned portable subset. */
public final class BoundedCandidateRenderer {
    public static final long DEFAULT_MAX_CANDIDATE_BYTES = 64L * 1024L;
    public static final String CODEX_ROOT_TO_CLAUDE_WRAPPER_PROFILE =
            "codex-root-to-claude-wrapper-renderer-v1";

    private final long maxCandidateBytes;

    public BoundedCandidateRenderer() {
        this(DEFAULT_MAX_CANDIDATE_BYTES);
    }

    public BoundedCandidateRenderer(long maxCandidateBytes) {
        if (maxCandidateBytes < 1 || maxCandidateBytes > DEFAULT_MAX_CANDIDATE_BYTES) {
            throw new IllegalArgumentException(
                    "candidate byte limit must be between 1 and 65536 bytes");
        }
        this.maxCandidateBytes = maxCandidateBytes;
    }

    public RenderedCandidate renderClaudeProjectImportWrapper(String sourceLogicalPath) {
        String source = ConversionValidation.logicalPath(sourceLogicalPath);
        byte[] bytes = ("@" + source + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxCandidateBytes) {
            throw new IllegalArgumentException("rendered candidate exceeds its byte limit");
        }
        return new RenderedCandidate(
                "CLAUDE.md", bytes, CODEX_ROOT_TO_CLAUDE_WRAPPER_PROFILE);
    }
}
