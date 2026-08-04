package dev.agentconfig.workbench.scan;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ScanResult(
        Path logicalRoot,
        Path realRoot,
        Instant scannedAt,
        List<DiscoveredArtifact> artifacts,
        List<ScanFinding> findings,
        ScanCompletionStatus completionStatus,
        ScanStopReason stopReason) {

    public ScanResult {
        Objects.requireNonNull(logicalRoot, "logicalRoot");
        Objects.requireNonNull(realRoot, "realRoot");
        Objects.requireNonNull(scannedAt, "scannedAt");
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
        Objects.requireNonNull(completionStatus, "completionStatus");
        Objects.requireNonNull(stopReason, "stopReason");
        boolean complete = completionStatus == ScanCompletionStatus.COMPLETE;
        if (complete != (stopReason == ScanStopReason.NONE)) {
            throw new IllegalArgumentException("Completion status and stop reason disagree");
        }
    }

    /**
     * Source-compatible constructor for complete results created before completion state existed.
     */
    public ScanResult(
            Path logicalRoot,
            Path realRoot,
            Instant scannedAt,
            List<DiscoveredArtifact> artifacts,
            List<ScanFinding> findings) {
        this(logicalRoot, realRoot, scannedAt, artifacts, findings,
                ScanCompletionStatus.COMPLETE, ScanStopReason.NONE);
    }

    public boolean hasBlockingFindings() {
        return findings.stream().anyMatch(finding -> finding.severity() == Severity.BLOCKING);
    }

    public boolean complete() {
        return completionStatus == ScanCompletionStatus.COMPLETE;
    }
}
