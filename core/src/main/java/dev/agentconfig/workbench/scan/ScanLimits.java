package dev.agentconfig.workbench.scan;

public record ScanLimits(
        int maxDepth,
        long maxEntries,
        long maxArtifactBytes,
        long maxTotalBytes,
        int contentSampleBytes) {
    public ScanLimits {
        if (maxDepth < 1 || maxEntries < 1 || maxArtifactBytes < 1
                || maxTotalBytes < 1 || contentSampleBytes < 1) {
            throw new IllegalArgumentException("All scan limits must be positive");
        }
        if (contentSampleBytes > maxArtifactBytes) {
            throw new IllegalArgumentException("Content sample cannot exceed artifact byte limit");
        }
    }

    /**
     * Source-compatible constructor for callers created before the aggregate read budget existed.
     */
    public ScanLimits(int maxDepth, long maxEntries, long maxArtifactBytes, int contentSampleBytes) {
        this(maxDepth, maxEntries, maxArtifactBytes, Long.MAX_VALUE, contentSampleBytes);
    }

    public static ScanLimits defaults() {
        return new ScanLimits(64, 100_000, 16L * 1024 * 1024,
                256L * 1024 * 1024, 64 * 1024);
    }
}
