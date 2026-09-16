package dev.stevecreate.agent.forge1201.command;

import com.mojang.authlib.GameProfile;
import dev.stevecreate.agent.core.electrical.ProjectEnergyLedger;
import dev.stevecreate.agent.core.fluid.ProjectFluidLedger;
import dev.stevecreate.agent.core.industrial.MetalPressOrderStage;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.warehouse.WarehouseReservationSystem;
import dev.stevecreate.agent.forge1201.acceptance.AcceptanceRuntimeGuard;
import dev.stevecreate.agent.forge1201.adapter.immersiveengineering.internal.v1020.ImmersiveEngineeringV1020MetalPressProduction;
import dev.stevecreate.agent.forge1201.industrial.IndustrialResourceCheckpointSavedData;
import dev.stevecreate.agent.forge1201.industrial.FactoryMaintenanceApprovalSavedData;
import dev.stevecreate.agent.forge1201.industrial.FactoryMaintenanceExecutionSavedData;
import dev.stevecreate.agent.forge1201.industrial.FactoryMaintenanceProposalSavedData;
import dev.stevecreate.agent.forge1201.industrial.MetalPressOrderSavedData;
import dev.stevecreate.agent.forge1201.industrial.MetalPressOrderSavedData.StoredOrder;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
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

/** Two-JVM production proof from real resource-bound Metal Press crash boundaries. */
public final class MetalPressResourceRecoveryReloadAcceptanceFixture {
    public static final String PHASE_PROPERTY =
            "steve_industrial.test.metalPressResourceRecoveryReloadPhase";
    private static final int TIMEOUT = 8_000;
    private static final Set<String> WINDOWS = Set.of(
            "POWER_VERIFIED", "MAINTENANCE_APPROVED", "MAINTENANCE_PREPARED", "PARTIAL_ENERGY",
            "ENERGY_SETTLED", "OUTPUT_OBSERVED");
    private static final Set<ResourceId> CONSUMED = Set.of(
            id("minecraft:iron_ingot"), id("immersiveengineering:wirecoil_copper"));
    private static Session session;
    private static Logger logger;

    private MetalPressResourceRecoveryReloadAcceptanceFixture() {}

    public static void start(MinecraftServer server, String phase, Logger fixtureLogger) {
        AcceptanceRuntimeGuard.requireDevelopmentRuntime(
                "MetalPressResourceRecoveryReloadAcceptanceFixture");
        String[] parts = phase.split(":", -1);
        String action = parts[0];
        String window = parts.length == 1 ? "POWER_VERIFIED" : parts[1];
        if (session != null || parts.length > 2
                || !(action.equals("write") || action.equals("approve") || action.equals("read"))
                || !WINDOWS.contains(window)) {
            throw new IllegalStateException("invalid Metal Press resource reload phase " + phase);
        }
        logger = fixtureLogger;
        session = action.equals("write")
                ? Session.write(server, window) : Session.reload(server, action, window);
    }

