package dev.stevecreate.agent.core.industrial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.electrical.ElectricalNetworkGraph;
import dev.stevecreate.agent.core.electrical.ElectricalNetworkNode;
import dev.stevecreate.agent.core.electrical.ElectricalNodeKind;
import dev.stevecreate.agent.core.electrical.ElectricalWireEdge;
import dev.stevecreate.agent.core.electrical.ProjectEnergyLedger;
import dev.stevecreate.agent.core.electrical.VoltageTier;
import dev.stevecreate.agent.core.fluid.FluidIdentity;
import dev.stevecreate.agent.core.fluid.FluidNetworkGraph;
import dev.stevecreate.agent.core.fluid.FluidNetworkNode;
import dev.stevecreate.agent.core.fluid.FluidNodeKind;
import dev.stevecreate.agent.core.fluid.FluidRoute;
import dev.stevecreate.agent.core.fluid.FluidTransactionState;
import dev.stevecreate.agent.core.fluid.ProjectFluidLedger;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.Direction6;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.warehouse.GlobalInventoryGraph;
import dev.stevecreate.agent.core.warehouse.WarehouseAllocation;
import dev.stevecreate.agent.core.warehouse.WarehouseEndpointSnapshot;
import dev.stevecreate.agent.core.warehouse.WarehouseEndpointType;
import dev.stevecreate.agent.core.warehouse.WarehouseLogisticsEdge;
import dev.stevecreate.agent.core.warehouse.WarehouseReservationRequest;
import dev.stevecreate.agent.core.warehouse.WarehouseReservationStatus;
import dev.stevecreate.agent.core.warehouse.WarehouseReservationSystem;
import dev.stevecreate.agent.core.warehouse.WarehouseResourceKey;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IndustrialResourceRecoveryV1Test {
    private static final ResourceId PROJECT = id("project:ipo03");
    private static final ResourceId OWNER = id("player:owner");
    private static final ResourceId DIMENSION = id("minecraft:overworld");
    private static final ResourceId FE = id("forge:energy");
    private static final FluidIdentity WATER = new FluidIdentity(
            id("minecraft:water"), FluidIdentity.EMPTY_COMPONENT_SHA256);
    private static final WarehouseResourceKey IRON = new WarehouseResourceKey(
            GenericResourceType.ITEM, id("minecraft:iron_ingot"),
            WarehouseResourceKey.EMPTY_COMPONENT_SHA256);

    @Test
    void oneBindingFreezesWarehouseBotsFeAndFluidIntoTheOrderFingerprint() {
        Fixture fixture = fixture(8, 0);
        IndustrialResourceBindingV1 binding = binding(fixture);
        IndustrialPlayerOrderPlanV1 plan = new IndustrialPlayerOrderPlanV1(
                id("steve_industrial:ipo03"), PROJECT, id("minecraft:iron_plate"), 1,
                TestIndustrialPlans.material(PROJECT, id("minecraft:iron_plate")),
                Optional.empty(), Optional.empty(), Optional.of(fixture.electrical),
                Optional.of(fixture.fluid), Optional.of(binding),
                List.of(IndustrialCapability.ITEM_PROCESSING, IndustrialCapability.LOGISTICS,
                        IndustrialCapability.ELECTRICAL_POWER, IndustrialCapability.FLUID_PROCESSING),
                dev.stevecreate.agent.core.execution.construction.ExecutionMode.BOTS, 2,
                "ipo03-runtime", "a".repeat(64));

        assertThat(binding.fingerprint()).hasSize(64);
        assertThat(plan.fingerprint()).hasSize(64);
        assertThat(plan.resourceBinding()).contains(binding);
        FluidNetworkGraph drifted = fluid(1, true);
        assertThatThrownBy(() -> new IndustrialPlayerOrderPlanV1(
                plan.orderType(), PROJECT, plan.target(), 1, plan.materialPlan(),
                Optional.empty(), Optional.empty(), Optional.of(fixture.electrical),
                Optional.of(drifted), Optional.of(binding), plan.capabilities(),
                plan.executionMode(), 2, "ipo03-runtime", "a".repeat(64)))
                .hasMessageContaining("networks differ");
    }

    @Test
    void crossProjectContentionCannotDoubleReserveOnePhysicalItem() {
        Fixture fixture = fixture(8, 0);
        WarehouseReservationSystem system = new WarehouseReservationSystem(fixture.warehouse);
        var first = system.reserveAtomically(List.of(fixture.request), 100);
        WarehouseReservationRequest competing = new WarehouseReservationRequest(
                id("request:other"), id("project:other"), OWNER, IRON, 1,
                List.of(id("endpoint:item_source")), 10_000, 0);
        var second = system.reserveAtomically(List.of(competing), 100);

        assertThat(first.success()).isTrue();
        assertThat(second.success()).isFalse();
        assertThat(second.failureCode()).isEqualTo("WAREHOUSE_MATERIALS_INSUFFICIENT");
        assertThat(system.allocations()).hasSize(1);
    }

    @Test
    void everyPhysicalEndpointDriftPausesWithItsOwnTypedCode() {
        Fixture fixture = fixture(8, 0);
        IndustrialResourceBindingV1 binding = binding(fixture);

        assertThat(IndustrialResourceRecoveryV1.reconcile(binding, fixture.warehouse,
                fixture.logistics, fixture.electrical, fixture.fluid).code())
                .isEqualTo("RESOURCE_BINDING_READY");
        assertThat(IndustrialResourceRecoveryV1.reconcile(binding, warehouse(8, 1),
                fixture.logistics, fixture.electrical, fixture.fluid).code())
                .isEqualTo("RESOURCE_BINDING_READY");
        assertThat(IndustrialResourceRecoveryV1.reconcile(binding, warehouseWithMovedRoute(),
                fixture.logistics, fixture.electrical, fixture.fluid).code())
                .isEqualTo("WAREHOUSE_ENDPOINT_DRIFT");
        assertThat(IndustrialResourceRecoveryV1.reconcile(binding, fixture.warehouse,
                logistics(2), fixture.electrical, fixture.fluid).code())
                .isEqualTo("ENTITY_LOGISTICS_DRIFT");
        assertThat(IndustrialResourceRecoveryV1.reconcile(binding, fixture.warehouse,
                fixture.logistics, electricalDisconnected(), fixture.fluid).code())
                .isEqualTo("ELECTRICAL_ENDPOINT_DRIFT");
        assertThat(IndustrialResourceRecoveryV1.reconcile(binding, fixture.warehouse,
                fixture.logistics, fixture.electrical, fluid(0, false)).code())
                .isEqualTo("FLUID_ENDPOINT_DRIFT");
    }

    @Test
    void reloadRestoresEveryLedgerWithoutDuplicateWithdrawEnergyOrFluid() {
        Fixture fixture = fixture(8, 0);
        WarehouseReservationSystem warehouse = new WarehouseReservationSystem(fixture.warehouse);
        WarehouseAllocation allocation = warehouse.reserveAtomically(
                List.of(fixture.request), 100).allocations().get(0);
        warehouse.advance(allocation.allocationId(), WarehouseReservationStatus.WITHDRAWN, 101);
        warehouse.advance(allocation.allocationId(), WarehouseReservationStatus.DELIVERED, 102);
        warehouse.advance(allocation.allocationId(), WarehouseReservationStatus.CONSUMED, 103);

        ProjectEnergyLedger energy = new ProjectEnergyLedger();
        energy.prepare(id("energy_tx:one"), PROJECT, id("task:machine"),
                id("energy:generator"), FE, 2_400, 1);
        energy.settle(id("energy_tx:one"), 2);

        ProjectFluidLedger fluid = new ProjectFluidLedger();
        fluid.prepare(id("fluid_tx:one"), PROJECT, id("task:machine"),
                id("fluid_node:tank"), WATER, 250, 1);
        fluid.advance(id("fluid_tx:one"), FluidTransactionState.WITHDRAWN, 2);
        fluid.advance(id("fluid_tx:one"), FluidTransactionState.DELIVERED, 3);
        fluid.advance(id("fluid_tx:one"), FluidTransactionState.CONSUMED, 4);

        WarehouseReservationSystem restoredWarehouse = WarehouseReservationSystem.restore(
                fixture.warehouse, warehouse.snapshot());
        ProjectEnergyLedger restoredEnergy = ProjectEnergyLedger.restore(energy.snapshot());
        ProjectFluidLedger restoredFluid = ProjectFluidLedger.restore(fluid.snapshot());
        restoredWarehouse.advance(allocation.allocationId(), WarehouseReservationStatus.CONSUMED, 200);
        restoredEnergy.settle(id("energy_tx:one"), 200);
        restoredFluid.advance(id("fluid_tx:one"), FluidTransactionState.CONSUMED, 200);

        var settled = IndustrialResourceRecoveryV1.settle(binding(fixture), restoredWarehouse,
                completedLogistics(fixture), restoredEnergy, restoredFluid);
        assertThat(settled.balanced()).isTrue();
        assertThat(settled.code()).isEqualTo("RESOURCE_LEDGER_BALANCED");
        assertThat(settled.settledEnergy()).containsExactlyEntriesOf(Map.of(FE, 2_400L));
        assertThat(settled.consumedFluids()).containsExactlyEntriesOf(Map.of(WATER, 250L));
        assertThat(settled.duplicateEnergySettlements()).isZero();
        assertThat(settled.duplicateFluidWithdrawals()).isZero();
        assertThat(settled.duplicateFluidReturns()).isZero();
        assertThat(restoredWarehouse.allocations()).hasSize(1);
        assertThat(settled.completionReport(Map.of(id("minecraft:iron_ingot"), 8L),
                Map.of(id("minecraft:iron_ingot"), 8L),
                Map.of(id("minecraft:iron_ingot"), 8L), Map.of(),
                Map.of(id("minecraft:iron_plate"), 1L), 0, 0, true, "f".repeat(64)))
                .satisfies(report -> {
                    assertThat(report.materialLedgerBalanced()).isTrue();
                    assertThat(report.energyConsumed()).containsExactlyEntriesOf(Map.of(FE, 2_400L));
                    assertThat(report.fluidConsumed()).containsExactlyEntriesOf(
                            Map.of(id("minecraft:water"), 250L));
                    assertThat(report.duplicateWithdrawals()).isZero();
                    assertThat(report.duplicateEnergySettlements()).isZero();
                    assertThat(report.duplicateOutputs()).isZero();
                    assertThat(report.unaccountedItems()).isZero();
                    assertThat(report.privateItemsTouched()).isZero();
                });
    }

    @Test
    void exactSnapshotDriftIsRefusedInsteadOfSilentlySelectingAnotherEndpoint() {
        Fixture fixture = fixture(8, 0);
        WarehouseReservationSystem warehouse = new WarehouseReservationSystem(fixture.warehouse);
        warehouse.reserveAtomically(List.of(fixture.request), 100);

        assertThatThrownBy(() -> WarehouseReservationSystem.restore(
                warehouseWithMovedRoute(), warehouse.snapshot()))
                .hasMessageContaining("snapshot drifted");
    }

    @Test
    void emptyOrPartiallyTerminalWarehouseCannotMasqueradeAsASettledOrder() {
        Fixture fixture = fixture(8, 0);
        ProjectEnergyLedger energy = settledEnergy();
        ProjectFluidLedger fluid = consumedFluid();
        WarehouseReservationSystem empty = new WarehouseReservationSystem(fixture.warehouse);

        assertThat(IndustrialResourceRecoveryV1.settle(
                binding(fixture), empty, completedLogistics(fixture), energy, fluid)).satisfies(result -> {
            assertThat(result.balanced()).isFalse();
            assertThat(result.code()).isEqualTo("WAREHOUSE_SETTLEMENT_MISMATCH");
        });

        WarehouseReservationSystem partial = new WarehouseReservationSystem(fixture.warehouse);
        WarehouseAllocation allocation = partial.reserveAtomically(
                List.of(fixture.request), 100).allocations().get(0);
        partial.advance(allocation.allocationId(), WarehouseReservationStatus.WITHDRAWN, 101);
        assertThat(IndustrialResourceRecoveryV1.settle(
                binding(fixture), partial, completedLogistics(fixture), energy, fluid).code())
                .isEqualTo("WAREHOUSE_LEDGER_UNBALANCED");
    }

    @Test
    void incompleteOrDuplicateBotAssignmentsRefuseTheCommonReport() {
        Fixture fixture = fixture(8, 0);
        WarehouseReservationSystem warehouse = consumedWarehouse(fixture);
        EntityLogisticsSettlementV1 incomplete = new EntityLogisticsSettlementV1(
                fixture.logistics.sessionId(), fixture.logistics.taskGraphFingerprint(),
                List.of(), 0, 0, 0);
        var result = IndustrialResourceRecoveryV1.settle(binding(fixture), warehouse,
                incomplete, settledEnergy(), consumedFluid());
        assertThat(result.balanced()).isFalse();
        assertThat(result.code()).isEqualTo("ENTITY_LOGISTICS_SETTLEMENT_MISMATCH");
        assertThatThrownBy(() -> result.completionReport(Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(id("minecraft:iron_plate"), 1L), 0, 0, true, "f".repeat(64)))
                .hasMessageContaining("cannot complete");
    }

    @Test
    void physicalRouteAndInterfaceChangesAlterEveryGraphFingerprint() {
        Fixture fixture = fixture(8, 0);
        assertThat(warehouse(8, 0).fingerprint()).isNotEqualTo(warehouseWithMovedRoute().fingerprint());
        assertThat(electrical(0).fingerprint()).isNotEqualTo(electricalDisconnected().fingerprint());
        assertThat(fluid(0, true).fingerprint()).isNotEqualTo(fluid(0, false).fingerprint());
        assertThat(IndustrialResourceRecoveryV1.reconcile(binding(fixture), fixture.warehouse,
                fixture.logistics, fixture.electrical, fluid(0, false)).code())
                .isEqualTo("FLUID_ENDPOINT_DRIFT");
    }

    @Test
    void oneCheckpointRestoresAllLedgersOnlyAfterExactLiveGraphsReregister() {
        Fixture fixture = fixture(8, 0);
        IndustrialResourceBindingV1 binding = binding(fixture);
        IndustrialResourceRuntimeV1 original = new IndustrialResourceRuntimeV1(binding,
                consumedWarehouse(fixture), settledEnergy(), consumedFluid(),
                Optional.of(completedLogistics(fixture)), 4);
        IndustrialResourceCheckpointV1 checkpoint = original.checkpoint(200);

        var restored = IndustrialResourceRuntimeV1.restore(binding, checkpoint,
                warehouse(0, 0), fixture.logistics, fixture.electrical,
                fluidState(0, true, 750), 300);
        assertThat(restored.restored()).isTrue();
        IndustrialResourceRuntimeV1 runtime = restored.runtime().orElseThrow();
        runtime.energy().settle(id("energy_tx:one"), 301);
        runtime.fluid().advance(id("fluid_tx:one"), FluidTransactionState.CONSUMED, 301);
        assertThat(IndustrialResourceRecoveryV1.settle(binding, runtime.warehouse(),
                runtime.logisticsSettlement().orElseThrow(), runtime.energy(), runtime.fluid()))
                .satisfies(result -> {
                    assertThat(result.balanced()).isTrue();
                    assertThat(result.duplicateEnergySettlements()).isZero();
                    assertThat(result.duplicateFluidWithdrawals()).isZero();
                });

        assertThat(IndustrialResourceRuntimeV1.restore(binding, checkpoint,
                warehouseWithMovedRoute(), fixture.logistics, fixture.electrical,
                fluidState(0, true, 750), 300).code())
                .isEqualTo("WAREHOUSE_ENDPOINT_DRIFT");
        assertThat(IndustrialResourceRuntimeV1.restore(binding, checkpoint,
                warehouse(0, 0), fixture.logistics, fixture.electrical,
                fluidState(0, true, 750), 199).code())
                .isEqualTo("RESOURCE_TICK_REGRESSION");
        assertThat(IndustrialResourceRuntimeV1.restore(binding, checkpoint,
                warehouse(1, 0), fixture.logistics, fixture.electrical,
                fluidState(0, true, 750), 300).code())
                .isEqualTo("WAREHOUSE_CONTENTS_DRIFT");
        assertThat(IndustrialResourceRuntimeV1.restore(binding, checkpoint,
                warehouse(0, 0), fixture.logistics, fixture.electrical,
                fluidState(0, true, 749), 300).code())
                .isEqualTo("FLUID_CONTENTS_DRIFT");
    }

    private static ProjectEnergyLedger settledEnergy() {
        ProjectEnergyLedger energy = new ProjectEnergyLedger();
        energy.prepare(id("energy_tx:one"), PROJECT, id("task:machine"),
                id("energy:generator"), FE, 2_400, 1);
        energy.settle(id("energy_tx:one"), 2);
        return energy;
    }

    private static ProjectFluidLedger consumedFluid() {
        ProjectFluidLedger fluid = new ProjectFluidLedger();
        fluid.prepare(id("fluid_tx:one"), PROJECT, id("task:machine"),
                id("fluid_node:tank"), WATER, 250, 1);
        fluid.advance(id("fluid_tx:one"), FluidTransactionState.WITHDRAWN, 2);
        fluid.advance(id("fluid_tx:one"), FluidTransactionState.DELIVERED, 3);
        fluid.advance(id("fluid_tx:one"), FluidTransactionState.CONSUMED, 4);
        return fluid;
    }

    private static WarehouseReservationSystem consumedWarehouse(Fixture fixture) {
        WarehouseReservationSystem warehouse = new WarehouseReservationSystem(fixture.warehouse);
        WarehouseAllocation allocation = warehouse.reserveAtomically(
                List.of(fixture.request), 100).allocations().get(0);
        warehouse.advance(allocation.allocationId(), WarehouseReservationStatus.WITHDRAWN, 101);
        warehouse.advance(allocation.allocationId(), WarehouseReservationStatus.DELIVERED, 102);
        warehouse.advance(allocation.allocationId(), WarehouseReservationStatus.CONSUMED, 103);
        return warehouse;
    }

    private static EntityLogisticsSettlementV1 completedLogistics(Fixture fixture) {
        return new EntityLogisticsSettlementV1(fixture.logistics.sessionId(),
                fixture.logistics.taskGraphFingerprint(), fixture.logistics.assignmentIds(),
                0, 0, 0);
    }

    private static IndustrialResourceBindingV1 binding(Fixture fixture) {
        return new IndustrialResourceBindingV1(PROJECT, OWNER, "world", DIMENSION,
                fixture.warehouse, List.of(fixture.request), fixture.logistics,
                Optional.of(fixture.electrical), Optional.of(fixture.fluid),
                Map.of(FE, 2_400L), Map.of(WATER, 250L));
    }

    private static Fixture fixture(long itemCount, long generation) {
        GlobalInventoryGraph warehouse = warehouse(itemCount, generation);
        WarehouseReservationRequest request = new WarehouseReservationRequest(
                id("request:ipo03"), PROJECT, OWNER, IRON, itemCount,
                List.of(id("endpoint:item_source")), 10_000, 0);
        return new Fixture(warehouse, request, logistics(1), electrical(0), fluid(0, true));
    }

    private static GlobalInventoryGraph warehouse(long itemCount, long generation) {
        ResourceId warehouse = id("warehouse:player");
        var source = new WarehouseEndpointSnapshot(id("endpoint:item_source"), warehouse, OWNER,
                "world", DIMENSION, new BlockPos3i(0, 0, 0), Optional.of(Direction6.UP),
                id("minecraft:chest"), WarehouseEndpointType.ITEM_CONTAINER,
                itemCount == 0 ? Map.of() : Map.of(IRON, itemCount), 64,
                hash(generation == 0 ? 'a' : 'c'),
                generation, 10_000, true);
        var destination = new WarehouseEndpointSnapshot(id("endpoint:machine_input"), warehouse,
                OWNER, "world", DIMENSION, new BlockPos3i(4, 0, 0), Optional.of(Direction6.UP),
                id("minecraft:chest"), WarehouseEndpointType.ITEM_CONTAINER,
                Map.of(), 64, "b".repeat(64), generation, 10_000, true);
        var edge = new WarehouseLogisticsEdge(id("route:source_machine"), source.endpointId(),
                destination.endpointId(), Set.of(GenericResourceType.ITEM),
                List.of(source.position(), new BlockPos3i(2, 0, 0), destination.position()),
                64, "d".repeat(64), generation, true, true);
        return new GlobalInventoryGraph(warehouse, OWNER, "world", DIMENSION,
                List.of(source, destination), List.of(edge), generation);
    }

    private static GlobalInventoryGraph warehouseWithMovedRoute() {
        GlobalInventoryGraph original = warehouse(8, 0);
        WarehouseLogisticsEdge old = original.edges().values().iterator().next();
        WarehouseLogisticsEdge moved = new WarehouseLogisticsEdge(old.edgeId(),
                old.fromEndpointId(), old.toEndpointId(), old.supportedResourceTypes(),
                List.of(new BlockPos3i(0, 0, 0), new BlockPos3i(2, 1, 0),
                        new BlockPos3i(4, 0, 0)), old.capacityPerTick(), old.pathSha256(),
                old.generation(), true, true);
        return new GlobalInventoryGraph(original.warehouseId(), OWNER, "world", DIMENSION,
                List.copyOf(original.endpoints().values()), List.of(moved), 0);
    }

    private static EntityLogisticsBindingV1 logistics(long generation) {
        return new EntityLogisticsBindingV1(id("fleet:session"), id("graph:tasks"),
                "e".repeat(64), List.of(id("worker:one"), id("worker:two")),
                List.of(id("assignment:carry")), generation, generation);
    }

    private static ElectricalNetworkGraph electrical(long generation) {
        ElectricalNetworkNode generator = new ElectricalNetworkNode(id("energy:generator"),
                ElectricalNodeKind.GENERATOR, new BlockPos3i(0, 0, 4), VoltageTier.LV,
                Optional.empty(), Set.of(), Set.of(Direction6.EAST), 128, 0, 0, 0,
                hash(generation == 0 ? 'f' : '1'), true);
        ElectricalNetworkNode consumer = new ElectricalNetworkNode(id("energy:consumer"),
                ElectricalNodeKind.CONSUMER, new BlockPos3i(4, 0, 4), VoltageTier.LV,
                Optional.empty(), Set.of(Direction6.WEST), Set.of(), 0, 64, 0, 0,
                "2".repeat(64), true);
        ElectricalWireEdge wire = new ElectricalWireEdge(id("energy:wire"), generator.nodeId(),
                consumer.nodeId(), VoltageTier.LV, 4, 16, 128,
                List.of(generator.position(), consumer.position()), "3".repeat(64),
                true, true, true);
        return new ElectricalNetworkGraph(id("energy:graph"), "world", DIMENSION,
                List.of(generator, consumer), List.of(wire), generation);
    }

    private static ElectricalNetworkGraph electricalDisconnected() {
        ElectricalNetworkGraph graph = electrical(0);
        ElectricalWireEdge old = graph.edges().values().iterator().next();
        ElectricalWireEdge disconnected = new ElectricalWireEdge(old.edgeId(), old.fromNodeId(),
                old.toNodeId(), old.voltageTier(), old.transferDistance(), old.maximumDistance(),
                old.capacityPerTick(), old.collisionEnvelope(), old.connectionSha256(),
                false, old.collisionFree(), true);
        return new ElectricalNetworkGraph(graph.graphId(), "world", DIMENSION,
                List.copyOf(graph.nodes().values()), List.of(disconnected), 0);
    }

    private static FluidNetworkGraph fluid(long generation, boolean valveOpen) {
        return fluidState(generation, valveOpen, 1_000);
    }

    private static FluidNetworkGraph fluidState(
            long generation, boolean valveOpen, long sourceAmount) {
        FluidNetworkNode tank = new FluidNetworkNode(id("fluid_node:tank"), FluidNodeKind.TANK,
                new BlockPos3i(0, 0, 8), sourceAmount == 0 ? Optional.empty() : Optional.of(WATER),
                sourceAmount, 4_000,
                Set.of(Direction6.WEST), Set.of(Direction6.EAST), 0,
                hash(generation == 0 ? '4' : '6'), true);
        FluidNetworkNode pump = new FluidNetworkNode(id("fluid_node:pump"), FluidNodeKind.PUMP,
                new BlockPos3i(2, 0, 8), Optional.empty(), 0, 1_000,
                Set.of(Direction6.WEST), Set.of(Direction6.EAST), 250, "7".repeat(64), true);
        FluidNetworkNode machine = new FluidNetworkNode(id("fluid_node:machine"),
                FluidNodeKind.MACHINE_INPUT, new BlockPos3i(4, 0, 8), Optional.empty(), 0, 1_000,
                Set.of(Direction6.WEST), Set.of(), 0, "8".repeat(64), true);
        FluidRoute first = route("tank_pump", tank, pump, valveOpen);
        FluidRoute second = route("pump_machine", pump, machine, valveOpen);
        return new FluidNetworkGraph(id("fluid:graph"), "world", DIMENSION,
                List.of(tank, pump, machine), List.of(first, second), generation);
    }

    private static FluidRoute route(
            String name, FluidNetworkNode from, FluidNetworkNode to, boolean valveOpen) {
        return new FluidRoute(id("fluid_route:" + name), from.nodeId(), to.nodeId(), WATER,
                List.of(from.position(), to.position()), 250, 250, true,
                valveOpen, true, true, "9".repeat(64), true);
    }

    private static String hash(char value) { return Character.toString(value).repeat(64); }
    private static ResourceId id(String value) { return ResourceId.parse(value); }
    private record Fixture(
            GlobalInventoryGraph warehouse,
            WarehouseReservationRequest request,
            EntityLogisticsBindingV1 logistics,
            ElectricalNetworkGraph electrical,
            FluidNetworkGraph fluid) {}
}
