package dev.stevecreate.agent.core.warehouse;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Durable bounded authority for one unattended maintain-stock order. */
public record ProductionOrder(
        ResourceId orderId,
        ResourceId ownerId,
        ResourceId warehouseId,
        WarehouseResourceKey target,
        long targetStock,
        long verifiedBatchOutput,
        int maximumBatchesPerDispatch,
        ResourceId approvedProductionGraphId,
        Set<ResourceId> approvedRecipeIds,
        Set<ResourceId> approvedAdapterIds,
        List<ResourceId> approvedSiteIds,
        int maximumRetries,
        int consecutiveFailures,
        long nextEligibleTick,
        Optional<ResourceId> inFlightBatchId,
        ProductionOrderStatus status,
        String lastStatusCode,
        long generation) {
    public ProductionOrder {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(warehouseId, "warehouseId");
        Objects.requireNonNull(target, "target");
        if (targetStock < 1 || targetStock > 1_000_000_000_000L
                || verifiedBatchOutput < 1 || verifiedBatchOutput > targetStock
                || maximumBatchesPerDispatch < 1 || maximumBatchesPerDispatch > 64) {
            throw new IllegalArgumentException("production-order quantity bounds are invalid");
        }
        Objects.requireNonNull(approvedProductionGraphId, "approvedProductionGraphId");
        approvedRecipeIds = ids(approvedRecipeIds, "approvedRecipeIds");
        approvedAdapterIds = ids(approvedAdapterIds, "approvedAdapterIds");
        Objects.requireNonNull(approvedSiteIds, "approvedSiteIds");
        if (approvedSiteIds.isEmpty() || approvedSiteIds.size() > 64) {
            throw new IllegalArgumentException("production-order sites are empty or unbounded");
        }
        approvedSiteIds = approvedSiteIds.stream().distinct()
                .sorted(Comparator.comparing(ResourceId::toString)).toList();
        if (maximumRetries < 0 || maximumRetries > 16 || consecutiveFailures < 0
                || consecutiveFailures > maximumRetries || nextEligibleTick < 0 || generation < 0) {
            throw new IllegalArgumentException("production-order recovery values are invalid");
        }
        inFlightBatchId = Objects.requireNonNull(inFlightBatchId, "inFlightBatchId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(lastStatusCode, "lastStatusCode");
        if (lastStatusCode.isBlank() || lastStatusCode.length() > 1_024) {
            throw new IllegalArgumentException("production-order status is blank or unbounded");
        }
        if ((status == ProductionOrderStatus.BATCH_IN_FLIGHT) != inFlightBatchId.isPresent()) {
            throw new IllegalArgumentException("production-order in-flight state is inconsistent");
        }
    }

    private static Set<ResourceId> ids(Set<ResourceId> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.isEmpty() || values.size() > 256) {
            throw new IllegalArgumentException(name + " is empty or unbounded");
        }
        return Set.copyOf(values);
    }
}
