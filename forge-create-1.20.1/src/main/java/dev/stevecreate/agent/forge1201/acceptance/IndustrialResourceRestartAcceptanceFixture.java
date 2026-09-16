package dev.stevecreate.agent.forge1201.acceptance;

import blusunrize.immersiveengineering.api.wires.GlobalWireNetwork;
import blusunrize.immersiveengineering.common.blocks.multiblocks.IEMultiblocks;
import blusunrize.immersiveengineering.common.blocks.metal.CapacitorBlockEntity;
import blusunrize.immersiveengineering.api.IEEnums;
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
import dev.stevecreate.agent.core.industrial.EntityLogisticsBindingV1;
import dev.stevecreate.agent.core.industrial.EntityLogisticsSettlementV1;
import dev.stevecreate.agent.core.industrial.IndustrialResourceBindingV1;
import dev.stevecreate.agent.core.industrial.IndustrialResourceRecoveryV1;
import dev.stevecreate.agent.core.industrial.IndustrialResourceRuntimeV1;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.Direction6;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.warehouse.GlobalInventoryGraph;
import dev.stevecreate.agent.core.warehouse.WarehouseAllocation;
import dev.stevecreate.agent.core.warehouse.WarehouseReservationRequest;
import dev.stevecreate.agent.core.warehouse.WarehouseReservationStatus;
import dev.stevecreate.agent.core.warehouse.WarehouseReservationSystem;
import dev.stevecreate.agent.core.warehouse.WarehouseResourceKey;
import dev.stevecreate.agent.forge1201.adapter.immersiveengineering.internal.v1020.ImmersiveEngineeringV1020MetalPressProduction;
import dev.stevecreate.agent.forge1201.adapter.immersiveengineering.internal.v1020.ImmersiveEngineeringV1020MultiblockFormation;
import dev.stevecreate.agent.forge1201.command.CompositePlayerOrderReloadProbe;
import dev.stevecreate.agent.forge1201.command.WarehouseTopology;
import dev.stevecreate.agent.forge1201.entity.ConstructionBotEntities;
import dev.stevecreate.agent.forge1201.entity.ConstructionBotEntity;
import dev.stevecreate.agent.forge1201.fluid.FluidPipeTopology;
import dev.stevecreate.agent.forge1201.fluid.ForgeFluidTransactionExecutor;
import dev.stevecreate.agent.forge1201.industrial.IndustrialResourceCheckpointSavedData;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.common.util.FakePlayerFactory;
import org.slf4j.Logger;

/** Two-JVM physical IPO-03 warehouse/Bot/FE/fluid recovery gate. */
public final class IndustrialResourceRestartAcceptanceFixture {
    public static final String PHASE_PROPERTY = "steve_industrial.test.ipo03ResourceRestartPhase";
    private static final ResourceId PROJECT = id("steve_industrial:project/ipo03_restart");
    private static final ResourceId OWNER = id("steve_industrial:owner/ipo03_restart");
    private static final ResourceId WAREHOUSE = id("steve_industrial:warehouse/ipo03_restart");
    private static final ResourceId REQUEST = id("steve_industrial:request/ipo03_iron");
    private static final ResourceId ENERGY_TX = id("steve_industrial:energy_tx/ipo03");
    private static final ResourceId FLUID_TX = id("steve_industrial:fluid_tx/ipo03");
    private static final ResourceId ENERGY_SOURCE = id("steve_industrial:energy/press_storage");
    private static final ResourceId ENERGY_CONSUMER = id("steve_industrial:energy/press_consumer");
    private static final ResourceId FLUID_SOURCE = id("steve_industrial:fluid/source_tank");
    private static final ResourceId FE = id("forge:energy");
    private static final WarehouseResourceKey IRON = new WarehouseResourceKey(
            GenericResourceType.ITEM, id("minecraft:iron_ingot"),
            WarehouseResourceKey.EMPTY_COMPONENT_SHA256);
    private static final int MATERIAL_COUNT = 8;
    private static final int ENERGY_FE = 2_400;
    private static final int FLUID_MB = 250;
    private static final int TIMEOUT_TICKS = 4_000;
    private static Active active;

