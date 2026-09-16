package dev.stevecreate.agent.forge1201.adapter.immersiveengineering.internal.v1020;

import blusunrize.immersiveengineering.api.multiblocks.blocks.logic.IMultiblockBE;
import blusunrize.immersiveengineering.api.multiblocks.blocks.registry.MultiblockBlockEntityMaster;
import blusunrize.immersiveengineering.api.wires.Connection;
import blusunrize.immersiveengineering.api.wires.GlobalWireNetwork;
import blusunrize.immersiveengineering.api.wires.localhandlers.EnergyTransferHandler.IEnergyWire;
import blusunrize.immersiveengineering.common.blocks.metal.EnergyConnectorBlockEntity;
import blusunrize.immersiveengineering.common.blocks.multiblocks.IEMultiblocks;
import blusunrize.immersiveengineering.common.blocks.multiblocks.logic.MetalPressLogic;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.EnumSet;
import dev.stevecreate.agent.core.electrical.ElectricalNetworkGraph;
import dev.stevecreate.agent.core.electrical.ElectricalNetworkNode;
import dev.stevecreate.agent.core.electrical.ElectricalNodeKind;
import dev.stevecreate.agent.core.electrical.ElectricalWireEdge;
import dev.stevecreate.agent.core.electrical.VoltageTier;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.Direction6;
import dev.stevecreate.agent.core.model.ResourceId;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Version-pinned, stateless physical operations for the production IE Metal Press order.
 *
 * <p>No operation grants authority, reads player containers, writes FE, or claims a ledger
 * effect. Each method is safe to call only after the order service has persisted the matching
 * authorization checkpoint.</p>
 */
public final class ImmersiveEngineeringV1020MetalPressProduction {
    public static final int REQUIRED_ENERGY_FE = 2_400;
    private static final BlockPos INPUT_POS = new BlockPos(0, 1, 0);
    private static final BlockPos ENERGY_POS = new BlockPos(1, 2, 0);
    private static final BlockPos OUTPUT_POS = new BlockPos(3, 1, 0);
    private static final int POWER_OFFSET_Z = 5;

    private ImmersiveEngineeringV1020MetalPressProduction() {}

    /** Superset of every position this fixed-orientation order may mutate. */
    public static List<BlockPos> baselinePositions(ServerLevel level, BlockPos origin) {
        requireServer(level);
        LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
        for (var component : IEMultiblocks.METAL_PRESS.getStructure(level)) {
            BlockPos raw = origin.offset(component.pos()).immutable();
            result.add(raw);
            BlockPos connectorCandidate = raw.above().immutable();
            result.add(connectorCandidate);
            BlockPos generator = connectorCandidate.offset(0, -1, POWER_OFFSET_Z).immutable();
            result.add(generator);
            result.add(generator.above().immutable());
            result.add(generator.east().immutable());
            result.add(generator.west().immutable());
        }
        if (result.size() > 64) throw new IllegalStateException("IE production baseline is unbounded");
        return result.stream().sorted(positionOrder()).toList();
    }

    public static StructureResult placeStructure(ServerLevel level, BlockPos origin) {
        requireServer(level);
        // The press is one of twenty-odd IETemplateMultiblocks and nothing here was ever
        // specific to it; the shared version lives beside this class now.
        var result = ImmersiveEngineeringV1020MultiblockFormation.place(
                level, IEMultiblocks.METAL_PRESS, origin);
        return new StructureResult(result.success(), result.code(), result.placedBlocks());
    }

    /** Exact raw seven-block evidence used only to reconcile placement before its checkpoint. */
    public static boolean rawStructurePresent(ServerLevel level, BlockPos origin) {
        requireServer(level);
        return ImmersiveEngineeringV1020MultiblockFormation.rawStructurePresent(
                level, IEMultiblocks.METAL_PRESS, origin);
    }

    public static boolean multiblockFormed(ServerLevel level, BlockPos origin) {
        return inspect(level, origin).isPresent();
    }

