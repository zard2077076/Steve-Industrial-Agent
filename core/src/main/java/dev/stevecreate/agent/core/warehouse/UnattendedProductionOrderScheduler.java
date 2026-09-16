package dev.stevecreate.agent.core.warehouse;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure single-in-flight maintain-stock scheduler. It can select only the pre-approved warehouse,
 * graph, recipes, adapters and sites already embedded in the durable order.
 */
public final class UnattendedProductionOrderScheduler {
    public static final long[] RETRY_BACKOFF_TICKS = {100, 300, 1_200};

    public Decision evaluate(ProductionOrder order, long observedStock, long tick) {
        Objects.requireNonNull(order, "order");
        if (observedStock < 0 || observedStock > 1_000_000_000_000L || tick < 0) {
            throw new IllegalArgumentException("production-order observation is invalid");
        }
        if (order.status() == ProductionOrderStatus.CANCELLED
                || order.status() == ProductionOrderStatus.PAUSED
                || order.status() == ProductionOrderStatus.BATCH_IN_FLIGHT) {
            return Decision.noop(order, "ORDER_NOT_DISPATCHABLE");
        }
        if (observedStock >= order.targetStock()) {
            return Decision.updated(copy(order, ProductionOrderStatus.TARGET_SATISFIED,
                    order.consecutiveFailures(), order.nextEligibleTick(), Optional.empty(),
                    "TARGET_STOCK_SATISFIED"), Optional.empty());
        }
        if (tick < order.nextEligibleTick()) {
            return Decision.updated(copy(order, ProductionOrderStatus.COOLDOWN,
                    order.consecutiveFailures(), order.nextEligibleTick(), Optional.empty(),
                    "RETRY_COOLDOWN"), Optional.empty());
        }
        long deficit = order.targetStock() - observedStock;
        long batches = Math.min(order.maximumBatchesPerDispatch(),
                Math.max(1, Math.floorDiv(deficit + order.verifiedBatchOutput() - 1,
                        order.verifiedBatchOutput())));
        long expectedOutput = Math.multiplyExact(batches, order.verifiedBatchOutput());
        ResourceId batchId = ResourceId.parse("order:batch_" + order.orderId().namespace()
                + "_" + order.orderId().path() + "_" + (order.generation() + 1));
        ProductionOrder updated = copy(order, ProductionOrderStatus.BATCH_IN_FLIGHT,
                order.consecutiveFailures(), tick, Optional.of(batchId), "BATCH_AUTHORIZED");
        return Decision.updated(updated, Optional.of(new BatchAuthorization(
                batchId, order.orderId(), order.warehouseId(), order.approvedProductionGraphId(),
                order.approvedRecipeIds(), order.approvedAdapterIds(), order.approvedSiteIds(),
                batches, expectedOutput, tick)));
    }

    public ProductionOrder batchSucceeded(ProductionOrder order, ResourceId batchId, long tick) {
        requireBatch(order, batchId, tick);
        return copy(order, ProductionOrderStatus.ACTIVE, 0, tick, Optional.empty(),
                "BATCH_VERIFIED_AND_SETTLED");
    }

    public ProductionOrder batchFailed(
            ProductionOrder order,
            ResourceId batchId,
            String failureCode,
            long tick) {
        requireBatch(order, batchId, tick);
        Objects.requireNonNull(failureCode, "failureCode");
        int failures = order.consecutiveFailures() + 1;
        if (failures > order.maximumRetries()) {
            return copy(order, ProductionOrderStatus.PAUSED, order.maximumRetries(), tick,
                    Optional.empty(), "RETRY_BUDGET_EXHAUSTED:" + failureCode);
        }
        long backoff = RETRY_BACKOFF_TICKS[Math.min(failures - 1,
                RETRY_BACKOFF_TICKS.length - 1)];
        return copy(order, ProductionOrderStatus.COOLDOWN, failures,
                Math.addExact(tick, backoff), Optional.empty(), "BATCH_FAILED:" + failureCode);
    }

    private static void requireBatch(ProductionOrder order, ResourceId batchId, long tick) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(batchId, "batchId");
        if (tick < 0 || order.status() != ProductionOrderStatus.BATCH_IN_FLIGHT
                || !order.inFlightBatchId().equals(Optional.of(batchId))) {
            throw new IllegalArgumentException("production-order batch identity is not current");
        }
    }

    private static ProductionOrder copy(
            ProductionOrder order,
            ProductionOrderStatus status,
            int failures,
            long nextTick,
            Optional<ResourceId> batch,
            String code) {
        return new ProductionOrder(order.orderId(), order.ownerId(), order.warehouseId(),
                order.target(), order.targetStock(), order.verifiedBatchOutput(),
                order.maximumBatchesPerDispatch(), order.approvedProductionGraphId(),
                order.approvedRecipeIds(), order.approvedAdapterIds(), order.approvedSiteIds(),
                order.maximumRetries(), failures, nextTick, batch, status, code,
                Math.addExact(order.generation(), 1));
    }

    public record BatchAuthorization(
            ResourceId batchId,
            ResourceId orderId,
            ResourceId warehouseId,
            ResourceId productionGraphId,
            java.util.Set<ResourceId> recipeIds,
            java.util.Set<ResourceId> adapterIds,
            java.util.List<ResourceId> siteIds,
            long batches,
            long expectedOutput,
            long authorizedTick) {
        public BatchAuthorization {
            Objects.requireNonNull(batchId, "batchId");
            Objects.requireNonNull(orderId, "orderId");
            Objects.requireNonNull(warehouseId, "warehouseId");
            Objects.requireNonNull(productionGraphId, "productionGraphId");
            recipeIds = java.util.Set.copyOf(recipeIds);
            adapterIds = java.util.Set.copyOf(adapterIds);
            siteIds = java.util.List.copyOf(siteIds);
            if (batches < 1 || batches > 64 || expectedOutput < 1 || authorizedTick < 0) {
                throw new IllegalArgumentException("batch authorization is invalid");
            }
        }
    }

    public record Decision(
            ProductionOrder order,
            Optional<BatchAuthorization> authorization,
            String statusCode) {
        public Decision {
            Objects.requireNonNull(order, "order");
            authorization = Objects.requireNonNull(authorization, "authorization");
            Objects.requireNonNull(statusCode, "statusCode");
        }
        static Decision updated(ProductionOrder order, Optional<BatchAuthorization> value) {
            return new Decision(order, value, order.lastStatusCode());
        }
        static Decision noop(ProductionOrder order, String code) {
            return new Decision(order, Optional.empty(), code);
        }
    }
}