    private IndustrialResourceRestartAcceptanceFixture() {}

    public static void start(MinecraftServer server, String phase, Logger logger) {
        AcceptanceRuntimeGuard.requireDevelopmentRuntime("IndustrialResourceRestartAcceptanceFixture");
        if (!phase.equals("write") && !phase.equals("read")) {
            throw new IllegalArgumentException("unknown IPO-03 restart phase " + phase);
        }
        active = new Active(server, phase, logger);
    }

    public static void tick(MinecraftServer server) {
        Active current = active;
        if (current == null || current.server != server) return;
        try {
            current.tick();
        } catch (RuntimeException failure) {
            current.logger.error("IPO03_RESOURCE_RESTART FAIL phase={}", current.phase, failure);
            active = null;
            server.halt(false);
            throw failure;
        }
    }

    private static final class Active {
        private final MinecraftServer server;
        private final ServerLevel level;
        private final String phase;
        private final Logger logger;
        private final int started;
        private boolean prepared;
        private boolean generationStopped;
        private int lastEnergy = -1;
        private int stableEnergyTicks;
        private BlockPos origin;

        private Active(MinecraftServer server, String phase, Logger logger) {
            this.server = server; this.level = server.overworld(); this.phase = phase;
            this.logger = logger; this.started = server.getTickCount();
        }

        private void tick() {
            int elapsed = server.getTickCount() - started;
            if (elapsed > TIMEOUT_TICKS) throw new IllegalStateException("IPO-03 restart timed out");
            if (elapsed < 20) return;
            if (phase.equals("write")) write(elapsed); else read(elapsed);
        }

        private void write(int elapsed) {
            if (!prepared) {
                prepared = true;
                CompositePlayerOrderReloadProbe.markDisposableWorld(level, "ipo03-resource-restart");
                origin = site(); forceChunks(origin); clearArena(origin);
                setupPhysicalWorld();
                return;
            }
            var machine = ImmersiveEngineeringV1020MetalPressProduction.inspect(level, origin)
                    .orElseThrow(() -> new IllegalStateException("physical IE machine disappeared"));
            int pressStored = machine.state().getEnergy().getEnergyStored();
            if (pressStored < ENERGY_FE + 512) return;
            if (!generationStopped) {
                var stopped = ImmersiveEngineeringV1020MetalPressProduction
                        .stopThermalGeneration(level, origin);
                if (!stopped.success()) throw new IllegalStateException(stopped.code());
                generationStopped = true;
                lastEnergy = pressStored;
                return;
            }
            stableEnergyTicks = pressStored == lastEnergy ? stableEnergyTicks + 1 : 0;
            lastEnergy = pressStored;
            if (stableEnergyTicks < 10) return;
            int stored = energyStorage(capacitorPos()).getEnergyStored();

            GlobalInventoryGraph warehouse = WarehouseTopology.capture(level, warehousePos(), 2,
                    WAREHOUSE, OWNER, worldIdentity(), 0);
            if (warehouse.endpoints().size() != 1) throw new IllegalStateException("warehouse binding is not exact");
            ResourceId endpoint = warehouse.endpoints().keySet().iterator().next();
            WarehouseReservationRequest request = new WarehouseReservationRequest(REQUEST, PROJECT,
                    OWNER, IRON, MATERIAL_COUNT, List.of(endpoint), Long.MAX_VALUE, 0);
            EntityLogisticsBindingV1 logistics = observeBots(null);
            ElectricalNetworkGraph electrical = observeElectrical(stored);
            FluidNetworkGraph fluid = observeFluid();
            FluidIdentity water = ForgeFluidTransactionExecutor.identity(new FluidStack(Fluids.WATER, 1));
            IndustrialResourceBindingV1 binding = new IndustrialResourceBindingV1(PROJECT, OWNER,
                    worldIdentity(), dimension(), warehouse, List.of(request), logistics,
                    Optional.of(electrical), Optional.of(fluid), Map.of(FE, (long) ENERGY_FE),
                    Map.of(water, (long) FLUID_MB));

            WarehouseReservationSystem reservations = new WarehouseReservationSystem(warehouse);
            WarehouseAllocation allocation = reservations.reserveAtomically(List.of(request), 100)
                    .allocations().get(0);
            withdrawIron();
            reservations.advance(allocation.allocationId(), WarehouseReservationStatus.WITHDRAWN, 101);

            ProjectEnergyLedger energy = new ProjectEnergyLedger();
            energy.prepare(ENERGY_TX, PROJECT, id("steve_industrial:task/ipo03_power"),
                    ENERGY_SOURCE, FE, ENERGY_FE, level.getGameTime());
            int extracted = extractEnergy(capacitorPos(), ENERGY_FE);
            if (extracted != ENERGY_FE) throw new IllegalStateException("real FE extraction was " + extracted);
            energy.settle(ENERGY_TX, level.getGameTime());

            ProjectFluidLedger fluidLedger = new ProjectFluidLedger();
            var moved = ForgeFluidTransactionExecutor.transfer(level, fluidSource(), Direction.UP,
                    fluidDestination(), Direction.UP, fluidLedger, FLUID_TX, PROJECT,
                    id("steve_industrial:task/ipo03_fluid"), FLUID_SOURCE,
                    new FluidStack(Fluids.WATER, FLUID_MB), level.getGameTime());
            if (!moved.success() || moved.deliveredMb() != FLUID_MB) {
                throw new IllegalStateException("real fluid transfer failed: " + moved.code());
            }

            IndustrialResourceRuntimeV1 runtime = new IndustrialResourceRuntimeV1(binding,
                    reservations, energy, fluidLedger, Optional.empty(), level.getGameTime());
            IndustrialResourceCheckpointSavedData.forLevel(level).put(binding,
                    runtime.checkpoint(level.getGameTime()));
            level.getServer().overworld().getDataStorage().save();
            logger.info("IPO03_RESOURCE_RESTART_WRITE PASS warehouseWithdrawn={} bots={} "
                            + "feExtracted={} fluidDelivered={} oldRuntimeWillBeDestroyed=true ticks={}",
                    MATERIAL_COUNT, logistics.workerIds().size(), extracted, moved.deliveredMb(), elapsed);
            active = null; server.halt(false);
        }

