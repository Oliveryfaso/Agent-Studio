package dev.agentconfig.workbench.context.codex;

import java.util.List;
import java.util.Objects;

/** Parsed Codex project-instruction discovery options. */
public record CodexProjectOptions(List<String> fallbackFilenames, long maxBytes) {
    public static final long DEFAULT_MAX_BYTES = 32L * 1024L;

    public CodexProjectOptions {
        fallbackFilenames = List.copyOf(Objects.requireNonNull(
                fallbackFilenames, "fallbackFilenames"));
        if (fallbackFilenames.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Fallback filenames must not contain null");
        }
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
    }

    public static CodexProjectOptions defaults() {
        return new CodexProjectOptions(List.of(), DEFAULT_MAX_BYTES);
    }
}