    public static boolean plateMoldInstalled(ServerLevel level, BlockPos origin, ItemStack mold) {
        Machine machine = inspect(level, origin).orElse(null);
        return machine != null && !machine.state().mold.isEmpty()
                && ItemStack.isSameItemSameTags(machine.state().mold, mold);
    }

    public static OperationResult form(ServerLevel level, BlockPos origin, ItemStack hammer) {
        requireServer(level);
        if (hammer.isEmpty()) return OperationResult.failure("HAMMER_REQUIRED");
        // The hand is borrowed and given back inside the shared helper. Doing it here as
        // well would leave the actor holding the hammer: the helper restores whatever it
        // saw on entry, which would be the hammer this method had just put there.
        boolean formed = ImmersiveEngineeringV1020MultiblockFormation.form(
                level, IEMultiblocks.METAL_PRESS, origin, Direction.SOUTH, actor(level), hammer);
        return formed && inspect(level, origin).isPresent()
                ? OperationResult.success("MULTIBLOCK_FORMED")
                : OperationResult.failure("MULTIBLOCK_FORMATION_FAILED");
    }

    public static OperationResult insertMold(
            ServerLevel level, BlockPos origin, ItemStack mold) {
        Machine machine = inspect(level, origin).orElse(null);
        if (machine == null) return OperationResult.failure("MULTIBLOCK_MASTER_NOT_FOUND");
        if (!machine.state().mold.isEmpty()) {
            return ItemStack.isSameItemSameTags(machine.state().mold, mold)
                    ? OperationResult.success("MOLD_ALREADY_INSTALLED")
                    : OperationResult.failure("DIFFERENT_MOLD_ALREADY_INSTALLED");
        }
        FakePlayer actor = actor(level);
        ItemStack previous = actor.getItemInHand(InteractionHand.MAIN_HAND).copy();
        actor.setItemInHand(InteractionHand.MAIN_HAND, mold.copy());
        try {
            InteractionResult result = machine.master().getHelper().click(actor,
                    InteractionHand.MAIN_HAND, new BlockHitResult(
                            Vec3.atCenterOf(machine.masterPosition()), Direction.UP,
                            machine.masterPosition(), false));
            return result.consumesAction() && !machine.state().mold.isEmpty()
                    && ItemStack.isSameItemSameTags(machine.state().mold, mold)
                    ? OperationResult.success("MOLD_INSTALLED")
                    : OperationResult.failure("MOLD_INSERTION_FAILED");
        } finally {
            actor.setItemInHand(InteractionHand.MAIN_HAND, previous);
        }
    }