        private void read(int elapsed) {
            var data = IndustrialResourceCheckpointSavedData.forLevel(level);
            IndustrialResourceBindingV1 binding = data.binding(PROJECT).orElseThrow(
                    () -> new IllegalStateException("persisted resource binding missing"));
            var checkpoint = data.checkpoint(PROJECT).orElseThrow(
                    () -> new IllegalStateException("persisted resource checkpoint missing"));
            origin = block(binding.electricalNetwork().orElseThrow().nodes().get(ENERGY_CONSUMER).position())
                    .offset(-1, -2, 0);
            forceChunks(origin);
            GlobalInventoryGraph warehouse = WarehouseTopology.recaptureBound(level,
                    binding.warehouse(), 1);
            EntityLogisticsBindingV1 logistics = observeBots(binding.entityLogistics());
            ImmersiveEngineeringV1020MetalPressProduction.inspect(level, origin)
                    .orElseThrow(() -> new IllegalStateException("IE machine did not reload"));
            int stored = energyStorage(capacitorPos()).getEnergyStored();
            ElectricalNetworkGraph electrical = observeElectrical(stored);
            FluidNetworkGraph fluid = observeFluid();
            long expectedInitialFe = binding.electricalNetwork().orElseThrow().nodes()
                    .get(ENERGY_SOURCE).storedEnergy();
            logger.info("IPO03_RESOURCE_RESTART_RECONCILE initialFe={} journalDeltaFe={} "
                            + "observedFe={} sourceFluid={} destinationFluid={}",
                    expectedInitialFe, checkpoint.energy().transactions().get(ENERGY_TX).amountFe(),
                    stored, total(fluidHandler(fluidSource())), total(fluidHandler(fluidDestination())));
            var restored = IndustrialResourceRuntimeV1.restore(binding, checkpoint, warehouse,
                    logistics, electrical, fluid, level.getGameTime());
            if (!restored.restored()) throw new IllegalStateException(restored.code());
            IndustrialResourceRuntimeV1 runtime = restored.runtime().orElseThrow();
            WarehouseAllocation allocation = runtime.warehouse().allocations().values().iterator().next();
            runtime.warehouse().advance(allocation.allocationId(), WarehouseReservationStatus.DELIVERED, 201);
            runtime.warehouse().advance(allocation.allocationId(), WarehouseReservationStatus.CONSUMED, 202);
            IFluidHandler destination = fluidHandler(fluidDestination());
            FluidStack drained = destination.drain(new FluidStack(Fluids.WATER, FLUID_MB),
                    IFluidHandler.FluidAction.EXECUTE);
            if (drained.getAmount() != FLUID_MB) throw new IllegalStateException("delivered fluid was not present");
            runtime.fluid().advance(FLUID_TX, FluidTransactionState.CONSUMED, level.getGameTime());
            EntityLogisticsSettlementV1 settlement = new EntityLogisticsSettlementV1(
                    logistics.sessionId(), logistics.taskGraphFingerprint(), logistics.assignmentIds(),
                    0, 0, 0);
            var result = IndustrialResourceRecoveryV1.settle(binding, runtime.warehouse(),
                    settlement, runtime.energy(), runtime.fluid());
            if (!result.balanced()) throw new IllegalStateException(result.code());
            cleanup(binding);
            String baseline = sha256("ipo03-resource-fixture-air-baseline-v1");
            var report = result.completionReport(Map.of(IRON.resourceId(), (long) MATERIAL_COUNT),
                    Map.of(IRON.resourceId(), (long) MATERIAL_COUNT),
                    Map.of(IRON.resourceId(), (long) MATERIAL_COUNT), Map.of(), Map.of(),
                    0, 0, baselineRestored(binding), baseline);
            if (!report.materialLedgerBalanced() || !report.baselineRestored()) {
                throw new IllegalStateException("common report did not accept physical cleanup");
            }
            data.remove(PROJECT); level.getServer().overworld().getDataStorage().save();
            logger.info("IPO03_RESOURCE_RESTART_READ PASS oldRuntimeDestroyed=true "
                            + "bindingReloaded=true liveEndpointsReregistered=true warehouse={} bots={} "
                            + "fe={} fluid={} duplicateWithdrawals={} duplicateReturns={} "
                            + "duplicateEnergy={} duplicateFluidWithdrawals={} duplicateFluidReturns={} "
                            + "unaccounted={} privateItemsTouched={} materialLedgerBalanced={} "
                            + "baselineRestored={} report=true ticks={}", MATERIAL_COUNT,
                    logistics.workerIds().size(), result.settledEnergy().get(FE), FLUID_MB,
                    result.duplicateWarehouseWithdrawals(), result.duplicateWarehouseReturns(),
                    result.duplicateEnergySettlements(), result.duplicateFluidWithdrawals(),
                    result.duplicateFluidReturns(), result.unaccountedWarehouseResources()
                            + result.unaccountedCarriedItems(), result.privateItemsTouched(),
                    result.balanced(), report.baselineRestored(), elapsed);
            active = null; server.halt(false);
        }

