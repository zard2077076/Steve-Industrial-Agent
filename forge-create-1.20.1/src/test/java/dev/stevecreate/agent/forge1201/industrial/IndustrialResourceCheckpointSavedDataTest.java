package dev.stevecreate.agent.forge1201.industrial;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.electrical.EnergyTransaction;
import dev.stevecreate.agent.core.electrical.EnergyTransactionState;
import dev.stevecreate.agent.core.electrical.ProjectEnergyLedger;
import dev.stevecreate.agent.core.electrical.ProjectEnergyLedgerSnapshot;
import dev.stevecreate.agent.core.fluid.FluidIdentity;
import dev.stevecreate.agent.core.fluid.FluidTransaction;
import dev.stevecreate.agent.core.fluid.FluidTransactionState;
import dev.stevecreate.agent.core.fluid.ProjectFluidLedger;
import dev.stevecreate.agent.core.fluid.ProjectFluidLedgerSnapshot;
import dev.stevecreate.agent.core.industrial.EntityLogisticsSettlementV1;
import dev.stevecreate.agent.core.industrial.IndustrialResourceCheckpointV1;
import dev.stevecreate.agent.core.industrial.IndustrialResourceBindingV1;
import dev.stevecreate.agent.core.industrial.EntityLogisticsBindingV1;
import dev.stevecreate.agent.core.electrical.ElectricalNetworkGraph;
import dev.stevecreate.agent.core.electrical.ElectricalNetworkNode;
import dev.stevecreate.agent.core.electrical.ElectricalNodeKind;
import dev.stevecreate.agent.core.electrical.ElectricalWireEdge;
import dev.stevecreate.agent.core.electrical.VoltageTier;
import dev.stevecreate.agent.core.fluid.FluidNetworkGraph;
import dev.stevecreate.agent.core.fluid.FluidNetworkNode;
import dev.stevecreate.agent.core.fluid.FluidNodeKind;
import dev.stevecreate.agent.core.fluid.FluidRoute;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.Direction6;
import dev.stevecreate.agent.core.warehouse.GlobalInventoryGraph;
import dev.stevecreate.agent.core.warehouse.WarehouseEndpointSnapshot;
import dev.stevecreate.agent.core.warehouse.WarehouseEndpointType;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.warehouse.WarehouseAllocation;
import dev.stevecreate.agent.core.warehouse.WarehouseReservationRequest;
import dev.stevecreate.agent.core.warehouse.WarehouseReservationSnapshot;
import dev.stevecreate.agent.core.warehouse.WarehouseReservationStatus;
import dev.stevecreate.agent.core.warehouse.WarehouseResourceKey;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

class IndustrialResourceCheckpointSavedDataTest {
    private static final ResourceId PROJECT = id("project:ipo03");
    private static final ResourceId REQUEST = id("request:ipo03");
    private static final ResourceId ALLOCATION = id("allocation:ipo03");
    private static final ResourceId ENDPOINT = id("endpoint:item_source");
    private static final ResourceId ENERGY_TRANSACTION = id("energy_tx:one");
    private static final ResourceId FLUID_TRANSACTION = id("fluid_tx:one");
    private static final FluidIdentity WATER = new FluidIdentity(
            id("minecraft:water"), FluidIdentity.EMPTY_COMPONENT_SHA256);

    @Test
    void roundTripsOneCheckpointWithoutInventingLivePhysicalAuthority() {
        IndustrialResourceCheckpointV1 checkpoint = checkpoint();
        IndustrialResourceCheckpointSavedData data = new IndustrialResourceCheckpointSavedData();
        data.put(checkpoint);
        CompoundTag encoded = data.save(new CompoundTag());
        IndustrialResourceCheckpointV1 restored =
                IndustrialResourceCheckpointSavedData.load(encoded).checkpoint(PROJECT).orElseThrow();

        assertThat(restored).isEqualTo(checkpoint);
        assertThat(encoded.toString()).doesNotContain("LiveRuntime", "CapabilityHandle");
        ProjectEnergyLedger energy = ProjectEnergyLedger.restore(restored.energy());
        ProjectFluidLedger fluid = ProjectFluidLedger.restore(restored.fluid());
        energy.settle(ENERGY_TRANSACTION, 201);
        fluid.advance(FLUID_TRANSACTION, FluidTransactionState.CONSUMED, 201);
        assertThat(energy.balance(PROJECT).duplicateSettlements()).isZero();
        assertThat(fluid.balance(PROJECT).duplicateWithdrawals()).isZero();
    }