    /** Builds a real thermoelectric-LV-wire network using the actual copper-coil interaction. */
    public static PowerResult buildPowerNetwork(
            ServerLevel level, BlockPos origin, ItemStack copperWireCoil) {
        Machine machine = inspect(level, origin).orElse(null);
        if (machine == null) return PowerResult.failure("MULTIBLOCK_MASTER_NOT_FOUND");
        if (copperWireCoil.isEmpty()) return PowerResult.failure("COPPER_WIRE_COIL_REQUIRED");
        PowerLayout layout = layout(machine);
        for (BlockPos position : List.of(layout.machineConnector(), layout.generator(),
                layout.generatorConnector(), layout.coldSource(), layout.hotSource())) {
            if (!level.hasChunkAt(position)) return PowerResult.failure("POWER_CHUNK_NOT_LOADED");
            BlockState current = level.getBlockState(position);
            if (!current.isAir() && !current.canBeReplaced()) {
                return PowerResult.failure("POWER_SITE_BLOCKED");
            }
        }
        Block thermoelectric = block("immersiveengineering:thermoelectric_generator");
        Block connector = block("immersiveengineering:connector_lv");
        if (!level.setBlockAndUpdate(layout.generator(), thermoelectric.defaultBlockState())) {
            return PowerResult.failure("GENERATOR_PLACEMENT_FAILED");
        }
        if (!placeDownConnector(level, layout.generatorConnector(), connector)
                || !placeDownConnector(level, layout.machineConnector(), connector)) {
            return PowerResult.failure("CONNECTOR_PLACEMENT_FAILED");
        }
        // Use IE's real 200 K / 1300 K solid thermoelectric sources. Unlike bucket fluids these
        // cannot flow outside the captured baseline and later re-enter after teardown.
        if (!level.setBlockAndUpdate(layout.coldSource(), Blocks.BLUE_ICE.defaultBlockState())
                || !level.setBlockAndUpdate(layout.hotSource(), Blocks.MAGMA_BLOCK.defaultBlockState())) {
            return PowerResult.failure("THERMAL_SOURCE_PLACEMENT_FAILED");
        }
        FakePlayer actor = actor(level);
        ItemStack previous = actor.getItemInHand(InteractionHand.MAIN_HAND).copy();
        ItemStack workingCoil = copperWireCoil.copy();
        actor.setItemInHand(InteractionHand.MAIN_HAND, workingCoil);
        try {
            InteractionResult first = workingCoil.getItem().useOn(context(
                    actor, layout.generatorConnector(), Direction.UP));
            InteractionResult second = workingCoil.getItem().useOn(context(
                    actor, layout.machineConnector(), Direction.UP));
            if (!first.consumesAction() || !second.consumesAction()) {
                return PowerResult.failure("WIRE_CONNECTION_FAILED");
            }
        } finally {
            actor.setItemInHand(InteractionHand.MAIN_HAND, previous);
        }
        long connections = externalConnections(level, layout.generatorConnector()).size();
        boolean coilConsumed = workingCoil.getCount() == copperWireCoil.getCount() - 1;
        return connections == 1 && coilConsumed
                ? new PowerResult(true, "POWER_NETWORK_BUILT", layout, 1, connections)
                : PowerResult.failure(connections != 1
                        ? "WIRE_NETWORK_EVIDENCE_MISMATCH" : "WIRE_NOT_CONSUMED");
    }

    public static PowerObservation observePower(ServerLevel level, BlockPos origin) {
        Machine machine = inspect(level, origin).orElse(null);
        if (machine == null) return new PowerObservation(false, 0, 0, "MULTIBLOCK_MASTER_NOT_FOUND");
        PowerLayout layout = layout(machine);
        long connections = externalConnections(level, layout.machineConnector()).size();
        int stored = machine.state().getEnergy().getEnergyStored();
        return new PowerObservation(connections == 1 && stored > 0, stored, connections,
                connections == 1 && stored > 0 ? "POWER_VERIFIED" : "POWER_NOT_YET_VERIFIED");
    }

