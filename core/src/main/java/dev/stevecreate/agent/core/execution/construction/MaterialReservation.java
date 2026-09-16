package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** Versioned material ledger row with arithmetic that forbids double withdrawal/delivery. */
public record MaterialReservation(
        ResourceId reservationId,
        ResourceId sessionId,
        ResourceId taskId,
        ResourceId sourceId,
        MaterialSourceScope sourceScope,
        ResourceId resourceId,
        long requestedQuantity,
        long reservedQuantity,
        long withdrawnQuantity,
        long deliveredQuantity,
        long returnedQuantity,
        ReservationStatus status,
        long generation,
        long updatedTick) {
    public MaterialReservation {
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(sourceScope, "sourceScope");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(status, "status");
        if (requestedQuantity < 1
                || reservedQuantity < 0
                || reservedQuantity > requestedQuantity
                || withdrawnQuantity < 0
                || withdrawnQuantity > reservedQuantity
                || deliveredQuantity < 0
                || returnedQuantity < 0
                || Math.addExact(deliveredQuantity, returnedQuantity) > withdrawnQuantity) {
            throw new IllegalArgumentException("Material reservation quantities are inconsistent");
        }
        if (status == ReservationStatus.ACTIVE && reservedQuantity == 0) {
            throw new IllegalArgumentException("An active material reservation must reserve a positive quantity");
        }
        if (generation < 0 || updatedTick < 0) {
            throw new IllegalArgumentException("Material reservation generation/tick must be non-negative");
        }
    }

    public long outstandingQuantity() {
        return Math.subtractExact(withdrawnQuantity,
                Math.addExact(deliveredQuantity, returnedQuantity));
    }
}
