package dev.stevecreate.agent.forge1201.command;

import dev.stevecreate.agent.forge1201.acceptance.AcceptanceRuntimeGuard;

import com.mojang.authlib.GameProfile;
import dev.stevecreate.agent.core.execution.construction.MaterialTransactionState;
import dev.stevecreate.agent.core.industrial.MetalPressOrderStage;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.forge1201.entity.ConstructionBotEntity;
import dev.stevecreate.agent.forge1201.industrial.MetalPressOrderSavedData;
import dev.stevecreate.agent.forge1201.industrial.IndustrialPlayerOrderSavedData;
import dev.stevecreate.agent.forge1201.industrial.IndustrialResourceCheckpointSavedData;
import dev.stevecreate.agent.core.electrical.ProjectEnergyLedger;
import dev.stevecreate.agent.core.fluid.ProjectFluidLedger;
import dev.stevecreate.agent.core.warehouse.WarehouseReservationSystem;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.registries.ForgeRegistries;

/** Disposable full player-ledger/Bot/IE order gate, invoked after the low-level FE gate. */
public final class MetalPressProductionOrderAcceptanceFixture {
    private static final int TIMEOUT = 8_000;
    private static final Map<ResourceId, Long> BOM = Map.ofEntries(
            Map.entry(id("immersiveengineering:steel_scaffolding_standard"), 2L),
            Map.entry(id("immersiveengineering:heavy_engineering"), 1L),
            Map.entry(id("immersiveengineering:rs_engineering"), 1L),
            Map.entry(id("immersiveengineering:conveyor_basic"), 2L),
            Map.entry(id("minecraft:piston"), 1L),
            Map.entry(id("immersiveengineering:hammer"), 1L),
            Map.entry(id("immersiveengineering:mold_plate"), 1L),
            Map.entry(id("minecraft:iron_ingot"), 1L),
            Map.entry(id("immersiveengineering:thermoelectric_generator"), 1L),
            Map.entry(id("immersiveengineering:connector_lv"), 2L),
            Map.entry(id("immersiveengineering:wirecoil_copper"), 1L),
            Map.entry(id("minecraft:blue_ice"), 1L),
            Map.entry(id("minecraft:magma_block"), 1L));
    private static final java.util.Set<ResourceId> CONSUMED = java.util.Set.of(
            id("minecraft:iron_ingot"), id("immersiveengineering:wirecoil_copper"));
    private final ServerLevel level;
    private final FakePlayer player;
    private final BlockPos origin;
    private final BlockPos source;
    private final UUID orderId;
    private final Map<BlockPos, BlockState> fixtureBaseline = new LinkedHashMap<>();
    private final java.util.Set<ChunkPos> fixtureChunkTickets = new java.util.LinkedHashSet<>();
    private int ticks;

