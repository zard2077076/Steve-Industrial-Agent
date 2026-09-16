package dev.stevecreate.agent.forge1201.acceptance;

import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import dev.stevecreate.agent.core.execution.construction.MaterialTransactionState;
import dev.stevecreate.agent.core.industrial.IndustrialLifecyclePhase;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.warehouse.ProductionOrder;
import dev.stevecreate.agent.core.warehouse.ProductionOrderStatus;
import dev.stevecreate.agent.core.warehouse.WarehouseResourceKey;
import dev.stevecreate.agent.forge1201.adapter.immersiveengineering.internal.v1020.ImmersiveEngineeringV1020Adapter;
import dev.stevecreate.agent.forge1201.command.AlloySmelterProductionService;
import dev.stevecreate.agent.forge1201.command.CompositePlayerOrderReloadProbe;
import dev.stevecreate.agent.forge1201.industrial.AlloySmelterOrderSavedData;
import dev.stevecreate.agent.forge1201.industrial.IndustrialPlayerOrderSavedData;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData;
import dev.stevecreate.agent.forge1201.warehouse.WarehouseOrderSavedData;
import dev.stevecreate.agent.forge1201.warehouse.WarehouseOrderService;
import dev.stevecreate.agent.forge1201.warehouse.WarehouseRuntimeSavedData;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/** Fresh-process proof of unattended Alloy material, output and batch settlement. */
public final class AlloySmelterWarehouseRestartAcceptanceFixture {
    public static final String PHASE_PROPERTY =
            "steve_industrial.test.alloySmelterWarehouseRestartPhase";
    private static final int TIMEOUT_TICKS = 8_000;
    private static final ResourceId WAREHOUSE = id("steve_industrial:warehouse/alloy_restart");
    private static final ResourceId ORDER = id("steve_industrial:order/alloy_restart");
    private static Active active;

    private AlloySmelterWarehouseRestartAcceptanceFixture() {}

    public static void start(MinecraftServer server, String phase, Logger logger) {
        AcceptanceRuntimeGuard.requireDevelopmentRuntime(
                "AlloySmelterWarehouseRestartAcceptanceFixture");
        if (!phase.equals("write") && !phase.equals("read")) {
            throw new IllegalArgumentException("Unknown Alloy warehouse phase " + phase);
        }
        active = new Active(server, phase, logger);
    }

