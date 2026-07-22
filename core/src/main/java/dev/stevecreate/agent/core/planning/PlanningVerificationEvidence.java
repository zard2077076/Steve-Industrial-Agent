package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** One immutable read-only record that a planning verification check passed. */
public record PlanningVerificationEvidence(
        PlanningVerificationCheck check,
        ResourceId subjectId,
        String detail) {
    public static final int MAX_DETAIL_LENGTH = 512;

    public PlanningVerificationEvidence {
        Objects.requireNonNull(check, "check");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(detail, "detail");
        if (detail.isBlank() || detail.length() > MAX_DETAIL_LENGTH) {
            throw new IllegalArgumentException("detail must be nonblank and bounded");
        }
    }
}
