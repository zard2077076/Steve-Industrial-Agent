package dev.stevecreate.agent.forge1201.acceptance;

import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.warehouse.ProductionOrder;
import dev.stevecreate.agent.core.warehouse.ProductionOrderStatus;
import dev.stevecreate.agent.core.warehouse.WarehouseResourceKey;
import dev.stevecreate.agent.forge1201.command.CompositePlayerOrderReloadProbe;
import dev.stevecreate.agent.forge1201.command.PlayerCompositeOrderService;
import dev.stevecreate.agent.forge1201.command.WarehouseDiscovery;
import dev.stevecreate.agent.forge1201.warehouse.WarehouseOrderSavedData;
import dev.stevecreate.agent.forge1201.warehouse.WarehouseOrderService;
import dev.stevecreate.agent.forge1201.warehouse.WarehouseRuntimeSavedData;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/**
 * Two-process proof that an unattended factory keeps working after a restart.
 *
 * <p>Production orders have always been durable and the runtime acting on them was not.
 * It lived in a static map cleared on shutdown, so a restarted server read its orders,
 * found no runtime and skipped every one of them forever — and because the orders were
 * still sitting in storage, nothing about that looked wrong. A factory that stops when
 * the server restarts is not unattended.
 *
 * <p>The write phase leaves a warehouse with a standing instruction and exits. The read
 * phase, in a fresh JVM, registers nothing: everything it does must come from what the
 * previous process wrote down. If the runtime is not rebuilt, the order is skipped and
 * the gate times out with the stock it started with — which is precisely the failure this
 * exists to catch.
 */
public final class WarehouseRestartAcceptanceFixture {
    public static final String PHASE_PROPERTY =
            "steve_industrial.test.warehouseRestartPhase";
    private static final int TIMEOUT_TICKS = 8_000;
    private static final ResourceId WAREHOUSE =
            ResourceId.parse("steve_industrial:warehouse/restart_gate");
    private static final ResourceId ORDER =
            ResourceId.parse("steve_industrial:order/restart_gate");
    private static final ResourceId GRAPH =
            ResourceId.parse("steve_industrial:composite/01");

    private static Active active;

    private WarehouseRestartAcceptanceFixture() {}

    public static void start(MinecraftServer server, String phase, Logger logger) {
        AcceptanceRuntimeGuard.requireDevelopmentRuntime("WarehouseRestartAcceptanceFixture");
        if (!"write".equals(phase) && !"read".equals(phase)) {
            throw new IllegalArgumentException("Unknown warehouse restart phase: " + phase);
        }
        active = new Active(server, logger, phase, server.getTickCount());
    }

    public static void tick(MinecraftServer server) {
        Active current = active;
        if (current == null || current.server != server) return;
        try {
            current.tick();
        } catch (RuntimeException failure) {
            current.logger.error("WAREHOUSE_RESTART_ACCEPTANCE FAIL phase={}",
                    current.phase, failure);
            active = null;
            server.halt(false);
            throw failure;
        }
    }

    private static final class Active {
        private final MinecraftServer server;
        private final Logger logger;
        private final String phase;
        private final int startTick;
        private final ServerLevel level;
        private String lastState;

        private Active(MinecraftServer server, Logger logger, String phase, int startTick) {
            this.server = server;
            this.logger = logger;
            this.phase = phase;
            this.startTick = startTick;
            this.level = server.overworld();
        }

        private void tick() {
            int elapsed = server.getTickCount() - startTick;
            if (elapsed > TIMEOUT_TICKS) {
                throw new IllegalStateException(
                        "warehouse restart gate exceeded its budget in phase " + phase);
            }
            if (elapsed < 20) return;
            if (phase.equals("write")) tickWrite();
            else tickRead(elapsed);
        }

        /** Leaves a warehouse with a standing instruction, and nothing else. */
        private void tickWrite() {
            CompositePlayerOrderReloadProbe.markDisposableWorld(level, "warehouse-restart");
            BlockPos spawn = level.getSharedSpawnPos();
            int x = spawn.getX() + 96;
            int z = spawn.getZ() + 96;
            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 4;
            BlockPos site = new BlockPos(x, y, z);
            forceChunks(site);
            for (int dx = -32; dx <= 32; dx++) {
                for (int dz = -32; dz <= 32; dz++) {
                    level.setBlockAndUpdate(new BlockPos(x + dx, y - 1, z + dz),
                            Blocks.STONE.defaultBlockState());
                    for (int dy = 0; dy <= 6; dy++) {
                        level.setBlockAndUpdate(new BlockPos(x + dx, y + dy, z + dz),
                                Blocks.AIR.defaultBlockState());
                    }
                }
            }
            var preview = PlayerCompositeOrderService.preview(level, GRAPH, site);
            if (!preview.success()) {
                throw new IllegalStateException("restart preview failed: " + preview.code());
            }
            BlockPos warehouse = new BlockPos(x, y, z - 8);
            stock(warehouse, preview.requirements());

            WarehouseOrderService.registerDurableRuntime(level,
                    new WarehouseRuntimeSavedData.Registration(WAREHOUSE, cell(warehouse), 4,
                            cell(site), GRAPH, ExecutionMode.DIRECT, Optional.empty()));
            WarehouseOrderSavedData.forLevel(level).put(new ProductionOrder(
                    ORDER, ResourceId.parse("steve_industrial:owner/restart_gate"), WAREHOUSE,
                    target(), 1, 1, 1, GRAPH, Set.of(GRAPH),
                    Set.of(ResourceId.parse("steve_industrial:adapter/create_v606")),
                    List.of(ResourceId.parse("steve_industrial:site/restart_gate")),
                    3, 0, 0, Optional.empty(), ProductionOrderStatus.ACTIVE, "GATE_SEEDED", 1));

            long stocked = stockOf(warehouse);
            if (WarehouseRuntimeSavedData.forLevel(level).registrations().isEmpty()) {
                throw new IllegalStateException("the registration was not persisted");
            }
            level.getServer().overworld().getDataStorage().save();
            logger.info("WAREHOUSE_RESTART_WRITE PASS warehouse={} site={} billStocked={} "
                            + "productInWarehouse=0 registrationPersisted=true",
                    warehouse.toShortString(), site.toShortString(), stocked);
            active = null;
            server.halt(false);
        }

