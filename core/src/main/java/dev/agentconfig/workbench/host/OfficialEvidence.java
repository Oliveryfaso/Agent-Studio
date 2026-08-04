package dev.agentconfig.workbench.host;

import java.net.URI;
import java.time.LocalDate;
import java.util.Objects;

public record OfficialEvidence(URI url, LocalDate verifiedAt, String note) {
    public OfficialEvidence {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(verifiedAt, "verifiedAt");
        note = Objects.requireNonNull(note, "note").strip();
        if (!url.getScheme().equals("https")) {
            throw new IllegalArgumentException("Official evidence must use HTTPS");
        }
        if (note.isEmpty()) {
            throw new IllegalArgumentException("Evidence note must not be empty");
        }
    }
}
