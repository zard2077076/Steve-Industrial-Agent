package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** Provenance that prevents construction tasks from becoming free-coordinate templates. */
public record VerifiedPlanTaskSource(
        ResourceId verifiedPhysicalPlanId,
        TaskSourceKind sourceKind,
        ResourceId physicalElementId,
        int elementOrdinal) {
    public static final int MAX_ELEMENT_ORDINAL = 16_383;

    public VerifiedPlanTaskSource {
        Objects.requireNonNull(verifiedPhysicalPlanId, "verifiedPhysicalPlanId");
        Objects.requireNonNull(sourceKind, "sourceKind");
        Objects.requireNonNull(physicalElementId, "physicalElementId");
        if (elementOrdinal < 0 || elementOrdinal > MAX_ELEMENT_ORDINAL) {
            throw new IllegalArgumentException("elementOrdinal must be between 0 and "
                    + MAX_ELEMENT_ORDINAL);
        }
    }
}
