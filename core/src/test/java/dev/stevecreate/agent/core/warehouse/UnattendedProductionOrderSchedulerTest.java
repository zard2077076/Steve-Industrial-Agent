package dev.stevecreate.agent.core.warehouse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UnattendedProductionOrderSchedulerTest {
    @Test
    void dispatchesOneBoundedApprovedBatchAndStopsAtTarget() {
        UnattendedProductionOrderScheduler scheduler = new UnattendedProductionOrderScheduler();
        ProductionOrder order = order();

        var decision = scheduler.evaluate(order, 3, 100);
        assertThat(decision.authorization()).isPresent().get().satisfies(batch -> {
            assertThat(batch.batches()).isEqualTo(4);
            assertThat(batch.expectedOutput()).isEqualTo(8);
            assertThat(batch.warehouseId()).isEqualTo(order.warehouseId());
            assertThat(batch.siteIds()).containsExactly(id("site:one"));
        });
        assertThat(decision.order().status()).isEqualTo(ProductionOrderStatus.BATCH_IN_FLIGHT);
        assertThat(scheduler.evaluate(decision.order(), 3, 101).authorization()).isEmpty();

        ProductionOrder settled = scheduler.batchSucceeded(decision.order(),
                decision.authorization().orElseThrow().batchId(), 120);
        assertThat(scheduler.evaluate(settled, 10, 200).order().status())
                .isEqualTo(ProductionOrderStatus.TARGET_SATISFIED);
    }

    @Test
    void appliesBoundedBackoffThenPausesWithoutExpandingAuthority() {
        UnattendedProductionOrderScheduler scheduler = new UnattendedProductionOrderScheduler();
        ProductionOrder current = order();
        long tick = 100;
        for (int attempt = 0; attempt < 3; attempt++) {
            var dispatch = scheduler.evaluate(current, 0, tick);
            var batch = dispatch.authorization().orElseThrow();
            current = scheduler.batchFailed(dispatch.order(), batch.batchId(), "SOURCE_DRIFT", tick + 1);
            tick = current.nextEligibleTick();
        }
        var last = scheduler.evaluate(current, 0, tick);
        var lastBatch = last.authorization().orElseThrow();
        current = scheduler.batchFailed(last.order(), lastBatch.batchId(), "SOURCE_DRIFT", tick + 1);

        assertThat(current.status()).isEqualTo(ProductionOrderStatus.PAUSED);
        assertThat(current.lastStatusCode()).startsWith("RETRY_BUDGET_EXHAUSTED");
        assertThat(current.approvedSiteIds()).containsExactly(id("site:one"));
        assertThat(current.approvedRecipeIds()).containsExactly(id("create:pressing/iron"));
    }

    private static ProductionOrder order() {
        return new ProductionOrder(id("order:iron_sheet"), id("player:owner"),
                id("warehouse:main"), new WarehouseResourceKey(GenericResourceType.ITEM,
                id("create:iron_sheet"), WarehouseResourceKey.EMPTY_COMPONENT_SHA256),
                10, 2, 4, id("graph:iron_sheet"),
                Set.of(id("create:pressing/iron")), Set.of(id("create:v606")),
                List.of(id("site:one")), 3, 0, 0, Optional.empty(),
                ProductionOrderStatus.ACTIVE, "ORDER_CREATED", 0);
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