        private void setupPhysicalWorld() {
            level.setBlockAndUpdate(warehousePos(), Blocks.CHEST.defaultBlockState());
            Container chest = (Container) level.getBlockEntity(warehousePos());
            chest.setItem(0, stack("minecraft:iron_ingot", MATERIAL_COUNT)); chest.setChanged();
            spawnBot(botOne(), ConstructionBotEntity.Role.LOGISTICS, warehousePos().above());
            spawnBot(botTwo(), ConstructionBotEntity.Role.BUILDER_INSPECTOR, warehousePos().above().east());
            var placed = ImmersiveEngineeringV1020MetalPressProduction.placeStructure(level, origin);
            if (!placed.success()) throw new IllegalStateException(placed.code());
            if (!ImmersiveEngineeringV1020MetalPressProduction.form(level, origin,
                    stack("immersiveengineering:hammer", 1)).success()) throw new IllegalStateException("IE form failed");
            var power = ImmersiveEngineeringV1020MetalPressProduction.buildPowerNetwork(level, origin,
                    stack("immersiveengineering:wirecoil_copper", 1));
            if (!power.success() || power.externalConnections() != 1) throw new IllegalStateException(power.code());
            level.setBlockAndUpdate(capacitorPos(), blockItem("immersiveengineering:capacitor_lv")
                    .defaultBlockState());
            if (!(level.getBlockEntity(capacitorPos()) instanceof CapacitorBlockEntity capacitor)) {
                throw new IllegalStateException("LV capacitor block entity missing");
            }
            // Match the interaction contract of the Engineer's Hammer: configure one
            // face as output before using its exposed Forge capability. The fixture
            // does not write the internal energy field.
            for (int attempts = 0; attempts < 3
                    && capacitor.getSideConfig(Direction.EAST) != IEEnums.IOSideConfig.OUTPUT;
                    attempts++) {
                capacitor.toggleSide(Direction.EAST, FakePlayerFactory.getMinecraft(level));
            }
            if (capacitor.getSideConfig(Direction.EAST) != IEEnums.IOSideConfig.OUTPUT) {
                throw new IllegalStateException("LV capacitor output face could not be configured");
            }
            int acceptedEnergy = receiveEnergy(capacitorPos(), 5_000);
            if (acceptedEnergy != 5_000) {
                throw new IllegalStateException("LV capacitor accepted " + acceptedEnergy + " FE");
            }
            placeTank(fluidSource()); placeTank(fluidDestination());
            Block pipe = blockItem("create:fluid_pipe");
            for (int x = fluidSource().getX() + 1; x < fluidDestination().getX(); x++) {
                level.setBlockAndUpdate(new BlockPos(x, fluidSource().getY(), fluidSource().getZ()),
                        pipe.defaultBlockState());
            }
            if (!FluidPipeTopology.connected(level, fluidSource(), fluidDestination(), 32)) {
                throw new IllegalStateException("real Create pipe route is disconnected");
            }
            if (fluidHandler(fluidSource()).fill(new FluidStack(Fluids.WATER, 1_000),
                    IFluidHandler.FluidAction.EXECUTE) != 1_000) throw new IllegalStateException("tank seed failed");
        }

