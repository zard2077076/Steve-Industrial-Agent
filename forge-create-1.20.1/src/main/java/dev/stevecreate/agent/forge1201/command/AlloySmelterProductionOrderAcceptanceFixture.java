package dev.stevecreate.agent.forge1201.command;

import com.mojang.authlib.GameProfile;
import dev.stevecreate.agent.core.execution.construction.MaterialExecutorKind;
import dev.stevecreate.agent.core.execution.construction.MaterialTransactionState;
import dev.stevecreate.agent.core.industrial.IndustrialLifecyclePhase;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.forge1201.acceptance.AcceptanceRuntimeGuard;
import dev.stevecreate.agent.forge1201.adapter.immersiveengineering.internal.v1020.ImmersiveEngineeringV1020AlloySmelterProduction;
import dev.stevecreate.agent.forge1201.industrial.AlloySmelterOrderSavedData;
import dev.stevecreate.agent.forge1201.industrial.IndustrialPlayerOrderSavedData;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.registries.ForgeRegistries;

/** Disposable end-to-end player-ledger gate for the reviewed fuel-powered IE order. */
public final class AlloySmelterProductionOrderAcceptanceFixture {
    private static final int TIMEOUT = 5_000;
    private final ServerLevel level;
    private final FakePlayer player;
    private final BlockPos origin;
    private final BlockPos source;
    private final UUID orderId;
    private final Map<ResourceId, Long> bill;
    private final Set<ResourceId> consumed;
    private final boolean cancellation;
    private final Map<BlockPos, BlockState> fixtureBaseline = new LinkedHashMap<>();
    private final Set<ChunkPos> fixtureChunkTickets = new LinkedHashSet<>();
    private int ticks;

    private AlloySmelterProductionOrderAcceptanceFixture(
            MinecraftServer server, boolean cancellation) {
        level = server.overworld();
        this.cancellation = cancellation;
        var recipe = ImmersiveEngineeringV1020AlloySmelterProduction
                .reviewedRecipe(level).orElseThrow();
        ResourceId first = id(recipe.firstInput());
        ResourceId second = id(recipe.secondInput());
        LinkedHashMap<ResourceId, Long> requirements = new LinkedHashMap<>();
        requirements.put(id("immersiveengineering:alloybrick"), 8L);
        requirements.put(id("immersiveengineering:hammer"), 1L);
        requirements.merge(first, (long) recipe.firstInput().getCount(), Math::addExact);
        requirements.merge(second, (long) recipe.secondInput().getCount(), Math::addExact);
        requirements.merge(id("minecraft:coal"), 1L, Math::addExact);
        bill = Map.copyOf(requirements);
        consumed = Set.of(first, second, id("minecraft:coal"));

        BlockPos spawn = level.getSharedSpawnPos();
        int invocation = AlloySmelterOrderSavedData.forLevel(level).orders().size();
        int x = spawn.getX() + 384 + Math.floorMod(invocation, 4) * 48;
        int z = spawn.getZ() + 96 + Math.floorDiv(invocation, 4) * 48;
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 10;
        origin = new BlockPos(x, y, z);
        source = origin.offset(-7, 0, 0);
        for (int chunkX = (origin.getX() - 12) >> 4;
                chunkX <= (origin.getX() + 10) >> 4; chunkX++) {
            for (int chunkZ = (origin.getZ() - 8) >> 4;
                    chunkZ <= (origin.getZ() + 10) >> 4; chunkZ++) {
                ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
                if (!level.getForcedChunks().contains(chunk.toLong())) {
                    level.setChunkForced(chunkX, chunkZ, true);
                    fixtureChunkTickets.add(chunk);
                }
            }
        }
        for (int dx = -12; dx <= 10; dx++) {
            for (int dz = -8; dz <= 10; dz++) {
                BlockPos floor = origin.offset(dx, -1, dz);
                for (int dy = 0; dy <= 5; dy++) capture(floor.above(dy));
                level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
                for (int dy = 1; dy <= 5; dy++) {
                    level.setBlockAndUpdate(floor.above(dy), Blocks.AIR.defaultBlockState());
                }
            }
        }
        level.setBlockAndUpdate(source, Blocks.CHEST.defaultBlockState());
        ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(source);
        int slot = 0;
        for (var row : bill.entrySet().stream().sorted(Map.Entry.comparingByKey(
                Comparator.comparing(ResourceId::toString))).toList()) {
            chest.setItem(slot++, stack(row.getKey(), Math.toIntExact(row.getValue())));
        }
        chest.setChanged();
        PilotWorldMarkerSavedData markers = PilotWorldMarkerSavedData.forLevel(level);
        if (markers.marker().isEmpty()) {
            markers.mark(new PilotWorldMarkerSavedData.Marker(PilotWorldMarkerSavedData.SCHEMA,
                    "world:ie-alloy-order-acceptance", UUID.randomUUID().toString(),
                    "overworld-pilot-only", "disposable-dedicated-server-fixture",
                    "fixture:ie-alloy-order", level.getGameTime()));
        }
        player = FakePlayerFactory.get(level, new GameProfile(
                UUID.randomUUID(), "SteveIEAlloyOrderFixture"));
        player.moveTo(source.getX() - 1.5D, source.getY(), source.getZ() + 0.5D, 0, 0);
        player.getAbilities().mayBuild = true;
        var started = AlloySmelterProductionService.create(player, source, origin);
        if (!started.success()) {
            throw new IllegalStateException("IE Alloy order fixture start failed: "
                    + started.code());
        }
        orderId = started.orderId();
    }