    /** Loader-neutral, read-only topology for IPO-03 production recovery. */
    public static Optional<ElectricalNetworkGraph> observeResourceNetwork(
            ServerLevel level, BlockPos origin, String worldIdentity, long generation) {
        Machine machine = inspect(level, origin).orElse(null);
        if (machine == null) return Optional.empty();
        PowerLayout layout = layout(machine);
        List<Connection> connections = externalConnections(level, layout.generatorConnector());
        if (connections.size() != 1 || !externalConnections(level, layout.machineConnector())
                .contains(connections.get(0))) return Optional.empty();
        Connection connection = connections.get(0);
        Set<BlockPos> ends = Set.of(connection.getEndA().position(), connection.getEndB().position());
        if (!ends.equals(Set.of(layout.generatorConnector(), layout.machineConnector()))
                || !(connection.type instanceof IEnergyWire energyWire)
                || !"LV".equals(connection.type.getCategory())) return Optional.empty();
        ResourceId generatorId = resource("steve_industrial:energy/metal_press_generator");
        ResourceId connectorId = resource("steve_industrial:energy/metal_press_connector");
        ResourceId storageId = resource("steve_industrial:energy/metal_press_storage");
        ElectricalNetworkNode generator = new ElectricalNetworkNode(generatorId,
                ElectricalNodeKind.GENERATOR, cell(layout.generatorConnector()), VoltageTier.LV,
                Optional.empty(), Set.of(), EnumSet.allOf(Direction6.class), 1, 0, 0, 0,
                digest(level.getBlockState(layout.generator()) + "|"
                        + level.getBlockState(layout.generatorConnector())), true);
        ElectricalNetworkNode connector = new ElectricalNetworkNode(connectorId,
                ElectricalNodeKind.CONNECTOR, cell(layout.machineConnector()), VoltageTier.LV,
                Optional.empty(), EnumSet.allOf(Direction6.class), EnumSet.allOf(Direction6.class),
                0, 0, 0, 0, digest(level.getBlockState(layout.machineConnector()).toString()), true);
        ElectricalNetworkNode storage = new ElectricalNetworkNode(storageId,
                ElectricalNodeKind.STORAGE, cell(machine.energyPosition()), VoltageTier.LV,
                Optional.empty(), EnumSet.allOf(Direction6.class), EnumSet.allOf(Direction6.class),
                0, 0, machine.state().getEnergy().getEnergyStored(),
                machine.state().getEnergy().getMaxEnergyStored(),
                digest("metal-press-storage|" + machine.energyPosition() + "|"
                        + machine.state().getEnergy().getEnergyStored()), true);
        ElectricalWireEdge edge = new ElectricalWireEdge(
                resource("steve_industrial:energy/metal_press_lv_wire"), generatorId, connectorId,
                VoltageTier.LV, Math.max(1, (int) Math.ceil(connection.getLength())),
                connection.type.getMaxLength(), energyWire.getTransferRate(),
                List.of(cell(layout.generatorConnector()), cell(layout.machineConnector())),
                digest(connection.toNBT().toString()), true, true, true);
        ElectricalWireEdge internal = new ElectricalWireEdge(
                resource("steve_industrial:energy/metal_press_internal"), connectorId, storageId,
                VoltageTier.LV, 1, 1, energyWire.getTransferRate(),
                List.of(cell(layout.machineConnector()), cell(machine.energyPosition())),
                digest(layout.machineConnector() + "|" + machine.energyPosition()),
                true, true, true);
        return Optional.of(new ElectricalNetworkGraph(
                resource("steve_industrial:energy/metal_press_graph"), worldIdentity,
                resource(level.dimension().location().toString()),
                List.of(generator, connector, storage), List.of(edge, internal), generation));
    }

    /** Stops generation without deleting the verified connector/wire topology. */
    public static OperationResult stopThermalGeneration(ServerLevel level, BlockPos origin) {
        Machine machine = inspect(level, origin).orElse(null);
        if (machine == null) return OperationResult.failure("MULTIBLOCK_MASTER_NOT_FOUND");
        PowerLayout layout = layout(machine);
        boolean cold = level.setBlockAndUpdate(layout.coldSource(), Blocks.AIR.defaultBlockState());
        boolean hot = level.setBlockAndUpdate(layout.hotSource(), Blocks.AIR.defaultBlockState());
        return cold && hot ? OperationResult.success("THERMAL_GENERATION_STOPPED")
                : OperationResult.failure("THERMAL_GENERATION_STOP_FAILED");
    }

    /** Re-enables the order-owned solid thermal pair after a non-persistent machine FE reload. */
    public static OperationResult startThermalGeneration(ServerLevel level, BlockPos origin) {
        OperationResult preflight = thermalGenerationStartPreflight(level, origin);
        if (!preflight.success()) return preflight;
        Machine machine = inspect(level, origin).orElse(null);
        PowerLayout layout = layout(machine);
        BlockState cold = level.getBlockState(layout.coldSource());
        BlockState hot = level.getBlockState(layout.hotSource());
        boolean coldPlaced = cold.is(Blocks.BLUE_ICE)
                || level.setBlockAndUpdate(layout.coldSource(), Blocks.BLUE_ICE.defaultBlockState());
        boolean hotPlaced = hot.is(Blocks.MAGMA_BLOCK)
                || level.setBlockAndUpdate(layout.hotSource(), Blocks.MAGMA_BLOCK.defaultBlockState());
        if (coldPlaced && hotPlaced) return OperationResult.success("THERMAL_GENERATION_STARTED");
        // The two source cells form one bounded maintenance mutation. Never leave half a
        // generator behind when the second setBlock call fails.
        level.setBlockAndUpdate(layout.coldSource(), cold);
        level.setBlockAndUpdate(layout.hotSource(), hot);
        return OperationResult.failure("THERMAL_GENERATION_START_ROLLED_BACK");
    }

