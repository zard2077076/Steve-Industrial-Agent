package dev.stevecreate.agent.core.warehouse;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

public record WarehouseAllocation(
        ResourceId allocationId,
        ResourceId requestId,
        ResourceId projectId,
        ResourceId endpointId,
        WarehouseResourceKey resource,
        long quantity,
        WarehouseReservationStatus status,
        long generation,
        long updatedAtEpochMillis) {
    public WarehouseAllocation {
        Objects.requireNonNull(allocationId, "allocationId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(endpointId, "endpointId");
        Objects.requireNonNull(resource, "resource");
        if (quantity < 1 || quantity > 1_000_000_000_000L || generation < 0
                || updatedAtEpochMillis < 1) {
            throw new IllegalArgumentException("warehouse allocation values are invalid");
        }
        Objects.requireNonNull(status, "status");
    }

    public WarehouseAllocation advance(
            WarehouseReservationStatus next,
            long nextGeneration,
            long now) {
        boolean legal = switch (status) {
            case RESERVED -> next == WarehouseReservationStatus.WITHDRAWN
                    || next == WarehouseReservationStatus.RELEASED
                    || next == WarehouseReservationStatus.EXPIRED;
            case WITHDRAWN -> next == WarehouseReservationStatus.DELIVERED
                    || next == WarehouseReservationStatus.RETURN_PENDING;
            case DELIVERED -> next == WarehouseReservationStatus.CONSUMED
                    || next == WarehouseReservationStatus.RETURN_PENDING;
            case RETURN_PENDING -> next == WarehouseReservationStatus.RETURNED;
            case CONSUMED, RETURNED, RELEASED, EXPIRED -> false;
        };
        if (!legal || nextGeneration <= generation || now < updatedAtEpochMillis) {
            throw new IllegalStateException("illegal warehouse allocation transition");
        }
        return new WarehouseAllocation(allocationId, requestId, projectId, endpointId, resource,
                quantity, next, nextGeneration, now);
    }
}
