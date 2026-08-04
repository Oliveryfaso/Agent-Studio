package dev.agentconfig.workbench.ir;

import java.util.Objects;

/** A content-free directive fingerprint. Raw instruction text is deliberately not represented. */
public record DirectiveUnit(
        String id,
        SourceIdentity source,
        String normalizedHash,
        DirectivePolarity polarity,
        int line) {
    public DirectiveUnit {
        id = IrValidation.id(id, "directive id");
        Objects.requireNonNull(source, "source");
        normalizedHash = IrValidation.sha256(normalizedHash, "normalizedHash");
        Objects.requireNonNull(polarity, "polarity");
        if (line < 1) {
            throw new IllegalArgumentException("directive line must be positive");
        }
    }
}
