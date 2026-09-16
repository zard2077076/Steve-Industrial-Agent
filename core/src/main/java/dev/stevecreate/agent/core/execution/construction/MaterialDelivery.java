package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** One exact withdrawal carried by one Bot, with delivery/return non-duplication arithmetic. */
public record MaterialDelivery(
        ResourceId deliveryId,
        ResourceId reservationId,
        ResourceId sessionId,
        ResourceId taskId,
        ResourceId workerId,
        ResourceId resourceId,
        long withdrawnQuantity,
        long deliveredQuantity,
        long returnedQuantity,
        MaterialDeliveryStatus status,
        long generation,
        long updatedTick) {
    public MaterialDelivery {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(status, "status");
        if (withdrawnQuantity < 1
                || deliveredQuantity < 0
                || returnedQuantity < 0
                || Math.addExact(deliveredQuantity, returnedQuantity) > withdrawnQuantity) {
            throw new IllegalArgumentException("Material delivery quantities are inconsistent");
        }
        long outstanding = Math.subtractExact(
                withdrawnQuantity, Math.addExact(deliveredQuantity, returnedQuantity));
        if ((status == MaterialDeliveryStatus.CARRYING) != (outstanding > 0)) {
            throw new IllegalArgumentException("Material delivery status disagrees with outstanding quantity");
        }
        if (generation < 0 || updatedTick < 0) {
            throw new IllegalArgumentException("Material delivery generation/tick must be non-negative");
        }
    }

    public long outstandingQuantity() {
        return Math.subtractExact(
                withdrawnQuantity, Math.addExact(deliveredQuantity, returnedQuantity));
    }
}
