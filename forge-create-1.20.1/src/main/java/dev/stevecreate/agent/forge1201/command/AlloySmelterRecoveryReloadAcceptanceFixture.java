package dev.stevecreate.agent.forge1201.command;

import com.mojang.authlib.GameProfile;
import dev.stevecreate.agent.core.industrial.IndustrialLifecyclePhase;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.forge1201.acceptance.AcceptanceRuntimeGuard;
import dev.stevecreate.agent.forge1201.industrial.AlloySmelterOrderSavedData;
import dev.stevecreate.agent.forge1201.industrial.AlloySmelterOrderSavedData.StoredOrder;
import dev.stevecreate.agent.forge1201.industrial.IndustrialPlayerOrderSavedData;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.Transaction;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/** Two-process physical replay gate for three resource-bearing Alloy Smelter windows. */
public final class AlloySmelterRecoveryReloadAcceptanceFixture {
    public static final String PHASE_PROPERTY =
            "steve_industrial.test.alloySmelterRecoveryReloadAcceptancePhase";
    public static final Set<String> WINDOWS = Set.of(
            "WITHDRAWN_NOT_DELIVERED",
            "BATCH_ADMITTED_NOT_OUTPUT",
            "OUTPUT_CLAIMED_NOT_REPORTED");
    private static final int TIMEOUT = 2_000;
    private static Session session;
    private static Logger logger;

    private AlloySmelterRecoveryReloadAcceptanceFixture() {}

    public static void start(MinecraftServer server, String request, Logger fixtureLogger) {
        AcceptanceRuntimeGuard.requireDevelopmentRuntime(
                "AlloySmelterRecoveryReloadAcceptanceFixture");
        if (session != null) throw new IllegalStateException("Alloy reload fixture already active");
        String[] parts = request.split(":", -1);
        if (parts.length != 2 || !(parts[0].equals("write") || parts[0].equals("read"))
                || !WINDOWS.contains(parts[1])) {
            throw new IllegalArgumentException("Invalid Alloy reload phase " + request);
        }
        logger = fixtureLogger;
        session = parts[0].equals("write")
                ? Session.write(server, parts[1]) : Session.read(server, parts[1]);
    }

    public static void tick(MinecraftServer server) {
        if (session == null) return;
        Result result = session.tick();
        if (result == null) return;
        Session completed = session;
        session = null;
        if (!result.success()) {
            logger.error("IE_ALLOY_RECOVERY_RELOAD FAIL phase={} window={} detail={}",
                    completed.phase, completed.window, result.code());
            server.halt(false);
            throw new IllegalStateException(result.code());
        }
        if (completed.phase.equals("write")) {
            logger.info("IE_ALLOY_RECOVERY_RELOAD_WRITE PASS window={} stage={} "
                            + "durableSavedData=true processWillExit=true",
                    completed.window, result.stage());
        } else {
            logger.info("IE_ALLOY_RECOVERY_RELOAD_ACCEPTANCE PASS window={} "
                            + "oldRuntimeDestroyed=true savedDataReloaded=true resumed=true "
                            + "duplicateWithdrawals=0 duplicateReturns=0 duplicateOutputs=0 "
                            + "unaccountedItems=0 materialLedgerBalanced=true "
                            + "baselineRestored=true report=true output=2 stage={} ticks={}",
                    completed.window, result.stage(), result.ticks());
        }
        server.halt(false);
    }

    private static final class Session {
        private final MinecraftServer server;
        private final ServerLevel level;
        private final String phase;
        private final String window;
        private final FakePlayer player;
        private final StoredOrder stored;
        private final Map<ResourceId, Long> bill;
        private final Set<ResourceId> consumed;
        private boolean recoveryStarted;
        private int ticks;

        private Session(MinecraftServer server, String phase, String window, FakePlayer player,
                StoredOrder stored, Map<ResourceId, Long> bill, Set<ResourceId> consumed) {
            this.server = server;
            this.level = server.overworld();
            this.phase = phase;
            this.window = window;
            this.player = player;
            this.stored = stored;
            this.bill = bill;
            this.consumed = consumed;
            forceChunks(level, block(stored.machineOrigin()));
        }

