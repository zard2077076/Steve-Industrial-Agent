package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** Immutable explanatory evidence for one deterministic candidate derivation. */
public record PlanningEvidence(
        ResourceId evidenceId,
        PlanningEvidenceKind kind,
        ResourceId subjectId,
        String detail) {
    public static final int MAX_DETAIL_LENGTH = 512;

    public PlanningEvidence {
        Objects.requireNonNull(evidenceId, "evidenceId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(detail, "detail");
        if (detail.isBlank() || detail.length() > MAX_DETAIL_LENGTH) {
            throw new IllegalArgumentException("Planning evidence detail must be nonblank and bounded");
        }
    }
}