        private EntityLogisticsBindingV1 observeBots(EntityLogisticsBindingV1 expected) {
            List<ResourceId> workers = new ArrayList<>();
            for (UUID uuid : List.of(botOne(), botTwo())) {
                Entity entity = level.getEntity(uuid);
                if (!(entity instanceof ConstructionBotEntity bot) || !bot.isAlive()) {
                    throw new IllegalStateException("bound Bot entity missing: " + uuid);
                }
                workers.add(botId(uuid));
            }
            if (expected == null) return new EntityLogisticsBindingV1(
                    id("steve_industrial:fleet/ipo03_restart"),
                    id("steve_industrial:task_graph/ipo03_restart"), sha256("ipo03-tasks-v1"),
                    workers, List.of(id("steve_industrial:assignment/carry_iron"),
                            id("steve_industrial:assignment/inspect_resources")), 1,
                    level.getGameTime());
            return new EntityLogisticsBindingV1(expected.sessionId(), expected.taskGraphId(),
                    expected.taskGraphFingerprint(), workers, expected.assignmentIds(),
                    expected.generation(), level.getGameTime());
        }

        private ElectricalNetworkGraph observeElectrical(int stored) {
            var machine = ImmersiveEngineeringV1020MetalPressProduction.inspect(level, origin).orElseThrow();
            var layout = ImmersiveEngineeringV1020MetalPressProduction.layout(machine);
            long connections = GlobalWireNetwork.getNetwork(level).getLocalNet(layout.machineConnector())
                    .getConnections(layout.machineConnector()).stream().filter(value -> !value.isInternal()).count();
            if (connections != 1 || !level.getBlockState(layout.generator()).is(
                    blockItem("immersiveengineering:thermoelectric_generator"))) {
                throw new IllegalStateException("live IE FE topology was not observed");
            }
            ElectricalNetworkNode generator = new ElectricalNetworkNode(
                    id("steve_industrial:energy/generator"), ElectricalNodeKind.GENERATOR,
                    cell(layout.generator()), VoltageTier.LV, Optional.empty(), Set.of(),
                    EnumSet.allOf(Direction6.class), 1, 0, 0, 0,
                    sha256(level.getBlockState(layout.generator()).toString()), true);
            ElectricalNetworkNode consumer = new ElectricalNetworkNode(ENERGY_CONSUMER,
                    ElectricalNodeKind.CONSUMER, cell(machine.energyPosition()), VoltageTier.LV,
                    Optional.empty(), EnumSet.allOf(Direction6.class), Set.of(),
                    0, 1, 0, 0, sha256("press-consumer"), true);
            ElectricalNetworkNode storage = new ElectricalNetworkNode(ENERGY_SOURCE,
                    ElectricalNodeKind.STORAGE, cell(capacitorPos()), VoltageTier.LV,
                    Optional.empty(), EnumSet.allOf(Direction6.class), EnumSet.allOf(Direction6.class),
                    0, 0, stored, energyStorage(capacitorPos()).getMaxEnergyStored(),
                    sha256("lv-capacitor|" + stored), true);
            ElectricalWireEdge edge = new ElectricalWireEdge(id("steve_industrial:energy/wire"),
                    generator.nodeId(), consumer.nodeId(), VoltageTier.LV, 6, 16, 256,
                    List.of(generator.position(), consumer.position()),
                    sha256(layout.generatorConnector() + "|" + layout.machineConnector()),
                    true, true, true);
            return new ElectricalNetworkGraph(id("steve_industrial:energy/ipo03_graph"),
                    worldIdentity(), dimension(), List.of(generator, consumer, storage), List.of(edge), 0);
        }