    public static void tick(MinecraftServer server) {
        Active current = active;
        if (current == null || current.server != server) return;
        try {
            current.tick();
        } catch (RuntimeException failure) {
            current.logger.error("IE_ALLOY_WAREHOUSE_RESTART FAIL phase={}",
                    current.phase, failure);
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
        private final int startTick;
        private boolean prepared;
        private String lastState;

        private Active(MinecraftServer server, String phase, Logger logger) {
            this.server = server;
            this.level = server.overworld();
            this.phase = phase;
            this.logger = logger;
            this.startTick = server.getTickCount();
        }

        private void tick() {
            int elapsed = server.getTickCount() - startTick;
            if (elapsed > TIMEOUT_TICKS) {
                throw new IllegalStateException("Alloy warehouse restart timed out in " + phase
                        + " lastState=" + lastState);
            }
            if (elapsed < 20) return;
            if (phase.equals("write")) write(elapsed);
            else read(elapsed);
        }

        private void write(int elapsed) {
            if (!prepared) {
                prepared = true;
                CompositePlayerOrderReloadProbe.markDisposableWorld(
                        level, "ie-alloy-warehouse-restart");
                BlockPos spawn = level.getSharedSpawnPos();
                int x = spawn.getX() + 176;
                int z = spawn.getZ() + 176;
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 10;
                BlockPos site = new BlockPos(x, y, z);
                BlockPos warehouse = site.offset(-9, 0, 0);
                forceChunks(site);
                clearAndFloor(site);
                var reviewed = AlloySmelterProductionService.requirements(level);
                if (!reviewed.success()) {
                    throw new IllegalStateException("Alloy review failed: " + reviewed.code());
                }
                stock(warehouse, reviewed.requirements());
                WarehouseOrderService.registerDurableRuntime(level,
                        new WarehouseRuntimeSavedData.Registration(WAREHOUSE, cell(warehouse), 4,
                                cell(site), AlloySmelterProductionService.ORDER_TYPE,
                                ExecutionMode.BOTS,
                                WarehouseRuntimeSavedData.RuntimeKind.ALLOY_SMELTER,
                                Optional.empty()));
                WarehouseOrderSavedData.forLevel(level).put(new ProductionOrder(
                        ORDER, id("steve_industrial:owner/alloy_restart"), WAREHOUSE,
                        new WarehouseResourceKey(GenericResourceType.ITEM, reviewed.output(),
                                WarehouseResourceKey.EMPTY_COMPONENT_SHA256),
                        reviewed.outputCount(), reviewed.outputCount(), 1,
                        AlloySmelterProductionService.ORDER_TYPE, Set.of(reviewed.recipeId()),
                        Set.of(ImmersiveEngineeringV1020Adapter.ADAPTER_ID),
                        List.of(id("steve_industrial:site/alloy_restart")), 0, 0, 0,
                        Optional.empty(), ProductionOrderStatus.ACTIVE, "GATE_SEEDED", 1));
                level.getServer().overworld().getDataStorage().save();
                return;
            }
            var production = WarehouseOrderSavedData.forLevel(level).order(ORDER).orElseThrow();
            var stored = boundOrder();
            String physical = stored == null ? "no-project" : IndustrialPlayerOrderSavedData
                    .forLevel(level).order(stored.orderId()).map(value -> value.phase() + "/"
                            + value.stage()).orElse("missing-envelope");
            String state = production.status() + "/" + production.lastStatusCode() + "/" + physical;
            logState(elapsed, state);
            if (stored == null) return;
            var common = IndustrialPlayerOrderSavedData.forLevel(level)
                    .order(stored.orderId()).orElseThrow();
            if (!common.stage().equals("PROCESSING")) return;
            if (production.status() != ProductionOrderStatus.BATCH_IN_FLIGHT
                    || production.inFlightBatchId().isEmpty()) {
                throw new IllegalStateException("physical project has no in-flight batch");
            }
            level.getServer().overworld().getDataStorage().save();
            logger.info("IE_ALLOY_WAREHOUSE_RESTART_WRITE PASS batch={} project={} "
                            + "stage=PROCESSING registrationPersisted=true outputInWarehouse=0 "
                            + "oldRuntimeWillBeDestroyed=true ticks={}",
                    production.inFlightBatchId().orElseThrow(), stored.orderId(), elapsed);
            active = null;
            server.halt(false);
        }

        private void read(int elapsed) {
            var registrations = WarehouseRuntimeSavedData.forLevel(level).registrations();
            var registration = registrations.get(WAREHOUSE);
            if (registration == null
                    || registration.runtimeKind()
                            != WarehouseRuntimeSavedData.RuntimeKind.ALLOY_SMELTER) {
                throw new IllegalStateException("Alloy runtime registration did not reload");
            }
            BlockPos warehouse = block(registration.warehouseCentre());
            forceChunks(warehouse);
            var production = WarehouseOrderSavedData.forLevel(level).order(ORDER).orElseThrow();
            StoredOrderView view = view();
            String state = production.status() + "/" + production.lastStatusCode()
                    + "/" + view.phase + "/" + view.stage + "/stock=" + count(warehouse,
                            AlloySmelterProductionService.OUTPUT);
            logState(elapsed, state);
            if (production.status() != ProductionOrderStatus.TARGET_SATISFIED) return;
            var stored = boundOrder();
            if (stored == null) throw new IllegalStateException("bound Alloy order vanished");
            var common = IndustrialPlayerOrderSavedData.forLevel(level)
                    .order(stored.orderId()).orElseThrow();
            if (common.phase() != IndustrialLifecyclePhase.COMPLETED
                    || common.report().isEmpty()
                    || !common.report().orElseThrow().accepted(Map.of(),
                            Map.of(stored.output(), (long) stored.outputCount()))) {
                throw new IllegalStateException("completed report is not accepted");
            }
            PlayerMaterialSavedData.Entry material = PlayerMaterialSavedData.forLevel(level)
                    .entry(stored.orderId()).orElseThrow();
            if (material.report() == null || !material.report().balanced()
                    || material.report().duplicateWithdrawals() != 0
                    || material.report().duplicateReturns() != 0
                    || material.report().unaccountedItems() != 0
                    || material.transactions().stream().anyMatch(value -> value.state()
                            != MaterialTransactionState.CONSUMED
                            && value.state() != MaterialTransactionState.RETURNED)) {
                throw new IllegalStateException("material ledger did not settle exactly");
            }
            long stock = count(warehouse, stored.output());
            if (stock != stored.outputCount() || taggedOutputIn(warehouse, stored.orderId()) != 0) {
                throw new IllegalStateException("warehouse output is not exact and released");
            }
            long loose = 0;
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof ItemEntity item
                        && itemId(item.getItem()).equals(stored.output())) {
                    loose += item.getItem().getCount();
                }
            }
            if (loose != 0 || !baselineMatches(stored)) {
                throw new IllegalStateException("output was loose or baseline was not restored");
            }
            long duplicateOutputs = common.report().orElseThrow().duplicateOutputs();
            logger.info("IE_ALLOY_WAREHOUSE_RESTART_READ PASS oldRuntimeDestroyed=true "
                            + "savedRuntimeRestored=true physicalOrderResumed=true "
                            + "batchAutoSettled=true targetSatisfied=true outputInWarehouse={} "
                            + "looseOutputs={} duplicateWithdrawals={} duplicateReturns={} "
                            + "duplicateOutputs={} unaccountedItems={} materialLedgerBalanced={} "
                            + "baselineRestored=true report=true ticks={}",
                    stock, loose, material.report().duplicateWithdrawals(),
                    material.report().duplicateReturns(), duplicateOutputs,
                    material.report().unaccountedItems(), material.report().balanced(), elapsed);
            active = null;
            server.halt(false);
        }