    /** Read-only exact-cell preflight for the narrow maintenance executor. */
    public static OperationResult thermalGenerationStartPreflight(
            ServerLevel level, BlockPos origin) {
        Machine machine = inspect(level, origin).orElse(null);
        if (machine == null) return OperationResult.failure("MULTIBLOCK_MASTER_NOT_FOUND");
        PowerLayout layout = layout(machine);
        BlockState cold = level.getBlockState(layout.coldSource());
        BlockState hot = level.getBlockState(layout.hotSource());
        if ((!cold.isAir() && !cold.is(Blocks.BLUE_ICE))
                || (!hot.isAir() && !hot.is(Blocks.MAGMA_BLOCK))) {
            return OperationResult.failure("THERMAL_SOURCE_SITE_DRIFT");
        }
        if (cold.is(Blocks.BLUE_ICE) && hot.is(Blocks.MAGMA_BLOCK)) {
            return OperationResult.failure("THERMAL_GENERATION_ALREADY_ACTIVE");
        }
        return OperationResult.success("THERMAL_GENERATION_START_READY");
    }

    public static boolean thermalGenerationActive(ServerLevel level, BlockPos origin) {
        Machine machine = inspect(level, origin).orElse(null);
        if (machine == null) return false;
        PowerLayout layout = layout(machine);
        return level.getBlockState(layout.coldSource()).is(Blocks.BLUE_ICE)
                && level.getBlockState(layout.hotSource()).is(Blocks.MAGMA_BLOCK);
    }

    /** Returns the number of exact order-owned thermal source cells currently present. */
    public static int thermalGenerationSourceCount(ServerLevel level, BlockPos origin) {
        Machine machine = inspect(level, origin).orElse(null);
        if (machine == null) return -1;
        PowerLayout layout = layout(machine);
        int count = level.getBlockState(layout.coldSource()).is(Blocks.BLUE_ICE) ? 1 : 0;
        return count + (level.getBlockState(layout.hotSource()).is(Blocks.MAGMA_BLOCK) ? 1 : 0);
    }

    public static InputResult feedInput(
            ServerLevel level, BlockPos origin, ItemStack input) {
        Machine machine = inspect(level, origin).orElse(null);
        if (machine == null) return InputResult.failure("MULTIBLOCK_MASTER_NOT_FOUND");
        if (input.isEmpty()) return InputResult.failure("PROCESS_INPUT_REQUIRED");
        int before = machine.state().getEnergy().getEnergyStored();
        ItemEntity item = new ItemEntity(level,
                machine.inputPosition().getX() + 0.5D,
                machine.inputPosition().getY() + 0.5D,
                machine.inputPosition().getZ() + 0.5D, input.copy());
        if (!level.addFreshEntity(item)) return InputResult.failure("ITEM_INPUT_SPAWN_FAILED");
        machine.input().getHelper().onEntityCollided(item);
        boolean accepted = !item.isAlive() || item.getItem().getCount() < input.getCount();
        if (!accepted) {
            item.discard();
            return InputResult.failure("ITEM_INPUT_REJECTED");
        }
        return new InputResult(true, "INPUT_ACCEPTED", item.getUUID(), before);
    }