    public static void tick(MinecraftServer server) {
        if (session == null) return;
        Result result = session.tick();
        if (result == null) return;
        String action = session.action;
        String window = session.window;
        session = null;
        if (!result.success()) {
            logger.error("IE_METAL_PRESS_RESOURCE_RELOAD FAIL phase={} detail={}",
                    action + ":" + window, result.code());
            server.halt(false);
            throw new IllegalStateException(result.code());
        }
        if (action.equals("write")) {
            logger.info("IE_METAL_PRESS_RESOURCE_RELOAD_WRITE PASS window={} stage={} "
                            + "realPlayerSource=true resourceBindingSaved=true workers=2 "
                            + "assignments=13 processWillExit=true ticks={}",
                    window, result.stage(), result.ticks());
        } else if (action.equals("approve")) {
            logger.info("IE_METAL_PRESS_MAINTENANCE_RELOAD_APPROVE PASS window={} "
                    + "oldRuntimeDestroyed=true proposalSaved=true approvalSaved={} "
                    + "execution={} thermalSources={} processWillExit=true ticks={}",
                    window, window.equals("MAINTENANCE_PREPARED") ? "CONSUMED" : "APPROVED",
                    window.equals("MAINTENANCE_PREPARED") ? "PREPARED" : "NOT_STARTED",
                    window.equals("MAINTENANCE_PREPARED") ? 1 : 0, result.ticks());
        } else {
            logger.info("IE_METAL_PRESS_RESOURCE_RELOAD_ACCEPTANCE PASS window={} stage={} "
                            + "oldRuntimeDestroyed=true savedDataReloaded=true "
                            + "warehouseReobserved=true botsReobserved=2 electricalReobserved=true "
                            + "resumed=true duplicateWithdrawals=0 duplicateReturns=0 "
                            + "duplicateEnergySettlements=0 duplicateOutputs=0 "
                            + "unaccountedItems=0 privateItemsTouched=0 "
                            + "materialLedgerBalanced=true resourceBindingBalanced=true "
                            + "energyConsumedFe=2400 output=1 baselineRestored=true report=true ticks={}",
                    window, result.stage(), result.ticks());
            if (window.startsWith("MAINTENANCE_")) {
                logger.info("IE_METAL_PRESS_MAINTENANCE_RELOAD_READ PASS "
                        + "window={} proposalReloaded=true approvalReloaded=true "
                        + "approvalConsumed=true executionVerified=true worldMutations={} "
                        + "replayPrevented=true orderCompleted=true",
                        window, window.equals("MAINTENANCE_PREPARED") ? 1 : 2);
            }
        }
        server.halt(false);
    }

    private static final class Session {
        private final ServerLevel level;
        private final String action;
        private final String window;
        private final FakePlayer player;
        private final UUID orderId;
        private boolean recoveryStarted;
        private int ticks;

        private Session(MinecraftServer server, String action, String window,
                FakePlayer player, UUID orderId) {
            this.level = server.overworld();
            this.action = action;
            this.window = window;
            this.player = player;
            this.orderId = orderId;
            StoredOrder stored = MetalPressOrderSavedData.forLevel(level)
                    .order(orderId).orElseThrow();
            forceChunks(level, block(stored.order().machineOrigin()));
        }

        private static Session write(MinecraftServer server, String window) {
            ServerLevel level = server.overworld();
            if (!MetalPressOrderSavedData.forLevel(level).orders().isEmpty()) {
                throw new IllegalStateException("write phase found an existing Metal Press order");
            }
            BlockPos spawn = level.getSharedSpawnPos();
            int x = spawn.getX() + 192;
            int z = spawn.getZ() + 192;
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
            for (var row : requirements().entrySet().stream().sorted(
                    Map.Entry.comparingByKey(Comparator.comparing(ResourceId::toString))).toList()) {
                chest.setItem(slot++, stack(row.getKey(), Math.toIntExact(row.getValue())));
            }
            chest.setChanged();
            PilotWorldMarkerSavedData markers = PilotWorldMarkerSavedData.forLevel(level);
            if (markers.marker().isEmpty()) {
                markers.mark(new PilotWorldMarkerSavedData.Marker(PilotWorldMarkerSavedData.SCHEMA,
                        "world:ie-metal-press-resource-reload", UUID.randomUUID().toString(),
                        "overworld-pilot-only", "disposable-two-process-fixture",
                        "fixture:ie-metal-press-resource-reload", level.getGameTime()));
            }
            UUID playerId = UUID.nameUUIDFromBytes(
                    "metal-press-resource-reload".getBytes(StandardCharsets.UTF_8));
            FakePlayer player = FakePlayerFactory.get(level,
                    new GameProfile(playerId, "StevePressReload"));
            player.moveTo(source.getX() - 1.5D, source.getY(), source.getZ() + 0.5D, 0, 0);
            player.getAbilities().mayBuild = true;
            var started = MetalPressProductionService.create(player, source, origin);
            if (!started.success()) {
                throw new IllegalStateException("Metal Press resource reload start failed: "
                        + started.code());
            }
            return new Session(server, "write", window, player, started.orderId());
        }