        private FluidNetworkGraph observeFluid() {
            if (!FluidPipeTopology.connected(level, fluidSource(), fluidDestination(), 32)) {
                throw new IllegalStateException("live Create fluid topology was not observed");
            }
            FluidIdentity water = ForgeFluidTransactionExecutor.identity(new FluidStack(Fluids.WATER, 1));
            IFluidHandler source = fluidHandler(fluidSource()); IFluidHandler destination = fluidHandler(fluidDestination());
            long sourceAmount = total(source), destinationAmount = total(destination);
            FluidNetworkNode from = new FluidNetworkNode(FLUID_SOURCE, FluidNodeKind.TANK,
                    cell(fluidSource()), sourceAmount == 0 ? Optional.empty() : Optional.of(water),
                    sourceAmount, capacity(source), Set.of(Direction6.WEST), Set.of(Direction6.EAST),
                    0, sha256("source|" + sourceAmount), true);
            FluidNetworkNode to = new FluidNetworkNode(id("steve_industrial:fluid/destination_tank"),
                    FluidNodeKind.MACHINE_INPUT, cell(fluidDestination()),
                    destinationAmount == 0 ? Optional.empty() : Optional.of(water), destinationAmount,
                    capacity(destination), Set.of(Direction6.WEST), Set.of(), 0,
                    sha256("destination|" + destinationAmount), true);
            List<BlockPos3i> path = new ArrayList<>();
            for (int x = fluidSource().getX(); x <= fluidDestination().getX(); x++) {
                path.add(cell(new BlockPos(x, fluidSource().getY(), fluidSource().getZ())));
            }
            FluidRoute route = new FluidRoute(id("steve_industrial:fluid/pipe_route"), from.nodeId(),
                    to.nodeId(), water, path, FLUID_MB, capacity(destination), false, true,
                    true, true, sha256(path.toString()), true);
            return new FluidNetworkGraph(id("steve_industrial:fluid/ipo03_graph"), worldIdentity(),
                    dimension(), List.of(from, to), List.of(route), 0);
        }

