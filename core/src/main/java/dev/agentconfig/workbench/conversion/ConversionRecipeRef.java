package dev.agentconfig.workbench.conversion;

public record ConversionRecipeRef(String id, int version) {
    public ConversionRecipeRef {
        id = ConversionValidation.id(id, "recipe id");
        if (version < 1) {
            throw new IllegalArgumentException("recipe version must be positive");
        }
    }
}
