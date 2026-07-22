package dev.stevecreate.agent.core.execution;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** Serializable-shaped world-change reference, not a rollback journal or mutation permission. */
public record SessionWorldChangeReference(
        ResourceId changeId,
        ResourceId sourceStepId,
        long recordedTick,
        boolean potentiallyReversible) {
    public SessionWorldChangeReference {
        Objects.requireNonNull(changeId, "changeId");
        Objects.requireNonNull(sourceStepId, "sourceStepId");
        if (recordedTick < 0) {
            throw new IllegalArgumentException("recordedTick must not be negative");
        }
    }
}
