package dev.stevecreate.agent.core.warehouse;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** Exact verified stage-output deposit and next-stage reservation bridge. */
public record WarehouseTransferEvidence(
        ResourceId transferId,
        ResourceId projectId,
        ResourceId fromStageId,
        ResourceId toStageId,
        ResourceId destinationEndpointId,
        WarehouseResourceKey resource,
        long quantity,
        String sourceOutputSha256,
        String destinationSnapshotSha256,
        ResourceId reservationAllocationId,
        long observedTick) {
    public WarehouseTransferEvidence {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(fromStageId, "fromStageId");
        Objects.requireNonNull(toStageId, "toStageId");
        Objects.requireNonNull(destinationEndpointId, "destinationEndpointId");
        Objects.requireNonNull(resource, "resource");
        if (quantity < 1 || quantity > 1_000_000_000_000L || observedTick < 0) {
            throw new IllegalArgumentException("warehouse transfer quantity or tick is invalid");
        }
        if (!hash(sourceOutputSha256) || !hash(destinationSnapshotSha256)) {
            throw new IllegalArgumentException("warehouse transfer evidence hash is invalid");
        }
        Objects.requireNonNull(reservationAllocationId, "reservationAllocationId");
    }

    private static boolean hash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
