package dev.stevecreate.agent.core.verification;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;
import java.util.Optional;

/** Immutable complete observation; a pass flag never replaces observed and expected values. */
public record VerificationEvidence(
        ResourceId evidenceId,
        VerificationEvidenceKind kind,
        ResourceId requirementId,
        ResourceId sourceStepId,
        ResourceId sourceId,
        ResourceId targetId,
        EvidenceValue observedValue,
        EvidenceValue expectedValue,
        long observedTick,
        boolean passed,
        Optional<EvidenceDiagnostic> diagnostic) {
    public VerificationEvidence {
        Objects.requireNonNull(evidenceId, "evidenceId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(requirementId, "requirementId");
        Objects.requireNonNull(sourceStepId, "sourceStepId");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(observedValue, "observedValue");
        Objects.requireNonNull(expectedValue, "expectedValue");
        if (observedTick < 0) {
            throw new IllegalArgumentException("observedTick must not be negative");
        }
        diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
    }
}