    public static OutputObservation observeUniqueOutput(
            ServerLevel level, BlockPos origin, ItemStack expectedOutput, int energyAtInput) {
        Machine machine = inspect(level, origin).orElse(null);
        if (machine == null) return OutputObservation.failure("MULTIBLOCK_MASTER_NOT_FOUND");
        List<ItemEntity> matches = level.getEntitiesOfClass(ItemEntity.class,
                new AABB(machine.outputPosition()).inflate(4.0D), entity -> entity.isAlive()
                        && ItemStack.isSameItemSameTags(entity.getItem(), expectedOutput));
        long count = matches.stream().mapToLong(value -> value.getItem().getCount()).sum();
        int consumed = Math.max(0, energyAtInput - machine.state().getEnergy().getEnergyStored());
        return new OutputObservation(count == 1 && consumed == REQUIRED_ENERGY_FE,
                count == 0 ? "OUTPUT_NOT_YET_OBSERVED"
                        : count != 1 ? "OUTPUT_NOT_UNIQUE"
                        : consumed != REQUIRED_ENERGY_FE ? "ENERGY_CONSUMPTION_MISMATCH" : "OUTPUT_VERIFIED",
                count, consumed, matches.stream().map(ItemEntity::getUUID).toList());
    }

    public static ItemStack claimUniqueOutput(
            ServerLevel level, BlockPos origin, ItemStack expectedOutput) {
        Machine machine = inspect(level, origin).orElseThrow();
        List<ItemEntity> matches = level.getEntitiesOfClass(ItemEntity.class,
                new AABB(machine.outputPosition()).inflate(4.0D), entity -> entity.isAlive()
                        && ItemStack.isSameItemSameTags(entity.getItem(), expectedOutput));
        long count = matches.stream().mapToLong(value -> value.getItem().getCount()).sum();
        if (count != 1 || matches.size() != 1) {
            throw new IllegalStateException("Metal Press output is not exactly one entity/item");
        }
        ItemStack result = matches.get(0).getItem().copy();
        result.setCount(1);
        matches.get(0).discard();
        return result;
    }

    /** Read-only crash/reload evidence for an admitted real IE process. */
    public static ProcessingSnapshot processingSnapshot(
            ServerLevel level, BlockPos origin, int energyAtInput) {
        Machine machine = inspect(level, origin).orElse(null);
        if (machine == null) return new ProcessingSnapshot(false, 0, -1, "", 0, 0);
        var queue = machine.state().processor.getQueue();
        int processTick = queue.size() == 1 ? queue.get(0).processTick : -1;
        String recipeId = queue.size() == 1 ? queue.get(0).getRecipeId().toString() : "";
        int stored = machine.state().getEnergy().getEnergyStored();
        return new ProcessingSnapshot(true, queue.size(), processTick, recipeId, stored,
                Math.max(0, energyAtInput - stored));
    }

    public static Optional<Machine> inspect(ServerLevel level, BlockPos origin) {
        requireServer(level);
        MultiblockBlockEntityMaster<?> master = null;
        MetalPressLogic.State state = null;
        IMultiblockBE<?> input = null;
        BlockPos energyPosition = null;
        for (var component : IEMultiblocks.METAL_PRESS.getStructure(level)) {
            BlockPos position = origin.offset(component.pos());
            BlockEntity entity = level.getBlockEntity(position);
            if (entity instanceof MultiblockBlockEntityMaster<?> candidate
                    && candidate.getHelper().getState() instanceof MetalPressLogic.State value) {
                master = candidate;
                state = value;
            }
            if (entity instanceof IMultiblockBE<?> multiblock) {
                if (INPUT_POS.equals(multiblock.getHelper().getPositionInMB())) input = multiblock;
                if (ENERGY_POS.equals(multiblock.getHelper().getPositionInMB())) {
                    energyPosition = position.immutable();
                }
            }
        }
        if (master == null || state == null || input == null || energyPosition == null) {
            return Optional.empty();
        }
        BlockPos output = master.getHelper().getContext().getLevel().toAbsolute(OUTPUT_POS).immutable();
        return Optional.of(new Machine(master, state, input, master.getBlockPos().immutable(),
                ((BlockEntity) input).getBlockPos().immutable(), energyPosition, output));
    }