    private MetalPressProductionOrderAcceptanceFixture(MinecraftServer server) {
        level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        // A failed/reload-oriented gate deliberately leaves its durable order behind. Give every
        // invocation an isolated source/site instead of deleting that recovery evidence or letting
        // an old reservation make the next invocation look material-deficient.
        int invocation = MetalPressOrderSavedData.forLevel(level).orders().size();
        int x = spawn.getX() + 48 + Math.floorMod(invocation, 8) * 48;
        int z = spawn.getZ() + 48 + Math.floorDiv(invocation, 8) * 48;
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 10;
        origin = new BlockPos(x, y, z);
        source = origin.offset(-7, 0, 0);
        // FakePlayer is intentionally not added to the player list, so it does not provide the
        // chunk ticket a real nearby player supplies. Keep only this disposable fixture region
        // ticking and restore the previous forced-chunk set in finish().
        for (int chunkX = (origin.getX() - 12) >> 4; chunkX <= (origin.getX() + 10) >> 4; chunkX++) {
            for (int chunkZ = (origin.getZ() - 8) >> 4; chunkZ <= (origin.getZ() + 10) >> 4; chunkZ++) {
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
        for (Map.Entry<ResourceId, Long> row : BOM.entrySet().stream().sorted(
                Map.Entry.comparingByKey(Comparator.comparing(ResourceId::toString))).toList()) {
            chest.setItem(slot++, stack(row.getKey(), Math.toIntExact(row.getValue())));
        }
        chest.setChanged();
        PilotWorldMarkerSavedData data = PilotWorldMarkerSavedData.forLevel(level);
        if (data.marker().isEmpty()) {
            data.mark(new PilotWorldMarkerSavedData.Marker(PilotWorldMarkerSavedData.SCHEMA,
                    "world:ie-metal-press-order-acceptance", UUID.randomUUID().toString(),
                    "overworld-pilot-only", "disposable-dedicated-server-fixture",
                    "fixture:ie-metal-press-order", level.getGameTime()));
        }
        player = FakePlayerFactory.get(level, new GameProfile(
                UUID.randomUUID(), "SteveIEOrderFixture"));
        player.moveTo(source.getX() - 1.5D, source.getY(), source.getZ() + 0.5D, 0, 0);
        player.getAbilities().mayBuild = true;
        var start = MetalPressProductionService.create(player, source, origin);
        if (!start.success()) throw new IllegalStateException("IE order fixture start failed: " + start.code());
        orderId = start.orderId();
    }

    public static MetalPressProductionOrderAcceptanceFixture start(MinecraftServer server) {
        AcceptanceRuntimeGuard.requireDevelopmentRuntime("MetalPressProductionOrderAcceptanceFixture");
        return new MetalPressProductionOrderAcceptanceFixture(server);
    }

    public Result tick() {
        ticks++;
        if (ticks > TIMEOUT) {
            var stored = MetalPressOrderSavedData.forLevel(level).order(orderId).orElse(null);
            String stage = stored == null ? "MISSING" : stored.order().stage().name();
            var power = stored == null ? null
                    : dev.stevecreate.agent.forge1201.adapter.immersiveengineering.internal.v1020
                            .ImmersiveEngineeringV1020MetalPressProduction.observePower(level, origin);
            return finish(false, "TIMEOUT:stage=" + stage + ":power=" + power);
        }
        var stored = MetalPressOrderSavedData.forLevel(level).order(orderId).orElse(null);
        if (stored == null) return finish(false, "ORDER_DISAPPEARED");
        if (stored.order().stage() == MetalPressOrderStage.PAUSED) {
            return finish(false, "PAUSED:" + stored.order().statusCode());
        }
        if (stored.order().stage() != MetalPressOrderStage.COMPLETED) return null;
        PlayerMaterialSavedData.Entry material = PlayerMaterialSavedData.forLevel(level)
                .entry(stored.order().projectId()).orElseThrow();
        var report = stored.order().report().orElse(null);
        if (report == null || !report.accepted(2_400)) {
            return finish(false, "REPORT_NOT_ACCEPTED:" + report);
        }
        var common = IndustrialPlayerOrderSavedData.forLevel(level)
                .order(stored.order().projectId()).orElse(null);
        if (common == null || !common.orderType().equals(MetalPressProductionService.ORDER_TYPE)
                || !common.recipeId().equals(MetalPressProductionService.RECIPE)
                || common.report().isEmpty()
                || !common.report().orElseThrow().accepted(
                        Map.of(id("immersiveengineering:fe"), 2_400L),
                        Map.of(MetalPressProductionService.OUTPUT, 1L))) {
            return finish(false, "COMMON_INDUSTRIAL_ORDER_NOT_ACCEPTED:" + common);
        }
        ResourceId resourceProject = id("player_project:"
                + stored.order().projectId().toString().replace("-", ""));
        var resources = IndustrialResourceCheckpointSavedData.forLevel(level);
        var binding = resources.binding(resourceProject).orElse(null);
        var resourceCheckpoint = resources.checkpoint(resourceProject).orElse(null);
        if (binding == null || resourceCheckpoint == null
                || binding.warehouseRequests().size() != 13
                || binding.entityLogistics().workerIds().size() != 2
                || binding.entityLogistics().assignmentIds().size() != 13
                || !binding.plannedEnergy().equals(
                        Map.of(id("immersiveengineering:fe"), 2_400L))
                || !binding.plannedFluids().isEmpty()) {
            return finish(false, "RESOURCE_BINDING_NOT_EXACT:" + binding);
        }
        var warehouseBalance = WarehouseReservationSystem.restore(
                binding.warehouse(), resourceCheckpoint.warehouse()).balance(resourceProject);
        var energyBalance = ProjectEnergyLedger.restore(resourceCheckpoint.energy())
                .balance(resourceProject);
        var fluidBalance = ProjectFluidLedger.restore(resourceCheckpoint.fluid())
                .balance(resourceProject);
        boolean resourceBindingBalanced = warehouseBalance.balanced()
                && warehouseBalance.reserved() == 16 && warehouseBalance.withdrawn() == 16
                && warehouseBalance.consumed() == 2 && warehouseBalance.returned() == 14
                && warehouseBalance.outstanding() == 0
                && energyBalance.balanced() && energyBalance.plannedFe() == 2_400
                && energyBalance.settledFe() == 2_400
                && fluidBalance.balanced() && fluidBalance.withdrawnMb() == 0
                && resourceCheckpoint.logisticsSettlement().filter(
                        value -> value.exactlySettles(binding.entityLogistics())).isPresent();
        if (!resourceBindingBalanced) return finish(false,
                "RESOURCE_BINDING_UNBALANCED:warehouse=" + warehouseBalance
                        + ":energy=" + energyBalance + ":fluid=" + fluidBalance);
        if (material.report() == null || !material.report().balanced()) {
            return finish(false, "PLAYER_LEDGER_NOT_BALANCED");
        }
        String materialProjectTag = "material_project_"
                + stored.order().projectId().toString().replace("-", "");
        String orderBotTag = "ie_metal_press_order_"
                + stored.order().orderId().toString().replace("-", "");
        long retainedBots = iterable(level.getAllEntities()).stream()
                .filter(ConstructionBotEntity.class::isInstance)
                .filter(entity -> entity.getTags().contains(materialProjectTag)
                        || entity.getTags().contains(orderBotTag))
                .count();
        if (retainedBots != 0) return finish(false, "ORDER_BOT_CLEANUP_MISMATCH:" + retainedBots);
        if (material.report().planned() != 16 || material.report().withdrawn() != 16
                || material.report().consumed() != 2 || material.report().returned() != 14
                || material.report().duplicateWithdrawals() != 0
                || material.report().duplicateReturns() != 0
                || material.report().privateItemsTouched() != 0) {
            return finish(false, "MATERIAL_REPORT_COUNTER_MISMATCH:" + material.report());
        }
        if (!(level.getBlockEntity(source) instanceof ChestBlockEntity chest)) {
            return finish(false, "SOURCE_CHEST_MISSING");
        }
        for (Map.Entry<ResourceId, Long> row : BOM.entrySet()) {
            long expected = CONSUMED.contains(row.getKey()) ? 0 : row.getValue();
            if (count(chest, row.getKey()) != expected) {
                return finish(false, "SOURCE_BALANCE_MISMATCH:" + row.getKey());
            }
        }
        long outputs = 0;
        String outputTag = "ie_metal_press_order_" + orderId.toString().replace("-", "");
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof ItemEntity item && item.isAlive()
                    && entity.getTags().contains(outputTag)
                    && id(item.getItem()).equals(MetalPressProductionService.OUTPUT)) {
                outputs += item.getItem().getCount();
            }
        }
        if (outputs != 1) return finish(false, "UNIQUE_OUTPUT_MISMATCH:" + outputs);
        return finish(true, "OK", true, true, true);
    }