    public static AlloySmelterProductionOrderAcceptanceFixture start(MinecraftServer server) {
        AcceptanceRuntimeGuard.requireDevelopmentRuntime(
                "AlloySmelterProductionOrderAcceptanceFixture");
        return new AlloySmelterProductionOrderAcceptanceFixture(server, false);
    }

    public static AlloySmelterProductionOrderAcceptanceFixture startCancellation(
            MinecraftServer server) {
        AcceptanceRuntimeGuard.requireDevelopmentRuntime(
                "AlloySmelterProductionOrderCancellationAcceptanceFixture");
        return new AlloySmelterProductionOrderAcceptanceFixture(server, true);
    }

    public Result tick() {
        ticks++;
        if (ticks > TIMEOUT) {
            var order = IndustrialPlayerOrderSavedData.forLevel(level).order(orderId).orElse(null);
            return finish(false, "TIMEOUT:" + (order == null ? "MISSING" : order.stage()));
        }
        var order = IndustrialPlayerOrderSavedData.forLevel(level).order(orderId).orElse(null);
        if (order == null) return finish(false, "ORDER_DISAPPEARED");
        if (order.phase() == dev.stevecreate.agent.core.industrial.IndustrialLifecyclePhase.PAUSED) {
            return finish(false, order.stage());
        }
        if (cancellation) return tickCancellation(order);
        if (order.report().isEmpty()) return null;
        var report = order.report().orElseThrow();
        if (!report.accepted(Map.of(), Map.of(id("create:brass_ingot"), 2L))) {
            return finish(false, "COMMON_REPORT_NOT_ACCEPTED:" + report);
        }
        PlayerMaterialSavedData.Entry material = PlayerMaterialSavedData.forLevel(level)
                .entry(orderId).orElseThrow();
        if (material.report() == null || !material.report().balanced()) {
            return finish(false, "PLAYER_LEDGER_NOT_BALANCED");
        }
        long planned = bill.values().stream().mapToLong(Long::longValue).sum();
        long consumedCount = bill.entrySet().stream().filter(row -> consumed.contains(row.getKey()))
                .mapToLong(Map.Entry::getValue).sum();
        if (material.report().planned() != planned
                || material.report().withdrawn() != planned
                || material.report().consumed() != consumedCount
                || material.report().returned() != planned - consumedCount
                || material.report().duplicateWithdrawals() != 0
                || material.report().duplicateReturns() != 0
                || material.report().unaccountedItems() != 0
                || material.report().privateItemsTouched() != 0) {
            return finish(false, "MATERIAL_REPORT_COUNTER_MISMATCH:" + material.report());
        }
        if (!(level.getBlockEntity(source) instanceof ChestBlockEntity chest)) {
            return finish(false, "SOURCE_CHEST_MISSING");
        }
        for (var row : bill.entrySet()) {
            long expected = consumed.contains(row.getKey()) ? 0 : row.getValue();
            if (count(chest, row.getKey()) != expected) {
                return finish(false, "SOURCE_BALANCE_MISMATCH:" + row.getKey());
            }
        }
        long output = 0;
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof ItemEntity item && item.isAlive()
                    && item.distanceToSqr(source.getX() + 0.5D, source.getY() + 1.25D,
                            source.getZ() + 0.5D) < 16
                    && id(item.getItem()).equals(id("create:brass_ingot"))) {
                output += item.getItem().getCount();
            }
        }
        if (output != 2) return finish(false, "UNIQUE_OUTPUT_MISMATCH:" + output);
        if (material.transactions().stream().anyMatch(value ->
                value.executor() != MaterialExecutorKind.BOT)) {
            return finish(false, "NON_BOT_MATERIAL_TRANSACTION");
        }
        return finish(true, "OK");
    }

    private Result tickCancellation(
            dev.stevecreate.agent.core.industrial.IndustrialPlayerOrderV1 order) {
        PlayerMaterialSavedData.Entry material = PlayerMaterialSavedData.forLevel(level)
                .entry(orderId).orElseThrow();
        if (order.phase() != IndustrialLifecyclePhase.CANCELLED) {
            boolean carryingRealMaterial = material.transactions().stream().anyMatch(value ->
                    value.state() == MaterialTransactionState.WITHDRAWN);
            if (!carryingRealMaterial) return null;
            var cancelled = AlloySmelterProductionService.cancel(player);
            if (!cancelled.success()) return finish(false, "CANCEL_REFUSED:" + cancelled.code());
            order = IndustrialPlayerOrderSavedData.forLevel(level).order(orderId).orElseThrow();
            material = PlayerMaterialSavedData.forLevel(level).entry(orderId).orElseThrow();
        }
        if (order.phase() != IndustrialLifecyclePhase.CANCELLED
                || !order.stage().equals("CANCELLED_MATERIALS_RETURNED")) {
            return finish(false, "CANCEL_STAGE_MISMATCH:" + order.phase() + ":" + order.stage());
        }
        if (order.report().isPresent() || material.report() != null) {
            return finish(false, "CANCEL_FABRICATED_COMPLETION_REPORT");
        }
        if (material.transactions().stream().anyMatch(value ->
                value.state() != MaterialTransactionState.RETURNED
                        && value.state() != MaterialTransactionState.RELEASED)) {
            return finish(false, "CANCEL_UNSETTLED_TRANSACTION");
        }
        long returned = material.transactions().stream().filter(value ->
                value.state() == MaterialTransactionState.RETURNED).count();
        if (returned != 1) return finish(false, "CANCEL_RETURN_COUNT_MISMATCH:" + returned);
        if (!(level.getBlockEntity(source) instanceof ChestBlockEntity chest)) {
            return finish(false, "CANCEL_SOURCE_CHEST_MISSING");
        }
        for (var row : bill.entrySet()) {
            if (count(chest, row.getKey()) != row.getValue()) {
                return finish(false, "CANCEL_SOURCE_BALANCE_MISMATCH:" + row.getKey());
            }
        }
        if (ImmersiveEngineeringV1020AlloySmelterProduction.rawStructurePresent(level, origin)
                || ImmersiveEngineeringV1020AlloySmelterProduction.multiblockFormed(level, origin)) {
            return finish(false, "CANCEL_STRUCTURE_NOT_RESTORED");
        }
        if (nearbyOutput() != 0) return finish(false, "CANCEL_CREATED_OUTPUT");
        String projectHex = orderId.toString().replace("-", "");
        boolean botLeft = iterable(level.getAllEntities()).stream().anyMatch(entity ->
                entity instanceof dev.stevecreate.agent.forge1201.entity.ConstructionBotEntity
                        && entity.getTags().stream().anyMatch(tag -> tag.contains(projectHex)));
        if (botLeft) return finish(false, "CANCEL_BOT_NOT_DISCARDED");
        return finish(true, "CANCELLED_WITHDRAWN_MATERIAL_RETURNED");
    }

    private long nearbyOutput() {
        long output = 0;
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof ItemEntity item && item.isAlive()
                    && item.distanceToSqr(source.getX() + 0.5D, source.getY() + 1.25D,
                            source.getZ() + 0.5D) < 16
                    && id(item.getItem()).equals(id("create:brass_ingot"))) {
                output += item.getItem().getCount();
            }
        }
        return output;
    }

    private Result finish(boolean success, String code) {
        var order = IndustrialPlayerOrderSavedData.forLevel(level).order(orderId).orElse(null);
        var material = PlayerMaterialSavedData.forLevel(level).entry(orderId).orElse(null);
        List<String> journal = material == null ? List.of() : material.journal().stream()
                .map(value -> value.sequence() + ":" + value.state() + ":" + value.code())
                .toList();
        for (Entity entity : List.copyOf(iterable(level.getAllEntities()))) {
            if (entity instanceof ItemEntity item
                    && id(item.getItem()).equals(id("create:brass_ingot"))
                    && item.distanceToSqr(source.getX() + 0.5D, source.getY() + 1.25D,
                            source.getZ() + 0.5D) < 16) entity.discard();
        }
        fixtureBaseline.forEach(level::setBlockAndUpdate);
        fixtureChunkTickets.forEach(chunk -> level.setChunkForced(chunk.x, chunk.z, false));
        return new Result(success, code, ticks,
                order == null ? 0 : order.generation(), journal,
                material != null && material.report() != null && material.report().balanced());
    }

    private void capture(BlockPos position) {
        fixtureBaseline.putIfAbsent(position.immutable(), level.getBlockState(position));
    }

    private static long count(ChestBlockEntity chest, ResourceId item) {
        long result = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (!stack.isEmpty() && id(stack).equals(item)) result += stack.getCount();
        }
        return result;
    }

    private static ItemStack stack(ResourceId id, int count) {
        Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(id.toString()));
        if (item == null) throw new IllegalStateException("fixture item missing: " + id);
        ItemStack result = new ItemStack(item, count);
        result.setTag(null);
        return result;
    }

    private static ResourceId id(ItemStack stack) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (key == null) throw new IllegalStateException("unregistered fixture item");
        return id(key.toString());
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }

    private static <T> List<T> iterable(Iterable<T> values) {
        ArrayList<T> result = new ArrayList<>();
        values.forEach(result::add);
        return result;
    }

    public record Result(boolean success, String code, int ticks, long generation,
            List<String> materialJournal, boolean materialLedgerBalanced) {}
}
