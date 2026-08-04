package dev.agentconfig.workbench.conversion;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record TargetInventory(List<TargetInventoryEntry> entries) {
    public TargetInventory {
        entries = ConversionValidation.immutableList(entries, "target inventory entries");
        Map<String, TargetInventoryEntry> unique = new HashMap<>();
        for (TargetInventoryEntry entry : entries) {
            if (unique.put(entry.logicalPath(), entry) != null) {
                throw new IllegalArgumentException(
                        "duplicate target inventory path: " + entry.logicalPath());
            }
        }
    }

    public TargetInventoryEntry entry(String logicalPath) {
        String normalized = ConversionValidation.logicalPath(logicalPath);
        return entries.stream().filter(value -> value.logicalPath().equals(normalized))
                .findFirst().orElseGet(() -> TargetInventoryEntry.unknown(normalized));
    }

    public static TargetInventory unknown() {
        return new TargetInventory(List.of());
    }
}