        private void cleanup(IndustrialResourceBindingV1 binding) {
            for (ResourceId worker : binding.entityLogistics().workerIds()) {
                UUID uuid = UUID.fromString(worker.path().substring("bot/".length()));
                Entity entity = level.getEntity(uuid); if (entity != null) entity.discard();
            }
            ImmersiveEngineeringV1020MultiblockFormation.clear(level, IEMultiblocks.METAL_PRESS, origin);
            ImmersiveEngineeringV1020MetalPressProduction.baselinePositions(level, origin)
                    .forEach(position -> level.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState()));
            level.setBlockAndUpdate(capacitorPos(), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(warehousePos(), Blocks.AIR.defaultBlockState());
            for (int x = fluidSource().getX(); x <= fluidDestination().getX(); x++) {
                level.setBlockAndUpdate(new BlockPos(x, fluidSource().getY(), fluidSource().getZ()),
                        Blocks.AIR.defaultBlockState());
            }
        }

        private boolean baselineRestored(IndustrialResourceBindingV1 binding) {
            boolean blocks = ImmersiveEngineeringV1020MetalPressProduction.baselinePositions(level, origin)
                    .stream().allMatch(position -> level.getBlockState(position).isAir())
                    && level.getBlockState(warehousePos()).isAir()
                    && level.getBlockState(capacitorPos()).isAir();
            for (int x = fluidSource().getX(); x <= fluidDestination().getX(); x++) {
                blocks &= level.getBlockState(new BlockPos(x, fluidSource().getY(), fluidSource().getZ())).isAir();
            }
            return blocks && binding.entityLogistics().workerIds().stream().allMatch(worker ->
                    level.getEntity(UUID.fromString(worker.path().substring("bot/".length()))) == null);
        }

        private void withdrawIron() {
            Container chest = (Container) level.getBlockEntity(warehousePos());
            ItemStack stack = chest.getItem(0);
            if (!stack.is(ForgeRegistries.ITEMS.getValue(ResourceLocation.parse("minecraft:iron_ingot")))
                    || stack.getCount() != MATERIAL_COUNT) throw new IllegalStateException("warehouse stock changed");
            chest.setItem(0, ItemStack.EMPTY); chest.setChanged();
        }

        private void spawnBot(UUID uuid, ConstructionBotEntity.Role role, BlockPos position) {
            ConstructionBotEntity bot = ConstructionBotEntities.CONSTRUCTION_BOT.get().create(level);
            if (bot == null) throw new IllegalStateException("Bot type unavailable");
            bot.setUUID(uuid); bot.setRole(role); bot.setPos(position.getX() + .5, position.getY(), position.getZ() + .5);
            if (!level.addFreshEntity(bot)) throw new IllegalStateException("Bot spawn failed");
        }

