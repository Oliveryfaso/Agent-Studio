package dev.agentconfig.workbench.analyze;

public record DirectiveAnalyzerLimits(
        int maximumCharactersPerSource,
        int maximumLinesPerSource,
        int maximumItemCharacters,
        int maximumUnits,
        int maximumSources) {
    public static final DirectiveAnalyzerLimits DEFAULT = new DirectiveAnalyzerLimits(
            1_048_576,
            20_000,
            8_192,
            4_096,
            256);

    public DirectiveAnalyzerLimits(
            int maximumCharactersPerSource,
            int maximumLinesPerSource,
            int maximumItemCharacters,
            int maximumUnits) {
        this(maximumCharactersPerSource, maximumLinesPerSource,
                maximumItemCharacters, maximumUnits, 256);
    }

    public DirectiveAnalyzerLimits {
        if (maximumCharactersPerSource < 1
                || maximumLinesPerSource < 1
                || maximumItemCharacters < 1
                || maximumUnits < 1
                || maximumSources < 1) {
            throw new IllegalArgumentException("All directive analyzer limits must be positive");
        }
    }
}