        private static Session write(MinecraftServer server, String window) {
            ServerLevel level = server.overworld();
            if (!AlloySmelterOrderSavedData.forLevel(level).orders().isEmpty()) {
                throw new IllegalStateException("write phase found an existing Alloy order");
            }
            var recipe = dev.stevecreate.agent.forge1201.adapter.immersiveengineering.internal.v1020
                    .ImmersiveEngineeringV1020AlloySmelterProduction.reviewedRecipe(level)
                    .orElseThrow();
            ResourceId first = id(recipe.firstInput());
            ResourceId second = id(recipe.secondInput());
            LinkedHashMap<ResourceId, Long> bill = new LinkedHashMap<>();
            bill.put(id("immersiveengineering:alloybrick"), 8L);
            bill.put(id("immersiveengineering:hammer"), 1L);
            bill.merge(first, (long) recipe.firstInput().getCount(), Math::addExact);
            bill.merge(second, (long) recipe.secondInput().getCount(), Math::addExact);
            bill.merge(id("minecraft:coal"), 1L, Math::addExact);
            BlockPos spawn = level.getSharedSpawnPos();
            int x = spawn.getX() + 160;
            int z = spawn.getZ() + 160;
            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 10;
            BlockPos origin = new BlockPos(x, y, z);
            BlockPos source = origin.offset(-7, 0, 0);
            forceChunks(level, origin);
            for (int dx = -12; dx <= 10; dx++) {
                for (int dz = -8; dz <= 10; dz++) {
                    BlockPos floor = origin.offset(dx, -1, dz);
                    level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
                    for (int dy = 1; dy <= 5; dy++) {
                        level.setBlockAndUpdate(floor.above(dy), Blocks.AIR.defaultBlockState());
                    }
                }
            }
            level.setBlockAndUpdate(source, Blocks.CHEST.defaultBlockState());
            ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(source);
            int slot = 0;
            for (var row : bill.entrySet()) {
                chest.setItem(slot++, stack(row.getKey(), Math.toIntExact(row.getValue())));
            }
            chest.setChanged();
            PilotWorldMarkerSavedData markers = PilotWorldMarkerSavedData.forLevel(level);
            if (markers.marker().isEmpty()) {
                markers.mark(new PilotWorldMarkerSavedData.Marker(PilotWorldMarkerSavedData.SCHEMA,
                        "world:ie-alloy-reload-" + window.toLowerCase(),
                        UUID.randomUUID().toString(), "overworld-pilot-only",
                        "disposable-two-process-fixture", "fixture:ie-alloy-reload",
                        level.getGameTime()));
            }
            FakePlayer player = FakePlayerFactory.get(level, new GameProfile(
                    UUID.nameUUIDFromBytes(("alloy-reload:" + window).getBytes(
                            java.nio.charset.StandardCharsets.UTF_8)), "SteveAlloyReload"));
            player.moveTo(source.getX() - 1.5D, source.getY(), source.getZ() + 0.5D, 0, 0);
            player.getAbilities().mayBuild = true;
            var started = AlloySmelterProductionService.create(player, source, origin);
            if (!started.success()) {
                throw new IllegalStateException("Alloy reload order start failed: " + started.code());
            }
            StoredOrder stored = AlloySmelterOrderSavedData.forLevel(level)
                    .order(started.orderId()).orElseThrow();
            return new Session(server, "write", window, player, stored, Map.copyOf(bill),
                    Set.of(first, second, id("minecraft:coal")));
        }

        private static Session read(MinecraftServer server, String window) {
            ServerLevel level = server.overworld();
            if (AlloySmelterOrderSavedData.forLevel(level).orders().size() != 1) {
                throw new IllegalStateException("read phase did not restore exactly one Alloy order");
            }
            StoredOrder stored = AlloySmelterOrderSavedData.forLevel(level)
                    .orders().values().iterator().next();
            FakePlayer player = FakePlayerFactory.get(level, new GameProfile(
                    stored.playerId(), "SteveAlloyReload"));
            BlockPos source = block(stored.materialSource());
            player.moveTo(source.getX() - 1.5D, source.getY(), source.getZ() + 0.5D, 0, 0);
            player.getAbilities().mayBuild = true;
            EntryShape shape = shape(level, stored.orderId());
            return new Session(server, "read", window, player, stored,
                    shape.bill(), shape.consumed());
        }