    @Test
    void roundTripsImmutableBindingSelectorsAlongsideTheirMatchingCheckpoint() {
        IndustrialResourceBindingV1 binding = binding();
        IndustrialResourceCheckpointV1 checkpoint = new IndustrialResourceCheckpointV1(
                PROJECT, binding.fingerprint(), checkpoint().warehouse(), checkpoint().energy(),
                checkpoint().fluid(), checkpoint().logisticsSettlement(), 200);
        IndustrialResourceCheckpointSavedData data = new IndustrialResourceCheckpointSavedData();
        data.put(binding, checkpoint);

        IndustrialResourceCheckpointSavedData restored =
                IndustrialResourceCheckpointSavedData.load(data.save(new CompoundTag()));
        IndustrialResourceBindingV1 restoredBinding = restored.binding(PROJECT).orElseThrow();
        assertThat(IndustrialResourceBindingNbt.encode(restoredBinding))
                .isEqualTo(IndustrialResourceBindingNbt.encode(binding));
        assertThat(restored.checkpoint(PROJECT)).contains(checkpoint);
    }

    @Test
    void aTamperedJournalDropsTheCheckpointInsteadOfRecreatingAuthority() {
        IndustrialResourceCheckpointSavedData data = new IndustrialResourceCheckpointSavedData();
        data.put(checkpoint());
        CompoundTag encoded = data.save(new CompoundTag());
        CompoundTag row = encoded.getList("Checkpoints", Tag.TAG_COMPOUND).getCompound(0);
        CompoundTag energy = row.getCompound("Energy");
        ListTag journal = energy.getList("Journal", Tag.TAG_STRING);
        journal.add(StringTag.valueOf("2|2|SETTLED|energy_tx:one"));
        energy.put("Journal", journal);

        assertThat(IndustrialResourceCheckpointSavedData.load(encoded).checkpoint(PROJECT))
                .isEmpty();
    }

    @Test
    void aBindingThatNoLongerMatchesItsCheckpointDropsTheAtomicPair() {
        IndustrialResourceBindingV1 binding = binding();
        IndustrialResourceCheckpointV1 checkpoint = new IndustrialResourceCheckpointV1(
                PROJECT, binding.fingerprint(), checkpoint().warehouse(), checkpoint().energy(),
                checkpoint().fluid(), checkpoint().logisticsSettlement(), 200);
        IndustrialResourceCheckpointSavedData data = new IndustrialResourceCheckpointSavedData();
        data.put(binding, checkpoint);
        CompoundTag encoded = data.save(new CompoundTag());
        CompoundTag bindingRow = encoded.getList("Bindings", Tag.TAG_COMPOUND).getCompound(0);
        bindingRow.getList("PlannedEnergy", Tag.TAG_COMPOUND).getCompound(0)
                .putLong("Amount", 2_401);

        IndustrialResourceCheckpointSavedData restored =
                IndustrialResourceCheckpointSavedData.load(encoded);
        assertThat(restored.binding(PROJECT)).isEmpty();
        assertThat(restored.checkpoint(PROJECT)).isEmpty();
    }

    private static IndustrialResourceCheckpointV1 checkpoint() {
        WarehouseResourceKey iron = new WarehouseResourceKey(GenericResourceType.ITEM,
                id("minecraft:iron_ingot"), WarehouseResourceKey.EMPTY_COMPONENT_SHA256);
        WarehouseReservationRequest request = new WarehouseReservationRequest(
                REQUEST, PROJECT, id("player:owner"), iron, 8, List.of(ENDPOINT), 10_000, 0);
        WarehouseAllocation allocation = new WarehouseAllocation(ALLOCATION, REQUEST, PROJECT,
                ENDPOINT, iron, 8, WarehouseReservationStatus.CONSUMED, 4, 103);
        WarehouseReservationSnapshot warehouse = new WarehouseReservationSnapshot(
                "a".repeat(64), Map.of(REQUEST, request), Map.of(ALLOCATION, allocation),
                List.of("1|100|RESERVED|" + ALLOCATION,
                        "2|101|WITHDRAWN|" + ALLOCATION,
                        "3|102|DELIVERED|" + ALLOCATION,
                        "4|103|CONSUMED|" + ALLOCATION), 4, 103);
        EnergyTransaction energy = new EnergyTransaction(ENERGY_TRANSACTION, PROJECT,
                id("task:machine"), id("endpoint:generator"), id("forge:energy"), 2_400,
                EnergyTransactionState.SETTLED, 2, 2);
        ProjectEnergyLedgerSnapshot energySnapshot = new ProjectEnergyLedgerSnapshot(
                Map.of(ENERGY_TRANSACTION, energy),
                List.of("1|1|PREPARED|" + ENERGY_TRANSACTION,
                        "2|2|SETTLED|" + ENERGY_TRANSACTION), 2, 2);
        FluidTransaction fluid = new FluidTransaction(FLUID_TRANSACTION, PROJECT,
                id("task:machine"), id("fluid_node:tank"), WATER, 250,
                FluidTransactionState.CONSUMED, 4, 4);
        ProjectFluidLedgerSnapshot fluidSnapshot = new ProjectFluidLedgerSnapshot(
                Map.of(FLUID_TRANSACTION, fluid),
                List.of("1|1|PREPARED|" + FLUID_TRANSACTION,
                        "2|2|WITHDRAWN|" + FLUID_TRANSACTION,
                        "3|3|DELIVERED|" + FLUID_TRANSACTION,
                        "4|4|CONSUMED|" + FLUID_TRANSACTION), 4, 4);
        EntityLogisticsSettlementV1 logistics = new EntityLogisticsSettlementV1(
                id("fleet:session"), "b".repeat(64), List.of(id("assignment:carry")),
                0, 0, 0);
        return new IndustrialResourceCheckpointV1(PROJECT, "c".repeat(64), warehouse,
                energySnapshot, fluidSnapshot, Optional.of(logistics), 200);
    }

