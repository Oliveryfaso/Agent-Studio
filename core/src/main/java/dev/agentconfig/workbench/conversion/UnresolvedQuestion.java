package dev.agentconfig.workbench.conversion;

import dev.agentconfig.workbench.ir.IrNodeRef;
import java.util.List;

public record UnresolvedQuestion(
        String id,
        String code,
        String prompt,
        List<IrNodeRef> provenance) {
    public UnresolvedQuestion {
        id = ConversionValidation.id(id, "question id");
        code = ConversionValidation.id(code, "question code");
        prompt = ConversionValidation.nonBlank(prompt, "question prompt");
        provenance = ConversionValidation.immutableList(provenance, "question provenance");
        if (provenance.isEmpty()) {
            throw new IllegalArgumentException("question provenance must not be empty");
        }
    }
}
