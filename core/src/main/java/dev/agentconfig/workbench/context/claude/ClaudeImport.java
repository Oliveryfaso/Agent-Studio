package dev.agentconfig.workbench.context.claude;

import java.nio.file.Path;
import java.util.Objects;

/** A project-relative import reference; this record does not read or resolve the target. */
public record ClaudeImport(String rawPath, Path relativePath, int line, int column) {
    public ClaudeImport {
        rawPath = Objects.requireNonNull(rawPath, "rawPath");
        relativePath = Objects.requireNonNull(relativePath, "relativePath");
        if (line < 1 || column < 1) {
            throw new IllegalArgumentException("Import positions are one-based");
        }
    }
}
