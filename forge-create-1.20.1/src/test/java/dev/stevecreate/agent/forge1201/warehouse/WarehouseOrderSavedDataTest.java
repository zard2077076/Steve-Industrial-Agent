package dev.stevecreate.agent.forge1201.warehouse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.warehouse.ProductionOrder;
import dev.stevecreate.agent.core.warehouse.ProductionOrderStatus;
import dev.stevecreate.agent.core.warehouse.WarehouseResourceKey;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class WarehouseOrderSavedDataTest {
    @Test
    void roundTripsExactAllowlistAndInFlightRecoveryIdentity() {
        WarehouseOrderSavedData data = new WarehouseOrderSavedData();
        ProductionOrder order = new ProductionOrder(
                id("test:plate_order"), id("test:owner"), id("test:warehouse"),
                new WarehouseResourceKey(GenericResourceType.ITEM,
                        id("immersiveengineering:plate_iron"),
                        WarehouseResourceKey.EMPTY_COMPONENT_SHA256),
                64, 1, 8, id("test:metal_press_graph"),
                Set.of(id("immersiveengineering:metalpress/plate_iron")),
                Set.of(id("steve_industrial:immersive_engineering_v1020")),
                List.of(id("test:approved_site")), 3, 0, 120,
                Optional.of(id("order:batch_test_plate_order_1")),
                ProductionOrderStatus.BATCH_IN_FLIGHT, "BATCH_AUTHORIZED", 4);
        data.put(order);

        WarehouseOrderSavedData restored = WarehouseOrderSavedData.load(
                data.save(new CompoundTag()));

        assertThat(restored.orders()).containsOnlyKeys(order.orderId());
        assertThat(restored.order(order.orderId())).contains(order);
    }

    @Test
    void dispatchResultCannotCarryAnUnboundedOrBlankFailureCode() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new WarehouseOrderService.DispatchResult(false, " "))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new WarehouseOrderService.DispatchResult(false, "x".repeat(257)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