        /**
         * A fresh process must resume production from what was written down.
         *
         * <p>Nothing here registers anything. If the restore did not happen the order is
         * skipped exactly as it was before this existed, and the gate ends on a timeout
         * with the warehouse holding no product.</p>
         */
        private void tickRead(int elapsed) {
            var registrations = WarehouseRuntimeSavedData.forLevel(level).registrations();
            if (!registrations.containsKey(WAREHOUSE)) {
                throw new IllegalStateException("the registration did not survive the restart");
            }
            var registration = registrations.get(WAREHOUSE);
            BlockPos warehouse = new BlockPos(registration.warehouseCentre().x(),
                    registration.warehouseCentre().y(), registration.warehouseCentre().z());
            forceChunks(warehouse);
            long produced = countIn(warehouse, ResourceId.parse("create:cogwheel"));
            var order = WarehouseOrderSavedData.forLevel(level).order(ORDER).orElseThrow(
                    () -> new IllegalStateException("the production order did not survive"));
            String state = order.status() + "/" + order.lastStatusCode() + " product=" + produced;
            if (!state.equals(lastState)) {
                lastState = state;
                logger.info("WAREHOUSE_RESTART read tick={} {}", elapsed, state);
            }
            if (produced < 1) return;
            logger.info("WAREHOUSE_RESTART_READ PASS restoredWithoutRegistering=true "
                            + "productInWarehouse={} orderStatus={} ticks={}",
                    produced, order.status(), elapsed);
            active = null;
            server.halt(false);
        }

        private WarehouseResourceKey target() {
            return new WarehouseResourceKey(GenericResourceType.ITEM,
                    ResourceId.parse("create:cogwheel"),
                    WarehouseResourceKey.EMPTY_COMPONENT_SHA256);
        }

        private long countIn(BlockPos position, ResourceId resource) {
            return WarehouseDiscovery.candidates(level, position, 4, List.of()).stream()
                    .mapToLong(candidate -> candidate.contents().getOrDefault(resource, 0L))
                    .sum();
        }

        private long stockOf(BlockPos position) {
            if (!(level.getBlockEntity(position) instanceof Container container)) return 0;
            long total = 0;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                total += container.getItem(slot).getCount();
            }
            return total;
        }

        private void stock(BlockPos position, java.util.Map<ResourceId, Long> bill) {
            level.setBlockAndUpdate(position, Blocks.CHEST.defaultBlockState());
            if (!(level.getBlockEntity(position) instanceof Container container)) {
                throw new IllegalStateException("could not create the warehouse chest");
            }
            container.clearContent();
            int slot = 0;
            for (var required : bill.entrySet()) {
                var item = ForgeRegistries.ITEMS.getValue(
                        net.minecraft.resources.ResourceLocation.tryParse(
                                required.getKey().toString()));
                if (item == null) throw new IllegalStateException("unknown bill item");
                long remaining = required.getValue();
                while (remaining > 0) {
                    int portion = (int) Math.min(remaining, item.getMaxStackSize());
                    if (slot >= container.getContainerSize()) {
                        throw new IllegalStateException("the bill does not fit one chest");
                    }
                    container.setItem(slot++, new ItemStack(item, portion));
                    remaining -= portion;
                }
            }
            container.setChanged();
        }

        private void forceChunks(BlockPos centre) {
            for (int x = (centre.getX() - 48) >> 4; x <= (centre.getX() + 48) >> 4; x++) {
                for (int z = (centre.getZ() - 48) >> 4; z <= (centre.getZ() + 48) >> 4; z++) {
                    level.setChunkForced(x, z, true);
                }
            }
        }

        private static BlockPos3i cell(BlockPos position) {
            return new BlockPos3i(position.getX(), position.getY(), position.getZ());
        }
    }
}
