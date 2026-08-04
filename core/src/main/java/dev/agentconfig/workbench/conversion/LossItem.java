package dev.agentconfig.workbench.conversion;

import dev.agentconfig.workbench.ir.IrNodeRef;
import java.util.List;
import java.util.Objects;

public record LossItem(
        String id,
        String code,
        LossSeverity severity,
        String summary,
        List<IrNodeRef> provenance) {
    public LossItem {
        id = ConversionValidation.id(id, "loss id");
        code = ConversionValidation.id(code, "loss code");
        Objects.requireNonNull(severity, "severity");
        summary = ConversionValidation.nonBlank(summary, "loss summary");
        provenance = ConversionValidation.immutableList(provenance, "loss provenance");
        if (provenance.isEmpty()) {
            throw new IllegalArgumentException("loss provenance must not be empty");
        }
    }
}
