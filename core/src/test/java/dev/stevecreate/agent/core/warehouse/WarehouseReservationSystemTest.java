package dev.stevecreate.agent.core.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WarehouseReservationSystemTest {
    private static final WarehouseResourceKey SHAFT = new WarehouseResourceKey(
            GenericResourceType.ITEM, id("create:shaft"),
            WarehouseResourceKey.EMPTY_COMPONENT_SHA256);

    @Test
    void atomicallyReservesAcrossBoundContainersAndPreventsCrossProjectDoubleSpend() {
        WarehouseReservationSystem system = new WarehouseReservationSystem(graph(6, 8));
        var first = system.reserveAtomically(List.of(request("p1", "r1", 10)), 100);
        var second = system.reserveAtomically(List.of(request("p2", "r2", 5)), 100);

        assertThat(first.success()).isTrue();
        assertThat(first.allocations()).extracting(WarehouseAllocation::quantity)
                .containsExactly(6L, 4L);
        assertThat(second.success()).isFalse();
        assertThat(second.failureCode()).isEqualTo("WAREHOUSE_MATERIALS_INSUFFICIENT");
        assertThat(system.allocations()).hasSize(2);
    }

    @Test
    void transactionStatesBalanceAndExpiryReleasesUntouchedStock() {
        WarehouseReservationSystem system = new WarehouseReservationSystem(graph(12, 0));
        var reserved = system.reserveAtomically(List.of(request("p1", "r1", 12)), 100)
                .allocations().get(0);
        system.advance(reserved.allocationId(), WarehouseReservationStatus.WITHDRAWN, 101);
        system.advance(reserved.allocationId(), WarehouseReservationStatus.DELIVERED, 102);
        system.advance(reserved.allocationId(), WarehouseReservationStatus.CONSUMED, 103);

        assertThat(system.balance(id("project:p1"))).satisfies(report -> {
            assertThat(report.consumed()).isEqualTo(12);
            assertThat(report.unaccounted()).isZero();
            assertThat(report.balanced()).isTrue();
        });

        WarehouseReservationSystem expiring = new WarehouseReservationSystem(graph(12, 0));
        expiring.reserveAtomically(List.of(request("p2", "r2", 4)), 100);
        assertThat(expiring.expire(2_000)).isEqualTo(1);
        assertThat(expiring.allocations().values())
                .extracting(WarehouseAllocation::status)
                .containsExactly(WarehouseReservationStatus.EXPIRED);
    }

    @Test
    void restoresDeliveredThenReturnedWithoutDuplicateEventsAndRejectsTopologyDrift() {
        GlobalInventoryGraph original = graph(12, 0);
        WarehouseReservationSystem system = new WarehouseReservationSystem(original);
        WarehouseAllocation allocation = system.reserveAtomically(
                List.of(request("p1", "return", 4)), 100).allocations().get(0);
        system.advance(allocation.allocationId(), WarehouseReservationStatus.WITHDRAWN, 101);
        system.advance(allocation.allocationId(), WarehouseReservationStatus.DELIVERED, 102);
        system.advance(allocation.allocationId(), WarehouseReservationStatus.RETURN_PENDING, 103);
        system.advance(allocation.allocationId(), WarehouseReservationStatus.RETURNED, 104);

        WarehouseReservationSystem restored = WarehouseReservationSystem.restore(
                original, system.snapshot());
        restored.advance(allocation.allocationId(), WarehouseReservationStatus.RETURNED, 105);
        assertThat(restored.balance(id("project:p1"))).satisfies(report -> {
            assertThat(report.returned()).isEqualTo(4);
            assertThat(report.duplicateReturns()).isZero();
            assertThat(report.balanced()).isTrue();
        });

        GlobalInventoryGraph moved = movedRouteGraph(12, 0);
        assertThatThrownBy(() -> WarehouseReservationSystem.restore(moved, system.snapshot()))
                .hasMessageContaining("snapshot drifted");
    }

    private static GlobalInventoryGraph graph(long first, long second) {
        ResourceId warehouse = id("warehouse:player");
        ResourceId owner = id("player:owner");
        var a = endpoint("a", warehouse, owner, new BlockPos3i(0, 0, 0), first);
        var b = endpoint("b", warehouse, owner, new BlockPos3i(4, 0, 0), second);
        return new GlobalInventoryGraph(warehouse, owner, "world", id("minecraft:overworld"),
                List.of(a, b), List.of(new WarehouseLogisticsEdge(
                        id("warehouse:a_to_b"), a.endpointId(), b.endpointId(),
                        java.util.Set.of(GenericResourceType.ITEM),
                        List.of(a.position(), b.position()), 64, "d".repeat(64), 0, true, true)), 0);
    }

    private static GlobalInventoryGraph movedRouteGraph(long first, long second) {
        GlobalInventoryGraph original = graph(first, second);
        WarehouseLogisticsEdge old = original.edges().values().iterator().next();
        WarehouseLogisticsEdge moved = new WarehouseLogisticsEdge(old.edgeId(),
                old.fromEndpointId(), old.toEndpointId(), old.supportedResourceTypes(),
                List.of(new BlockPos3i(0, 0, 0), new BlockPos3i(2, 1, 0),
                        new BlockPos3i(4, 0, 0)), old.capacityPerTick(), old.pathSha256(),
                old.generation(), true, true);
        return new GlobalInventoryGraph(original.warehouseId(), original.ownerId(),
                original.worldIdentity(), original.dimension(),
                List.copyOf(original.endpoints().values()), List.of(moved), original.generation());
    }

    private static WarehouseEndpointSnapshot endpoint(
            String name, ResourceId warehouse, ResourceId owner, BlockPos3i pos, long quantity) {
        return new WarehouseEndpointSnapshot(id("endpoint:" + name), warehouse, owner, "world",
                id("minecraft:overworld"), pos, Optional.empty(), id("minecraft:chest"),
                WarehouseEndpointType.ITEM_CONTAINER,
                quantity == 0 ? Map.of() : Map.of(SHAFT, quantity), 64,
                (name.equals("a") ? "a" : "b").repeat(64), 0, 10_000, true);
    }

    private static WarehouseReservationRequest request(String project, String request, long quantity) {
        return new WarehouseReservationRequest(id("request:" + request), id("project:" + project),
                id("player:owner"), SHAFT, quantity,
                List.of(id("endpoint:a"), id("endpoint:b")), 1_000, 0);
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