    public static PowerLayout layout(Machine machine) {
        BlockPos machineConnector = machine.energyPosition().above().immutable();
        BlockPos generator = machineConnector.offset(0, -1, POWER_OFFSET_Z).immutable();
        return new PowerLayout(machineConnector, generator, generator.above().immutable(),
                generator.east().immutable(), generator.west().immutable());
    }

    private static boolean placeDownConnector(ServerLevel level, BlockPos position, Block block) {
        if (!level.setBlockAndUpdate(position, block.defaultBlockState())) return false;
        BlockEntity entity = level.getBlockEntity(position);
        if (!(entity instanceof EnergyConnectorBlockEntity connector)) return false;
        @SuppressWarnings("unchecked")
        Property<Direction> facing = (Property<Direction>) connector.getFacingProperty();
        BlockState state = level.getBlockState(position);
        return state.hasProperty(facing)
                && level.setBlockAndUpdate(position, state.setValue(facing, Direction.DOWN));
    }

    private static UseOnContext context(FakePlayer actor, BlockPos position, Direction face) {
        return new UseOnContext(actor, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(position), face, position, false));
    }

    private static List<Connection> externalConnections(ServerLevel level, BlockPos connector) {
        return GlobalWireNetwork.getNetwork(level).getLocalNet(connector)
                .getConnections(connector).stream().filter(value -> !value.isInternal())
                .sorted(Comparator.comparing(Object::toString)).toList();
    }

    private static Block block(String id) {
        Block block = ForgeRegistries.BLOCKS.getValue(ResourceLocation.parse(id));
        if (block == null || block == Blocks.AIR) {
            throw new IllegalStateException("required IE block is unavailable: " + id);
        }
        return block;
    }

    private static BlockPos3i cell(BlockPos value) {
        return new BlockPos3i(value.getX(), value.getY(), value.getZ());
    }

    private static ResourceId resource(String value) { return ResourceId.parse(value); }

    private static String digest(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest
                    .getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static FakePlayer actor(ServerLevel level) {
        return FakePlayerFactory.getMinecraft(level);
    }

    private static void requireServer(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("IE production operation requires the server thread");
        }
    }

    private static Comparator<BlockPos> positionOrder() {
        return Comparator.comparingInt((BlockPos value) -> value.getX())
                .thenComparingInt(value -> value.getY())
                .thenComparingInt(value -> value.getZ());
    }

    public record Machine(MultiblockBlockEntityMaster<?> master, MetalPressLogic.State state,
            IMultiblockBE<?> input, BlockPos masterPosition, BlockPos inputPosition,
            BlockPos energyPosition, BlockPos outputPosition) {}

    public record PowerLayout(BlockPos machineConnector, BlockPos generator,
            BlockPos generatorConnector, BlockPos coldSource, BlockPos hotSource) {}

    public record StructureResult(boolean success, String code, int placedBlocks) {}

    public record OperationResult(boolean success, String code) {
        static OperationResult success(String code) { return new OperationResult(true, code); }
        static OperationResult failure(String code) { return new OperationResult(false, code); }
    }

    public record PowerResult(boolean success, String code, PowerLayout layout,
            int consumedWireCoils, long externalConnections) {
        static PowerResult failure(String code) { return new PowerResult(false, code, null, 0, 0); }
    }

    public record PowerObservation(boolean verified, int storedEnergyFe,
            long externalConnections, String code) {}

    public record InputResult(boolean success, String code, java.util.UUID entityId,
            int energyAtInput) {
        static InputResult failure(String code) { return new InputResult(false, code, null, 0); }
    }

    public record OutputObservation(boolean verified, String code, long outputCount,
            int energyConsumedFe, List<java.util.UUID> outputEntityIds) {
        public OutputObservation { outputEntityIds = List.copyOf(outputEntityIds); }
        static OutputObservation failure(String code) {
            return new OutputObservation(false, code, 0, 0, List.of());
        }
    }

    public record ProcessingSnapshot(boolean machinePresent, int queueSize,
            int processTick, String recipeId, int storedEnergyFe, int observedEnergyDeltaFe) {}
}