    private static IndustrialResourceBindingV1 binding() {
        WarehouseResourceKey iron = new WarehouseResourceKey(GenericResourceType.ITEM,
                id("minecraft:iron_ingot"), WarehouseResourceKey.EMPTY_COMPONENT_SHA256);
        ResourceId owner = id("player:owner");
        ResourceId warehouseId = id("warehouse:ipo03");
        ResourceId dimension = id("minecraft:overworld");
        WarehouseEndpointSnapshot endpoint = new WarehouseEndpointSnapshot(ENDPOINT, warehouseId,
                owner, "world", dimension, new BlockPos3i(0, 64, 0), Optional.of(Direction6.UP),
                id("minecraft:chest"), WarehouseEndpointType.ITEM_CONTAINER, Map.of(iron, 8L),
                1728, "a".repeat(64), 0, Long.MAX_VALUE, true);
        GlobalInventoryGraph warehouse = new GlobalInventoryGraph(warehouseId, owner, "world",
                dimension, List.of(endpoint), List.of(), 0);
        WarehouseReservationRequest request = new WarehouseReservationRequest(REQUEST, PROJECT,
                owner, iron, 8, List.of(ENDPOINT), Long.MAX_VALUE, 0);
        EntityLogisticsBindingV1 logistics = new EntityLogisticsBindingV1(
                id("fleet:ipo03"), id("graph:ipo03"), "b".repeat(64),
                List.of(id("bot:one"), id("bot:two")), List.of(id("assignment:carry")), 1, 1);
        ElectricalNetworkNode generator = new ElectricalNetworkNode(id("energy:generator"),
                ElectricalNodeKind.GENERATOR, new BlockPos3i(0, 64, 4), VoltageTier.LV,
                Optional.empty(), Set.of(), Set.of(Direction6.EAST), 1, 0, 0, 0,
                "c".repeat(64), true);
        ElectricalNetworkNode storage = new ElectricalNetworkNode(id("endpoint:generator"),
                ElectricalNodeKind.STORAGE, new BlockPos3i(4, 64, 4), VoltageTier.LV,
                Optional.empty(), Set.of(Direction6.WEST), Set.of(Direction6.EAST), 0, 0,
                5_000, 100_000, "d".repeat(64), true);
        ElectricalWireEdge wire = new ElectricalWireEdge(id("energy:wire"), generator.nodeId(),
                storage.nodeId(), VoltageTier.LV, 4, 16, 256,
                List.of(generator.position(), storage.position()), "e".repeat(64),
                true, true, true);
        ElectricalNetworkGraph electrical = new ElectricalNetworkGraph(id("energy:graph"),
                "world", dimension, List.of(generator, storage), List.of(wire), 0);
        FluidNetworkNode source = new FluidNetworkNode(id("fluid_node:tank"), FluidNodeKind.TANK,
                new BlockPos3i(0, 64, 8), Optional.of(WATER), 1_000, 8_000,
                Set.of(Direction6.WEST), Set.of(Direction6.EAST), 0, "f".repeat(64), true);
        FluidNetworkNode destination = new FluidNetworkNode(id("fluid_node:machine"),
                FluidNodeKind.MACHINE_INPUT, new BlockPos3i(4, 64, 8), Optional.empty(), 0, 8_000,
                Set.of(Direction6.WEST), Set.of(), 0, "1".repeat(64), true);
        FluidRoute route = new FluidRoute(id("fluid_route:one"), source.nodeId(),
                destination.nodeId(), WATER, List.of(source.position(), destination.position()),
                250, 8_000, false, true, true, true, "2".repeat(64), true);
        FluidNetworkGraph fluid = new FluidNetworkGraph(id("fluid:graph"), "world", dimension,
                List.of(source, destination), List.of(route), 0);
        return new IndustrialResourceBindingV1(PROJECT, owner, "world", dimension, warehouse,
                List.of(request), logistics, Optional.of(electrical), Optional.of(fluid),
                Map.of(id("forge:energy"), 2_400L), Map.of(WATER, 250L));
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
