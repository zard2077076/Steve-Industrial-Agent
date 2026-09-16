package dev.stevecreate.agent.core.warehouse;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record WarehouseReservationRequest(
        ResourceId requestId,
        ResourceId projectId,
        ResourceId ownerId,
        WarehouseResourceKey resource,
        long quantity,
        List<ResourceId> allowedEndpointIds,
        long expiresAtEpochMillis,
        int priority) {
    public WarehouseReservationRequest {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(resource, "resource");
        if (quantity < 1 || quantity > 1_000_000_000_000L) {
            throw new IllegalArgumentException("warehouse reservation quantity is invalid");
        }
        Objects.requireNonNull(allowedEndpointIds, "allowedEndpointIds");
        if (allowedEndpointIds.isEmpty() || allowedEndpointIds.size() > GlobalInventoryGraph.MAX_ENDPOINTS) {
            throw new IllegalArgumentException("allowed warehouse endpoints are empty or unbounded");
        }
        allowedEndpointIds = allowedEndpointIds.stream().distinct()
                .sorted(Comparator.comparing(ResourceId::toString)).toList();
        if (expiresAtEpochMillis < 1 || priority < 0 || priority > 1_000_000) {
            throw new IllegalArgumentException("warehouse reservation expiry or priority is invalid");
        }
    }
}
