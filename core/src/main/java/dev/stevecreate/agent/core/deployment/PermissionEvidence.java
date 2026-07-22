package dev.stevecreate.agent.core.deployment;

import dev.stevecreate.agent.core.model.ResourceId;
import java.time.Instant;
import java.util.Objects;

/** Adapter-produced evidence; only exact verified isolated/development ALLOWED may pass. */
public record PermissionEvidence(
        PermissionQuery query,
        PermissionDecision decision,
        ResourceId adapterId,
        PermissionEvidenceState state,
        String source,
        Instant observedAt,
        long observedGeneration,
        String fingerprint,
        String provenance) {
    public PermissionEvidence {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(adapterId, "adapterId");
        Objects.requireNonNull(state, "state");
        source = PermissionQuery.text(source, "source");
        Objects.requireNonNull(observedAt, "observedAt");
        if (observedGeneration < 0) {
            throw new IllegalArgumentException("observedGeneration cannot be negative");
        }
        fingerprint = PermissionQuery.text(fingerprint, "fingerprint");
        provenance = PermissionQuery.text(provenance, "provenance");
        if (decision == PermissionDecision.ALLOWED && state != PermissionEvidenceState.VERIFIED) {
            throw new IllegalArgumentException("ALLOWED permission must be verified evidence");
        }
    }

    public boolean permitsConstruction() {
        return decision == PermissionDecision.ALLOWED
                && state == PermissionEvidenceState.VERIFIED
                && observedGeneration == query.generation()
                && fingerprint.equals(query.fingerprint())
                && !observedAt.isBefore(query.requestedAt())
                && (query.environmentClassification() == WorldEnvironmentType.ISOLATED_TEST_WORLD
                        || query.environmentClassification() == WorldEnvironmentType.DEVELOPMENT_WORLD);
    }
}