        private BlockPos site() { BlockPos spawn = level.getSharedSpawnPos(); int x = spawn.getX() + 208;
            int z = spawn.getZ() + 208; int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 8;
            return new BlockPos(x, y, z); }
        private BlockPos warehousePos() { return origin.offset(-8, 0, 0); }
        private BlockPos fluidSource() { return origin.offset(10, 0, 0); }
        private BlockPos fluidDestination() { return origin.offset(14, 0, 0); }
        private BlockPos capacitorPos() { return origin.offset(-8, 0, 5); }
        private UUID botOne() { return UUID.fromString("aa1f0010-0000-4000-8000-000000000001"); }
        private UUID botTwo() { return UUID.fromString("aa1f0010-0000-4000-8000-000000000002"); }
        private String worldIdentity() { return "ipo03-resource-restart"; }
        private ResourceId dimension() { return id(level.dimension().location().toString()); }
        private void forceChunks(BlockPos centre) { for (int x = (centre.getX() - 32) >> 4; x <= (centre.getX() + 32) >> 4; x++)
            for (int z = (centre.getZ() - 32) >> 4; z <= (centre.getZ() + 32) >> 4; z++) level.setChunkForced(x, z, true); }
        private void clearArena(BlockPos centre) { for (int dx = -16; dx <= 20; dx++) for (int dz = -8; dz <= 8; dz++) {
            level.setBlockAndUpdate(centre.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
            for (int dy = 0; dy <= 9; dy++) level.setBlockAndUpdate(centre.offset(dx, dy, dz), Blocks.AIR.defaultBlockState()); } }
        private IFluidHandler fluidHandler(BlockPos position) { return level.getBlockEntity(position)
                .getCapability(ForgeCapabilities.FLUID_HANDLER, Direction.UP).resolve().orElseThrow(); }
        private IEnergyStorage energyStorage(BlockPos position) {
            var entity = level.getBlockEntity(position);
            if (entity == null) throw new IllegalStateException("energy endpoint missing at " + position);
            IEnergyStorage unsided = entity.getCapability(ForgeCapabilities.ENERGY).resolve()
                    .orElse(null);
            if (unsided != null) return unsided;
            for (Direction face : Direction.values()) {
                IEnergyStorage storage = entity.getCapability(ForgeCapabilities.ENERGY, face)
                        .resolve().orElse(null);
                if (storage != null) return storage;
            }
            throw new IllegalStateException("energy capability missing at " + position);
        }
        private IEnergyStorage energyReceive(BlockPos position) {
            var entity = level.getBlockEntity(position);
            IEnergyStorage unsided = entity.getCapability(ForgeCapabilities.ENERGY).resolve()
                    .orElse(null);
            if (unsided != null && unsided.canReceive()) return unsided;
            for (Direction face : Direction.values()) {
                IEnergyStorage storage = entity.getCapability(ForgeCapabilities.ENERGY, face)
                        .resolve().orElse(null);
                if (storage != null && storage.canReceive()) return storage;
            }
            throw new IllegalStateException("energy input face missing at " + position);
        }
        private IEnergyStorage energyExtract(BlockPos position) {
            var entity = level.getBlockEntity(position);
            IEnergyStorage unsided = entity.getCapability(ForgeCapabilities.ENERGY).resolve()
                    .orElse(null);
            if (unsided != null && unsided.canExtract()) return unsided;
            for (Direction face : Direction.values()) {
                IEnergyStorage storage = entity.getCapability(ForgeCapabilities.ENERGY, face)
                        .resolve().orElse(null);
                if (storage != null && storage.canExtract()) return storage;
            }
            throw new IllegalStateException("energy output face missing at " + position);
        }
        private int receiveEnergy(BlockPos position, int amount) {
            int moved = 0;
            while (moved < amount) {
                int portion = energyReceive(position).receiveEnergy(amount - moved, false);
                if (portion <= 0) break;
                moved += portion;
            }
            return moved;
        }
        private int extractEnergy(BlockPos position, int amount) {
            int moved = 0;
            while (moved < amount) {
                int portion = energyExtract(position).extractEnergy(amount - moved, false);
                if (portion <= 0) break;
                moved += portion;
            }
            return moved;
        }
        private long total(IFluidHandler handler) { long total = 0; for (int i = 0; i < handler.getTanks(); i++) total += handler.getFluidInTank(i).getAmount(); return total; }
        private long capacity(IFluidHandler handler) { long total = 0; for (int i = 0; i < handler.getTanks(); i++) total += handler.getTankCapacity(i); return total; }
        private void placeTank(BlockPos position) { level.setBlockAndUpdate(position, blockItem("create:fluid_tank").defaultBlockState()); }
    }

    private static ItemStack stack(String name, int count) { Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(name));
        if (item == null || item == net.minecraft.world.item.Items.AIR) throw new IllegalStateException("missing item " + name);
        return new ItemStack(item, count); }
    private static Block blockItem(String name) { Block block = ForgeRegistries.BLOCKS.getValue(ResourceLocation.parse(name));
        if (block == null || block == Blocks.AIR) throw new IllegalStateException("missing block " + name); return block; }
    private static ResourceId botId(UUID uuid) { return id("steve_industrial:bot/" + uuid); }
    private static BlockPos3i cell(BlockPos position) { return new BlockPos3i(position.getX(), position.getY(), position.getZ()); }
    private static BlockPos block(BlockPos3i position) { return new BlockPos(position.x(), position.y(), position.z()); }
    private static ResourceId id(String value) { return ResourceId.parse(value); }
    private static String sha256(String value) { try { return java.util.HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
}
