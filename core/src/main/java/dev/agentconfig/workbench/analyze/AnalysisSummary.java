package dev.agentconfig.workbench.analyze;

public record AnalysisSummary(
        int sourceCount,
        int activeSourceCount,
        int directiveCount,
        int deterministicFindingCount,
        int heuristicFindingCount) {
    public AnalysisSummary {
        if (sourceCount < 0 || activeSourceCount < 0 || directiveCount < 0
                || deterministicFindingCount < 0 || heuristicFindingCount < 0
                || activeSourceCount > sourceCount) {
            throw new IllegalArgumentException("Analysis summary counts are invalid");
        }
    }
}