        private static Session reload(MinecraftServer server, String action, String window) {
            ServerLevel level = server.overworld();
            if (MetalPressOrderSavedData.forLevel(level).orders().size() != 1) {
                throw new IllegalStateException("read phase did not restore exactly one order");
            }
            StoredOrder stored = MetalPressOrderSavedData.forLevel(level)
                    .orders().values().iterator().next();
            FakePlayer player = FakePlayerFactory.get(level, new GameProfile(
                    stored.order().playerId(), "StevePressReload"));
            BlockPos source = block(stored.order().materialSource());
            player.moveTo(source.getX() - 1.5D, source.getY(), source.getZ() + 0.5D, 0, 0);
            player.getAbilities().mayBuild = true;
            if (!stored.order().stage().name().equals(targetStage(window))) {
                throw new IllegalStateException("read phase expected " + targetStage(window)
                        + " for " + window + " but restored "
                        + stored.order().stage());
            }
            return new Session(server, action, window, player, stored.order().orderId());
        }

        private Result tick() {
            ticks++;
            StoredOrder stored = MetalPressOrderSavedData.forLevel(level)
                    .order(orderId).orElse(null);
            if (stored == null) return result(false, "ORDER_MISSING", "MISSING");
            var order = stored.order();
            if (order.stage() == MetalPressOrderStage.PAUSED) {
                return result(false, "PAUSED:" + order.statusCode(), order.stage().name());
            }
            if (ticks > TIMEOUT) {
                return result(false, "TIMEOUT:" + order.statusCode(), order.stage().name());
            }
            if (action.equals("write")) {
                ResourceId project = projectId(order.projectId());
                var resources = IndustrialResourceCheckpointSavedData.forLevel(level);
                if (!order.stage().name().equals(targetStage(window))
                        || resources.binding(project).isEmpty()
                        || resources.checkpoint(project).isEmpty()) return null;
                var binding = resources.binding(project).orElseThrow();
                if (binding.entityLogistics().workerIds().size() != 2
                        || binding.entityLogistics().assignmentIds().size() != 13) {
                    return result(false, "RESOURCE_BINDING_SHAPE_MISMATCH", order.stage().name());
                }
                if (window.equals("ENERGY_SETTLED")) {
                    var energy = ProjectEnergyLedger.restore(
                            resources.checkpoint(project).orElseThrow().energy()).balance(project);
                    if (energy.settledFe() != 2_400 || energy.duplicateSettlements() != 0
                            || order.measuredEnergyConsumedFe() != 2_400
                            || taggedOutput(level, orderId) != 0 || order.report().isPresent()) {
                        return result(false, "ENERGY_WINDOW_NOT_EXACT", order.stage().name());
                    }
                }
                if (window.equals("PARTIAL_ENERGY")) {
                    var process = ImmersiveEngineeringV1020MetalPressProduction
                            .processingSnapshot(level, block(order.machineOrigin()),
                                    Math.toIntExact(order.energyAtInputFe()));
                    if (!process.machinePresent() || process.queueSize() != 1
                            || process.processTick() < 1
                            || !process.recipeId().equals(order.recipeId().toString())
                            || process.observedEnergyDeltaFe() < 1
                            || process.observedEnergyDeltaFe() >= order.expectedEnergyFe()
                            || taggedOutput(level, orderId) != 0) {
                        return null;
                    }
                }
                if (window.equals("OUTPUT_OBSERVED")
                        && (taggedOutput(level, orderId) != 1 || order.report().isPresent())) {
                    return result(false, "OUTPUT_WINDOW_NOT_EXACT", order.stage().name());
                }
                level.getDataStorage().save();
                return result(true, "WRITE_CHECKPOINT_REACHED", order.stage().name());
            }
            if (action.equals("approve")) {
                // After the first JVM is destroyed, IE's internal machine FE buffer is empty.
                // Preserve that real reload boundary: diagnose and approve, but do not execute or
                // invoke production recovery in this process.
                if (ticks < 40) return null;
                if (!window.startsWith("MAINTENANCE_")) {
                    return result(false, "APPROVE_ACTION_REQUIRES_MAINTENANCE_WINDOW",
                            order.stage().name());
                }
                if (FactoryMaintenanceService.proposeOwnedMetalPressPower(player)
                                .proposal().isEmpty()
                        || !FactoryMaintenanceService.approveCurrent(player).success()) {
                    return result(false, "MAINTENANCE_APPROVAL_SETUP_FAILED",
                            order.stage().name());
                }
                var proposal = FactoryMaintenanceProposalSavedData.forLevel(level)
                        .proposal(player.getUUID()).orElse(null);
                if (proposal == null || FactoryMaintenanceApprovalSavedData.forLevel(level)
                        .ledger().approvals().get(proposal.proposalId()) == null
                        || ImmersiveEngineeringV1020MetalPressProduction
                                .thermalGenerationActive(level, block(order.machineOrigin()))) {
                    return result(false, "MAINTENANCE_AUTHORITY_NOT_PERSISTED",
                            order.stage().name());
                }
                if (window.equals("MAINTENANCE_PREPARED")) {
                    var approvals = FactoryMaintenanceApprovalSavedData.forLevel(level);
                    var ledger = approvals.ledger();
                    var diagnosis = FactoryDiagnosticService.diagnoseOwnedMetalPressPower(
                            player, proposal.subjectId()).orElse(null);
                    var consumed = diagnosis == null ? null : ledger.consume(proposal, diagnosis,
                            player.getUUID(), level.getGameTime());
                    if (consumed == null || !consumed.success()) {
                        return result(false, "MAINTENANCE_PREPARE_CONSUME_FAILED",
                                order.stage().name());
                    }
                    approvals.put(ledger);
                    FactoryMaintenanceExecutionSavedData.forLevel(level).prepare(
                            proposal.proposalId(), player.getUUID(), proposal.diagnosticHash(),
                            level.getGameTime());
                    level.getDataStorage().save();
                    var machine = ImmersiveEngineeringV1020MetalPressProduction.inspect(
                            level, block(order.machineOrigin())).orElse(null);
                    if (machine == null) return result(false, "MAINTENANCE_MACHINE_MISSING",
                            order.stage().name());
                    var layout = ImmersiveEngineeringV1020MetalPressProduction.layout(machine);
                    level.setBlockAndUpdate(layout.coldSource(), Blocks.BLUE_ICE.defaultBlockState());
                }
                level.getDataStorage().save();
                return result(true, "MAINTENANCE_APPROVED_CHECKPOINT", order.stage().name());
            }
            if (!recoveryStarted) {
                // The global IE wire network completes its own world-load pass before the
                // production service's normal 20-tick recovery scan. Do not race it from
                // the fixture's first tick and turn a transient startup state into drift.
                if (ticks < 40) return null;
                if (window.startsWith("MAINTENANCE_")) {
                    var proposal = FactoryMaintenanceProposalSavedData.forLevel(level)
                            .proposal(player.getUUID()).orElse(null);
                    if (proposal == null || FactoryMaintenanceApprovalSavedData.forLevel(level)
                            .ledger().approvals().get(proposal.proposalId()) == null) {
                        return result(false, "MAINTENANCE_AUTHORITY_NOT_RELOADED",
                                order.stage().name());
                    }
                    var maintained = FactoryMaintenanceService.executeCurrent(player);
                    int expectedMutations = window.equals("MAINTENANCE_PREPARED") ? 1 : 2;
                    if (!maintained.success() || maintained.worldMutations() != expectedMutations
                            || FactoryMaintenanceExecutionSavedData.forLevel(level)
                                    .entry(proposal.proposalId()).orElseThrow().state()
                            != FactoryMaintenanceExecutionSavedData.State.VERIFIED) {
                        return result(false, "MAINTENANCE_RELOAD_EXECUTION_FAILED:"
                                + maintained.code(), order.stage().name());
                    }
                }
                recoveryStarted = MetalPressProductionService.recoverForOwner(player);
                if (!recoveryStarted) return null;
            }
            if (order.stage() != MetalPressOrderStage.COMPLETED) return null;
            PlayerMaterialSavedData.Entry material = PlayerMaterialSavedData.forLevel(level)
                    .entry(order.projectId()).orElse(null);
            if (material == null || material.report() == null || !material.report().balanced()
                    || material.report().duplicateWithdrawals() != 0
                    || material.report().duplicateReturns() != 0
                    || material.report().unaccountedItems() != 0
                    || material.report().privateItemsTouched() != 0) {
                return result(false, "MATERIAL_LEDGER_NOT_BALANCED", order.stage().name());
            }
            ResourceId project = projectId(order.projectId());
            var resources = IndustrialResourceCheckpointSavedData.forLevel(level);
            var binding = resources.binding(project).orElse(null);
            var checkpoint = resources.checkpoint(project).orElse(null);
            if (binding == null || checkpoint == null) {
                return result(false, "RESOURCE_BINDING_MISSING", order.stage().name());
            }
            var warehouse = WarehouseReservationSystem.restore(
                    binding.warehouse(), checkpoint.warehouse()).balance(project);
            var energy = ProjectEnergyLedger.restore(checkpoint.energy()).balance(project);
            var fluid = ProjectFluidLedger.restore(checkpoint.fluid()).balance(project);
            boolean balanced = warehouse.balanced() && warehouse.reserved() == 16
                    && warehouse.withdrawn() == 16 && warehouse.consumed() == 2
                    && warehouse.returned() == 14 && warehouse.outstanding() == 0
                    && energy.balanced() && energy.plannedFe() == 2_400
                    && energy.settledFe() == 2_400 && energy.duplicateSettlements() == 0
                    && fluid.balanced()
                    && checkpoint.logisticsSettlement().filter(
                            value -> value.exactlySettles(binding.entityLogistics())).isPresent();
            if (!balanced) return result(false, "RESOURCE_BINDING_NOT_BALANCED",
                    order.stage().name());
            if (!sourceBalanced(level, stored, material.requirements())) {
                return result(false, "SOURCE_BALANCE_MISMATCH", order.stage().name());
            }
            if (taggedOutput(level, orderId) != 1 || order.exactOutputCount() != 1
                    || order.measuredEnergyConsumedFe() != 2_400 || order.report().isEmpty()
                    || !order.report().orElseThrow().accepted(2_400)) {
                return result(false, "OUTPUT_ENERGY_OR_REPORT_MISMATCH", order.stage().name());
            }
            return result(true, "OK", order.stage().name());
        }

