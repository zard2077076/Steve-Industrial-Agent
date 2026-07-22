package dev.stevecreate.agent.core.formalbackup;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ApprovalEvidence(
        String candidatePackageIdentity,
        boolean userSelectionPresent,
        List<String> missingEvidence,
        Instant observedAt,
        String provenance) {
    public ApprovalEvidence {
        Objects.requireNonNull(candidatePackageIdentity, "candidatePackageIdentity");
        missingEvidence = List.copyOf(Objects.requireNonNull(missingEvidence, "missingEvidence"));
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(provenance, "provenance");
        if (!candidatePackageIdentity.matches("formal-candidate:[0-9a-f]{64}")
                || missingEvidence.size() > 128 || provenance.isBlank()) {
            throw new IllegalArgumentException("approval evidence is invalid");
        }
    }
}