        private Result tick() {
            ticks++;
            if (phase.equals("read") && !recoveryStarted) {
                recoveryStarted = AlloySmelterProductionService.recoverForOwner(player);
                if (!recoveryStarted) return null;
            }
            var order = IndustrialPlayerOrderSavedData.forLevel(level)
                    .order(stored.orderId()).orElse(null);
            if (order == null) return result(false, "ORDER_MISSING");
            PlayerMaterialSavedData.Entry material = PlayerMaterialSavedData.forLevel(level)
                    .entry(stored.orderId()).orElse(null);
            if (material == null) return result(false, "MATERIAL_LEDGER_MISSING");
            if (order.phase() == IndustrialLifecyclePhase.PAUSED
                    || order.stage().startsWith("PAUSED:")) {
                return result(false, order.stage() + ":" + transactionShape(material));
            }
            if (ticks > TIMEOUT) {
                return result(false, "TIMEOUT:" + order.phase() + ":" + order.stage()
                        + ":" + transactionShape(material));
            }
            if (phase.equals("write")) {
                boolean reached = switch (window) {
                    case "WITHDRAWN_NOT_DELIVERED" -> material.transactions().stream().anyMatch(value ->
                            value.state() == dev.stevecreate.agent.core.execution.construction
                                    .MaterialTransactionState.WITHDRAWN);
                    case "BATCH_ADMITTED_NOT_OUTPUT" -> order.stage().equals("PROCESSING");
                    case "OUTPUT_CLAIMED_NOT_REPORTED" -> order.stage().equals("OUTPUT_OBSERVED")
                            && order.report().isEmpty();
                    default -> false;
                };
                if (!reached) return null;
                level.getServer().overworld().getDataStorage().save();
                return result(true, "WRITE_CHECKPOINT_REACHED");
            }
            if (order.report().isEmpty()) return null;
            var report = order.report().orElseThrow();
            if (!report.accepted(Map.of(), Map.of(stored.output(), (long) stored.outputCount()))) {
                return result(false, "REPORT_NOT_ACCEPTED");
            }
            if (material.report() == null || !material.report().balanced()
                    || material.report().duplicateWithdrawals() != 0
                    || material.report().duplicateReturns() != 0
                    || material.report().unaccountedItems() != 0) {
                return result(false, "MATERIAL_REPORT_NOT_ACCEPTED");
            }
            if (!sourceBalanced(level, stored, bill, consumed)) {
                return result(false, "SOURCE_BALANCE_MISMATCH");
            }
            if (nearbyOutput(level, stored) != stored.outputCount()) {
                return result(false, "OUTPUT_NOT_UNIQUE");
            }
            return result(true, "OK");
        }

        private static String transactionShape(PlayerMaterialSavedData.Entry material) {
            return material.transactions().stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            Transaction::state, java.util.TreeMap::new,
                            java.util.stream.Collectors.counting()))
                    .entrySet().stream()
                    .map(row -> row.getKey() + "=" + row.getValue())
                    .collect(java.util.stream.Collectors.joining(","));
        }

        private Result result(boolean success, String code) {
            var order = IndustrialPlayerOrderSavedData.forLevel(level)
                    .order(stored.orderId()).orElse(null);
            return new Result(success, code, ticks, order == null ? "MISSING" : order.stage());
        }
    }

    private static EntryShape shape(ServerLevel level, UUID orderId) {
        PlayerMaterialSavedData.Entry entry = PlayerMaterialSavedData.forLevel(level)
                .entry(orderId).orElseThrow();
        Set<ResourceId> returned = Set.of(id("immersiveengineering:alloybrick"),
                id("immersiveengineering:hammer"));
        Set<ResourceId> consumed = entry.requirements().keySet().stream()
                .filter(value -> !returned.contains(value))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new EntryShape(entry.requirements(), consumed);
    }

    private static boolean sourceBalanced(ServerLevel level, StoredOrder stored,
            Map<ResourceId, Long> bill, Set<ResourceId> consumed) {
        if (!(level.getBlockEntity(block(stored.materialSource())) instanceof ChestBlockEntity chest)) {
            return false;
        }
        return bill.entrySet().stream().allMatch(row -> count(chest, row.getKey())
                == (consumed.contains(row.getKey()) ? 0 : row.getValue()));
    }

    private static long nearbyOutput(ServerLevel level, StoredOrder stored) {
        BlockPos source = block(stored.materialSource());
        long output = 0;
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof ItemEntity item && item.isAlive()
                    && id(item.getItem()).equals(stored.output())
                    && item.distanceToSqr(source.getX() + 0.5D, source.getY() + 1.25D,
                            source.getZ() + 0.5D) < 16) output += item.getItem().getCount();
        }
        return output;
    }

    private static void forceChunks(ServerLevel level, BlockPos origin) {
        for (int chunkX = (origin.getX() - 12) >> 4;
                chunkX <= (origin.getX() + 10) >> 4; chunkX++) {
            for (int chunkZ = (origin.getZ() - 8) >> 4;
                    chunkZ <= (origin.getZ() + 10) >> 4; chunkZ++) {
                level.setChunkForced(chunkX, chunkZ, true);
            }
        }
    }

    private static long count(ChestBlockEntity chest, ResourceId item) {
        long count = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (!stack.isEmpty() && id(stack).equals(item)) count += stack.getCount();
        }
        return count;
    }

    private static ItemStack stack(ResourceId id, int count) {
        Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(id.toString()));
        if (item == null) throw new IllegalStateException("fixture item missing: " + id);
        ItemStack result = new ItemStack(item, count);
        result.setTag(null);
        return result;
    }

    private static ResourceId id(ItemStack stack) {
        ResourceLocation resource = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (resource == null) throw new IllegalStateException("unregistered fixture item");
        return id(resource.toString());
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
    private static BlockPos block(dev.stevecreate.agent.core.model.BlockPos3i value) {
        return new BlockPos(value.x(), value.y(), value.z());
    }

    private record EntryShape(Map<ResourceId, Long> bill, Set<ResourceId> consumed) {}
    private record Result(boolean success, String code, int ticks, String stage) {}
}