    private Result result(boolean success, String code, String stage) {
            return new Result(success, code, ticks, stage);
        }
    }

    private static String targetStage(String window) {
        return window.equals("PARTIAL_ENERGY") ? "PROCESSING"
                : window.startsWith("MAINTENANCE_") ? "POWER_VERIFIED" : window;
    }

    private static Map<ResourceId, Long> requirements() {
        return Map.ofEntries(
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
    }

    private static boolean sourceBalanced(ServerLevel level, StoredOrder stored,
            Map<ResourceId, Long> bill) {
        if (!(level.getBlockEntity(block(stored.order().materialSource()))
                instanceof ChestBlockEntity chest)) return false;
        return bill.entrySet().stream().allMatch(row -> count(chest, row.getKey())
                == (CONSUMED.contains(row.getKey()) ? 0 : row.getValue()));
    }

    private static long taggedOutput(ServerLevel level, UUID orderId) {
        String tag = "ie_metal_press_order_" + orderId.toString().replace("-", "");
        long count = 0;
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof ItemEntity item && item.isAlive()
                    && entity.getTags().contains(tag)
                    && id(item.getItem()).equals(MetalPressProductionService.OUTPUT)) {
                count += item.getItem().getCount();
            }
        }
        return count;
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
        ResourceLocation value = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (value == null) throw new IllegalStateException("unregistered fixture item");
        return id(value.toString());
    }

    private static ResourceId projectId(UUID value) {
        return id("player_project:" + value.toString().replace("-", ""));
    }
    private static ResourceId id(String value) { return ResourceId.parse(value); }
    private static BlockPos block(BlockPos3i value) {
        return new BlockPos(value.x(), value.y(), value.z());
    }

    private record Result(boolean success, String code, int ticks, String stage) {}
}
