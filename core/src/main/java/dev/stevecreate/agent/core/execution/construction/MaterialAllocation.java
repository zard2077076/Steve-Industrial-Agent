package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** Exact reservation allocation against one source slot and stack identity. */
public record MaterialAllocation(
        ResourceId allocationId,
        ResourceId projectId,
        ResourceId requirementId,
        ResourceId sourceId,
        int sourceSlot,
        MaterialIdentity identity,
        long quantity) {
    public MaterialAllocation {
        Objects.requireNonNull(allocationId, "allocationId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(requirementId, "requirementId");
        Objects.requireNonNull(sourceId, "sourceId");
        if (sourceSlot < 0 || sourceSlot >= 4_096) throw new IllegalArgumentException("source slot invalid");
        Objects.requireNonNull(identity, "identity");
        if (quantity < 1 || quantity > 1_000_000_000L) throw new IllegalArgumentException("allocation quantity invalid");
    }
}