    private Result finish(boolean success, String code) {
        return finish(success, code, false, false, false);
    }

    private Result finish(
            boolean success, String code, boolean commonOrderBound,
            boolean commonReportAccepted, boolean resourceBindingBalanced) {
        var stored = MetalPressOrderSavedData.forLevel(level).order(orderId).orElse(null);
        long energy = stored == null ? 0 : stored.order().measuredEnergyConsumedFe();
        long output = stored == null ? 0 : stored.order().exactOutputCount();
        long generation = stored == null ? 0 : stored.order().generation();
        List<String> journal = stored == null ? List.of() : PlayerMaterialSavedData.forLevel(level)
                .entry(stored.order().projectId()).map(entry -> entry.journal().stream()
                        .map(value -> value.sequence() + ":" + value.state() + ":" + value.code())
                        .toList()).orElse(List.of());
        String outputTag = "ie_metal_press_order_" + orderId.toString().replace("-", "");
        for (Entity entity : List.copyOf(iterable(level.getAllEntities()))) {
            if (entity instanceof ItemEntity item && entity.getTags().contains(outputTag)
                    && id(item.getItem()).equals(MetalPressProductionService.OUTPUT)) entity.discard();
        }
        fixtureBaseline.forEach(level::setBlockAndUpdate);
        fixtureChunkTickets.forEach(chunk -> level.setChunkForced(chunk.x, chunk.z, false));
        return new Result(success, code, ticks, energy, output, generation, journal,
                commonOrderBound, commonReportAccepted, resourceBindingBalanced);
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

    private static <T> List<T> iterable(Iterable<T> values) {
        ArrayList<T> result = new ArrayList<>();
        values.forEach(result::add);
        return result;
    }

    private static ItemStack stack(ResourceId id, int count) {
        Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(id.toString()));
        if (item == null) throw new IllegalStateException("fixture item missing: " + id);
        ItemStack result = new ItemStack(item, count);
        // The player ledger deliberately accepts only the exact empty-component identity here.
        // IE may eagerly attach a zero-damage capability tag to a freshly constructed hammer.
        result.setTag(null);
        return result;
    }

    private static ResourceId id(ItemStack stack) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (key == null) throw new IllegalStateException("unregistered fixture item");
        return id(key.toString());
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }

    public record Result(boolean success, String code, int ticks, long energyConsumedFe,
            long outputCount, long orderGeneration, List<String> materialJournal,
            boolean commonOrderBound, boolean commonReportAccepted,
            boolean resourceBindingBalanced) {}
}
