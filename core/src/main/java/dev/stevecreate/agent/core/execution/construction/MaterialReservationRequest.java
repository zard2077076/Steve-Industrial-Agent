package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** Deterministic reservation request tied to one exact graph task and authorized source. */
public record MaterialReservationRequest(
        ResourceId reservationId,
        ResourceId sessionId,
        ResourceId taskId,
        ResourceId sourceId,
        ResourceId resourceId,
        long quantity,
        long requestedTick) {
    public MaterialReservationRequest {
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(resourceId, "resourceId");
        if (quantity < 1 || requestedTick < 0) {
            throw new IllegalArgumentException("Reservation quantity must be positive and tick non-negative");
        }
    }
}