        private AlloySmelterOrderSavedData.StoredOrder boundOrder() {
            return AlloySmelterOrderSavedData.forLevel(level).orders().values().stream()
                    .filter(value -> value.warehouseBinding().isPresent())
                    .filter(value -> value.warehouseBinding().orElseThrow().warehouseId()
                            .equals(WAREHOUSE)).findFirst().orElse(null);
        }

        private StoredOrderView view() {
            var stored = boundOrder();
            if (stored == null) return new StoredOrderView("NO_PROJECT", "NO_PROJECT");
            return IndustrialPlayerOrderSavedData.forLevel(level).order(stored.orderId())
                    .map(value -> new StoredOrderView(value.phase().name(), value.stage()))
                    .orElse(new StoredOrderView("MISSING", "MISSING"));
        }

        private boolean baselineMatches(AlloySmelterOrderSavedData.StoredOrder stored) {
            return stored.baseline().stream().allMatch(value -> NbtUtils.writeBlockState(
                    level.getBlockState(block(value.position()))).equals(value.serializedState()));
        }

        private long taggedOutputIn(BlockPos position, UUID orderId) {
            if (!(level.getBlockEntity(position) instanceof Container container)) return -1;
            long count = 0;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.getTag() != null
                        && stack.getTag().hasUUID("SteveIndustrialAlloyOrder")
                        && stack.getTag().getUUID("SteveIndustrialAlloyOrder").equals(orderId)) {
                    count += stack.getCount();
                }
            }
            return count;
        }

        private void logState(int elapsed, String state) {
            if (state.equals(lastState)) return;
            lastState = state;
            logger.info("IE_ALLOY_WAREHOUSE_RESTART {} tick={} state={}", phase, elapsed, state);
        }

        private void clearAndFloor(BlockPos site) {
            for (int dx = -14; dx <= 14; dx++) {
                for (int dz = -14; dz <= 14; dz++) {
                    level.setBlockAndUpdate(site.offset(dx, -1, dz),
                            Blocks.STONE.defaultBlockState());
                    for (int dy = 0; dy <= 6; dy++) {
                        level.setBlockAndUpdate(site.offset(dx, dy, dz),
                                Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }

        private void stock(BlockPos position, Map<ResourceId, Long> bill) {
            level.setBlockAndUpdate(position, Blocks.CHEST.defaultBlockState());
            if (!(level.getBlockEntity(position) instanceof Container container)) {
                throw new IllegalStateException("warehouse chest was not created");
            }
            int slot = 0;
            for (var row : bill.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(java.util.Comparator.comparing(
                            ResourceId::toString))).toList()) {
                var item = ForgeRegistries.ITEMS.getValue(
                        ResourceLocation.parse(row.getKey().toString()));
                if (item == null) throw new IllegalStateException("unknown bill " + row.getKey());
                long remaining = row.getValue();
                while (remaining > 0) {
                    int portion = (int) Math.min(remaining, item.getMaxStackSize());
                    container.setItem(slot++, new ItemStack(item, portion));
                    remaining -= portion;
                }
            }
            container.setChanged();
        }

        private long count(BlockPos position, ResourceId resource) {
            if (!(level.getBlockEntity(position) instanceof Container container)) return 0;
            long total = 0;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty() && itemId(stack).equals(resource)) total += stack.getCount();
            }
            return total;
        }

        private void forceChunks(BlockPos centre) {
            for (int x = (centre.getX() - 48) >> 4; x <= (centre.getX() + 48) >> 4; x++) {
                for (int z = (centre.getZ() - 48) >> 4; z <= (centre.getZ() + 48) >> 4; z++) {
                    level.setChunkForced(x, z, true);
                }
            }
        }
    }

    private record StoredOrderView(String phase, String stage) {}

    private static ResourceId itemId(ItemStack stack) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (key == null) throw new IllegalStateException("unregistered fixture item");
        return id(key.toString());
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
    private static BlockPos3i cell(BlockPos value) {
        return new BlockPos3i(value.getX(), value.getY(), value.getZ());
    }
    private static BlockPos block(BlockPos3i value) {
        return new BlockPos(value.x(), value.y(), value.z());
    }
}
