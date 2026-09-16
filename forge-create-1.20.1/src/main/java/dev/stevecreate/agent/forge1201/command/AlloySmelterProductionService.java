package dev.stevecreate.agent.forge1201.command;

import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import dev.stevecreate.agent.core.execution.construction.MaterialExecutorKind;
import dev.stevecreate.agent.core.execution.construction.MaterialIdentity;
import dev.stevecreate.agent.core.execution.construction.MaterialTransactionState;
import dev.stevecreate.agent.core.industrial.IndustrialCompletionReportV1;
import dev.stevecreate.agent.core.industrial.IndustrialLifecyclePhase;
import dev.stevecreate.agent.core.industrial.IndustrialPlayerOrderV1;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.forge1201.adapter.immersiveengineering.internal.v1020.ImmersiveEngineeringV1020AlloySmelterProduction;
import dev.stevecreate.agent.forge1201.entity.ConstructionBotEntities;
import dev.stevecreate.agent.forge1201.entity.ConstructionBotEntity;
import dev.stevecreate.agent.forge1201.industrial.AlloySmelterOrderSavedData;
import dev.stevecreate.agent.forge1201.industrial.AlloySmelterOrderSavedData.BaselineBlock;
import dev.stevecreate.agent.forge1201.industrial.AlloySmelterOrderSavedData.StoredOrder;
import dev.stevecreate.agent.forge1201.industrial.AlloySmelterOrderSavedData.WarehouseBinding;
import dev.stevecreate.agent.forge1201.industrial.IndustrialPlayerOrderSavedData;
import dev.stevecreate.agent.forge1201.industrial.IndustrialPlayerOrderService;
import dev.stevecreate.agent.forge1201.player.MaterialLedgerProjection;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.Entry;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.Reservation;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.Source;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.Transaction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Player-selected, material-ledger-backed execution of the reviewed IE Alloy
 * Smelter brass batch.
 *
 * <p>The service owns orchestration only. Exact machine state and recipe behavior
 * remain in the versioned IE adapter; source selection, slot reservations and Bot
 * carry remain in the frozen player-material protocol.</p>
 */
public final class AlloySmelterProductionService {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(
            AlloySmelterProductionService.class);
    public static final ResourceId ORDER_TYPE = id("steve_industrial:alloy_smelter");
    public static final ResourceId OUTPUT = id("create:brass_ingot");
    private static final ResourceId ALLOY_BRICK = id("immersiveengineering:alloybrick");
    private static final ResourceId HAMMER = id("immersiveengineering:hammer");
    private static final ResourceId COAL = id("minecraft:coal");
    private static final int STRUCTURE_BLOCKS = 8;
    private static final int MAX_ORIGIN_DISTANCE_SQUARED = 32 * 32;
    private static final String BUILDER_TAG = "steve_ie_alloy_smelter_builder";
    private static final String ORDER_TAG = "ie_alloy_smelter_order_";
    private static final String OUTPUT_STACK_ORDER = "SteveIndustrialAlloyOrder";
    private static final Map<UUID, Active> ACTIVE = new HashMap<>();

    private static final ResourceId EFFECT_WITHDRAWAL = effect("material_withdrawal");
    private static final ResourceId EFFECT_STRUCTURE = effect("structure_placement");
    private static final ResourceId EFFECT_FORMATION = effect("multiblock_formation");
    private static final ResourceId EFFECT_BATCH = effect("batch_admission");
    private static final ResourceId EFFECT_OUTPUT = effect("output_claim");
    private static final ResourceId EFFECT_BASELINE = effect("baseline_restore");
    private static final ResourceId EFFECT_RETURN = effect("material_return");
    private static final ResourceId EFFECT_REPORT = effect("completion_report");

    private AlloySmelterProductionService() {}

    public static StartResult create(
            ServerPlayer player, BlockPos sourcePosition, BlockPos origin) {
        return create(player, sourcePosition, origin, Optional.empty());
    }

    /**
     * Starts the same reviewed physical order under one exact warehouse batch.
     * The destination is deliberately the selected material source in this first
     * boundary: it is already authorised and keeps output inside the inventory the
     * maintain-stock observer measures.
     */
    public static StartResult createForWarehouse(
            ServerPlayer actor,
            BlockPos sourcePosition,
            BlockPos origin,
            ResourceId warehouseId,
            ResourceId productionOrderId,
            ResourceId batchId,
            BlockPos outputDestination) {
        if (!sourcePosition.equals(outputDestination)) {
            return StartResult.failure("WAREHOUSE_OUTPUT_DESTINATION_NOT_SELECTED_SOURCE");
        }
        return create(actor, sourcePosition, origin, Optional.of(new WarehouseBinding(
                warehouseId, productionOrderId, batchId, pos(outputDestination))));
    }

    private static StartResult create(
            ServerPlayer player,
            BlockPos sourcePosition,
            BlockPos origin,
            Optional<WarehouseBinding> warehouseBinding) {
        ServerLevel level = player.serverLevel();
        if (!level.getServer().isSameThread()) return StartResult.failure("SERVER_THREAD_REQUIRED");
        if (!ModList.get().isLoaded("immersiveengineering")) {
            return StartResult.failure("IMMERSIVE_ENGINEERING_NOT_LOADED");
        }
        String runtime = ModList.get().getModContainerById("immersiveengineering")
                .map(value -> value.getModInfo().getVersion().toString()).orElse("");
        if (!runtime.contains("10.2.0-183")) {
            return StartResult.failure("IE_RUNTIME_UNSUPPORTED");
        }
        PilotWorldMarkerSavedData.Marker marker = PilotWorldMarkerSavedData.forLevel(level)
                .marker().orElse(null);
        if (marker == null) return StartResult.failure("WORLD_NOT_AUTHORIZED");
        if (!level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) {
            return StartResult.failure("ORDER_DIMENSION_UNSUPPORTED");
        }
        if (!player.mayBuild()) return StartResult.failure("PLAYER_BUILD_PERMISSION_DENIED");
        if (player.distanceToSqr(origin.getX() + 0.5D, origin.getY() + 0.5D,
                origin.getZ() + 0.5D) > MAX_ORIGIN_DISTANCE_SQUARED) {
            return StartResult.failure("MACHINE_ORIGIN_OUT_OF_RANGE");
        }
        if (AlloySmelterOrderSavedData.forLevel(level).orders().values().stream().anyMatch(value ->
                value.playerId().equals(player.getUUID()) && !terminal(level, value.orderId()))) {
            return StartResult.failure("PLAYER_ALREADY_HAS_ACTIVE_ALLOY_SMELTER_ORDER");
        }
        var reviewed = ImmersiveEngineeringV1020AlloySmelterProduction
                .reviewedRecipe(level).orElse(null);
        if (reviewed == null) return StartResult.failure("ALLOY_SMELTER_RECIPE_UNAVAILABLE");
        if (!reviewed.recipeId().equals(
                ImmersiveEngineeringV1020AlloySmelterProduction.REVIEWED_RECIPE)) {
            return StartResult.failure("ALLOY_SMELTER_RECIPE_CHANGED");
        }
        ItemStack first = reviewed.firstInput();
        ItemStack second = reviewed.secondInput();
        ItemStack output = reviewed.output();
        if (first.hasTag() || second.hasTag() || output.hasTag()
                || output.getCount() != 2 || !itemId(output).equals(OUTPUT)) {
            return StartResult.failure("ALLOY_SMELTER_RECIPE_IDENTITY_UNSUPPORTED");
        }
        ResourceId firstId = itemId(first);
        ResourceId secondId = itemId(second);
        LinkedHashMap<ResourceId, Long> requirements = new LinkedHashMap<>();
        requirements.put(ALLOY_BRICK, (long) STRUCTURE_BLOCKS);
        requirements.put(HAMMER, 1L);
        requirements.merge(firstId, (long) first.getCount(), Math::addExact);
        requirements.merge(secondId, (long) second.getCount(), Math::addExact);
        requirements.merge(COAL, 1L, Math::addExact);

        BlockPos staging = origin.offset(-3, 0, -2).immutable();
        List<BlockPos> mutable = new ArrayList<>(
                ImmersiveEngineeringV1020AlloySmelterProduction.baselinePositions(level, origin));
        mutable.add(staging);
        mutable = mutable.stream().distinct().sorted(positionOrder()).toList();
        if (mutable.contains(sourcePosition)
                || mutable.size() > AlloySmelterOrderSavedData.MAX_BASELINE_BLOCKS) {
            return StartResult.failure("MATERIAL_SOURCE_OVERLAPS_ORDER_SITE");
        }
        for (BlockPos position : mutable) {
            if (!level.hasChunkAt(position)) return StartResult.failure("ORDER_CHUNK_NOT_LOADED");
            BlockState state = level.getBlockState(position);
            if ((!state.isAir() && !state.canBeReplaced()) || level.getBlockEntity(position) != null) {
                ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
                return StartResult.failure("ORDER_SITE_NOT_EMPTY:" + position.getX() + ","
                        + position.getY() + "," + position.getZ() + ":"
                        + (blockId == null ? "unregistered" : blockId));
            }
        }

        UUID orderId = UUID.randomUUID();
        List<BaselineBlock> baseline = mutable.stream().map(position -> new BaselineBlock(
                pos(position), NbtUtils.writeBlockState(level.getBlockState(position)))).toList();
        String baselineHash = baselineHash(baseline);
        String planHash = planHash(runtime, reviewed.recipeId(), requirements,
                firstId, first.getCount(), secondId, second.getCount(), OUTPUT, output.getCount());
        var opened = PlayerMaterialService.openStandalonePlan(player, orderId,
                Map.copyOf(requirements), planHash, runtime);
        if (!opened.success()) return StartResult.failure(opened.statusCode());
        var selected = PlayerMaterialService.selectStandaloneSource(
                player, orderId, pos(sourcePosition), Direction.UP);
        if (!selected.success()) return StartResult.failure(selected.statusCode());
        var reserved = PlayerMaterialService.confirmStandalone(
                player, orderId, marker.worldIdentity());
        if (!reserved.success()) return StartResult.failure(withMissingDetail(reserved, selected));
        if (!placeStaging(level, staging)) return StartResult.failure("STAGING_CHEST_FAILED");

        Entry material = reserved.entry().withLogistics(
                new PlayerMaterialSavedData.Logistics(pos(staging), pos(staging)),
                Instant.now().toEpochMilli(), "IE_ALLOY_ORDER_LOGISTICS_BOUND");
        PlayerMaterialSavedData.forLevel(level).put(material);
        material = PlayerConstructionService.prepareWithdrawal(
                level, material, MaterialExecutorKind.BOT);
        DeploymentBoundingBox bounds = bounds(mutable, sourcePosition, player.blockPosition());
        PlayerConstructionService.MaterialCourier courier;
        try {
            courier = PlayerConstructionService.MaterialCourier.spawnForOwner(level, orderId,
                    material, pos(staging), pos(player.blockPosition()), bounds, player);
        } catch (RuntimeException failure) {
            releasePreparedTransactions(level, orderId);
            restoreBaseline(level, baseline);
            return StartResult.failure("MATERIAL_COURIER_START_REFUSED");
        }

        long now = Instant.now().toEpochMilli();
        var identity = new IndustrialPlayerOrderService.OrderIdentity(orderId, player.getUUID(),
                id(level.dimension().location().toString()), pos(origin), baselineHash, now);
        var bound = IndustrialPlayerOrderService.bindReviewedOrder(player, identity, ORDER_TYPE,
                OUTPUT, id(reviewed.recipeId().toString()), planHash, runtime,
                marker.worldIdentity());
        if (!bound.success()) {
            courier.cancelAndReturn();
            restoreBaseline(level, baseline);
            return StartResult.failure(bound.code());
        }
        StoredOrder stored = new StoredOrder(orderId, player.getUUID(),
                id(level.dimension().location().toString()), pos(origin), pos(sourcePosition),
                pos(staging), id(reviewed.recipeId().toString()), firstId, first.getCount(),
                secondId, second.getCount(), COAL, 1, OUTPUT, output.getCount(), planHash,
                runtime, baselineHash, baseline, warehouseBinding);
        AlloySmelterOrderSavedData.forLevel(level).put(stored);
        save(level);
        ACTIVE.put(orderId, new Active(orderId, player.getUUID(), origin.immutable(),
                staging, bounds, courier, null, player));
        return new StartResult(true, "ORDER_STARTED", orderId,
                IndustrialPlayerOrderSavedData.forLevel(level).order(orderId).orElseThrow(),
                material);
    }

    /** Read-only live bill used by warehouse admission before it chooses a source. */
    public static RequirementResult requirements(ServerLevel level) {
        if (!level.getServer().isSameThread()) {
            return RequirementResult.failure("SERVER_THREAD_REQUIRED");
        }
        if (!ModList.get().isLoaded("immersiveengineering")) {
            return RequirementResult.failure("IMMERSIVE_ENGINEERING_NOT_LOADED");
        }
        String runtime = ModList.get().getModContainerById("immersiveengineering")
                .map(value -> value.getModInfo().getVersion().toString()).orElse("");
        if (!runtime.contains("10.2.0-183")) {
            return RequirementResult.failure("IE_RUNTIME_UNSUPPORTED");
        }
        var reviewed = ImmersiveEngineeringV1020AlloySmelterProduction
                .reviewedRecipe(level).orElse(null);
        if (reviewed == null) return RequirementResult.failure("ALLOY_SMELTER_RECIPE_UNAVAILABLE");
        if (!reviewed.recipeId().equals(
                ImmersiveEngineeringV1020AlloySmelterProduction.REVIEWED_RECIPE)) {
            return RequirementResult.failure("ALLOY_SMELTER_RECIPE_CHANGED");
        }
        ItemStack first = reviewed.firstInput();
        ItemStack second = reviewed.secondInput();
        ItemStack output = reviewed.output();
        if (first.hasTag() || second.hasTag() || output.hasTag()
                || output.getCount() != 2 || !itemId(output).equals(OUTPUT)) {
            return RequirementResult.failure("ALLOY_SMELTER_RECIPE_IDENTITY_UNSUPPORTED");
        }
        LinkedHashMap<ResourceId, Long> bill = new LinkedHashMap<>();
        bill.put(ALLOY_BRICK, (long) STRUCTURE_BLOCKS);
        bill.put(HAMMER, 1L);
        bill.merge(itemId(first), (long) first.getCount(), Math::addExact);
        bill.merge(itemId(second), (long) second.getCount(), Math::addExact);
        bill.merge(COAL, 1L, Math::addExact);
        return new RequirementResult(true, "OK", Map.copyOf(bill), OUTPUT, output.getCount(),
                id(reviewed.recipeId().toString()), runtime);
    }

    public static void tick(MinecraftServer server) {
        if (!ModList.get().isLoaded("immersiveengineering")) return;
        recoverMissing(server);
        for (Active active : List.copyOf(ACTIVE.values())) {
            ServerPlayer player = active.livePlayer != null ? active.livePlayer
                    : server.getPlayerList().getPlayer(active.playerId);
            if (player == null) continue;
            try {
                tickActive(player, active);
            } catch (RuntimeException failure) {
                String detail = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                pause(player, active.orderId, "ALLOY_ORDER_EXCEPTION:" + bounded(detail));
                ACTIVE.remove(active.orderId);
            }
        }
    }

    private static void tickActive(ServerPlayer player, Active active) {
        ServerLevel level = player.serverLevel();
        StoredOrder stored = AlloySmelterOrderSavedData.forLevel(level)
                .order(active.orderId).orElseThrow();
        IndustrialPlayerOrderV1 order = order(level, active.orderId);
        if (terminal(order) || order.phase() == IndustrialLifecyclePhase.PAUSED) {
            ACTIVE.remove(active.orderId);
            return;
        }
        if (active.courier != null) {
            PlayerConstructionService.CourierTick result = active.courier.tick();
            if (result == PlayerConstructionService.CourierTick.PROGRESS) return;
            if (result == PlayerConstructionService.CourierTick.FAILED) {
                pause(player, active.orderId,
                        "MATERIAL_COURIER_FAILED:" + active.courier.failureCode());
                ACTIVE.remove(active.orderId);
                return;
            }
            checkpoint(player, active.orderId, IndustrialLifecyclePhase.PLACED,
                    Stage.MATERIALS_DELIVERED, EFFECT_WITHDRAWAL);
            active.courier = null;
            active.builderId = ensureBuilder(level, active, player).getUUID();
            return;
        }

        ConstructionBotEntity builder = ensureBuilder(level, active, player);
        BlockPos work = workCell(level, active.origin);
        if (!at(builder, work)) {
            builder.advanceToward(work);
            return;
        }
        Entry material = PlayerMaterialSavedData.forLevel(level)
                .entry(active.orderId).orElseThrow();
        Stage stage = Stage.valueOf(order.stage());
        switch (stage) {
            case MATERIALS_RESERVED -> {
                if (!allTransactions(material, MaterialTransactionState.DELIVERED)) {
                    throw new OrderFailure("MATERIAL_DELIVERY_RECONCILIATION_REQUIRED");
                }
                checkpoint(player, active.orderId, IndustrialLifecyclePhase.PLACED,
                        Stage.MATERIALS_DELIVERED, EFFECT_WITHDRAWAL);
            }
            case MATERIALS_DELIVERED -> {
                if (ImmersiveEngineeringV1020AlloySmelterProduction.rawStructurePresent(
                        level, active.origin)) {
                    checkpoint(player, active.orderId, IndustrialLifecyclePhase.PLACED,
                            Stage.STRUCTURE_BUILT, EFFECT_STRUCTURE);
                    return;
                }
                removeFromStaging(level, active.staging, ALLOY_BRICK, STRUCTURE_BLOCKS);
                var placed = ImmersiveEngineeringV1020AlloySmelterProduction
                        .placeStructure(level, active.origin);
                if (!placed.success() || placed.placedBlocks() != STRUCTURE_BLOCKS) {
                    throw new OrderFailure(placed.code());
                }
                checkpoint(player, active.orderId, IndustrialLifecyclePhase.PLACED,
                        Stage.STRUCTURE_BUILT, EFFECT_STRUCTURE);
            }
            case STRUCTURE_BUILT -> {
                if (ImmersiveEngineeringV1020AlloySmelterProduction.multiblockFormed(
                        level, active.origin)) {
                    checkpoint(player, active.orderId, IndustrialLifecyclePhase.FORMED,
                            Stage.MULTIBLOCK_FORMED, EFFECT_FORMATION);
                    return;
                }
                ItemStack hammer = removeFromStaging(level, active.staging, HAMMER, 1);
                builder.setItemSlot(EquipmentSlot.MAINHAND, hammer);
                var formed = ImmersiveEngineeringV1020AlloySmelterProduction.form(
                        level, active.origin, builder.getItemBySlot(EquipmentSlot.MAINHAND));
                if (!formed.success()) throw new OrderFailure(formed.code());
                builder.setItemSlot(EquipmentSlot.OFFHAND,
                        builder.getItemBySlot(EquipmentSlot.MAINHAND).copy());
                builder.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                checkpoint(player, active.orderId, IndustrialLifecyclePhase.FORMED,
                        Stage.MULTIBLOCK_FORMED, EFFECT_FORMATION);
            }
            case MULTIBLOCK_FORMED -> admitOrRecoverBatch(player, active, stored, material, builder);
            case PROCESSING -> observeAndClaimOutput(player, active, stored);
            case OUTPUT_OBSERVED -> checkpoint(player, active.orderId,
                    IndustrialLifecyclePhase.DISMANTLING, Stage.TEARDOWN, null);
            case TEARDOWN -> {
                if (!baselineMatches(level, stored.baseline())) {
                    List<ItemStack> residue = ImmersiveEngineeringV1020AlloySmelterProduction
                            .extractAllInputs(level, active.origin);
                    if (!residue.isEmpty()) {
                        throw new OrderFailure("ALLOY_SMELTER_RESIDUE_BEFORE_TEARDOWN");
                    }
                    ImmersiveEngineeringV1020AlloySmelterProduction.clear(level, active.origin);
                    restoreBaseline(level, stored.baseline());
                }
                if (!baselineMatches(level, stored.baseline())) {
                    throw new OrderFailure("BASELINE_RESTORE_MISMATCH");
                }
                checkpoint(player, active.orderId, IndustrialLifecyclePhase.DISMANTLING,
                        Stage.BASELINE_RESTORED, EFFECT_BASELINE);
            }
            case BASELINE_RESTORED -> {
                if (!returnMaterials(player, active, stored)) {
                    throw new OrderFailure("RETURN_PENDING_SOURCE_FULL_OR_CHANGED");
                }
                checkpoint(player, active.orderId, IndustrialLifecyclePhase.DISMANTLING,
                        Stage.MATERIALS_RETURNED, EFFECT_RETURN);
            }
            case MATERIALS_RETURNED -> generateReport(player, active, stored);
            case REPORT_GENERATED, COMPLETED -> finishCompleted(level, stored);
            case CANCELLED, PAUSED -> ACTIVE.remove(active.orderId);
        }
    }

    private static void admitOrRecoverBatch(
            ServerPlayer player,
            Active active,
            StoredOrder stored,
            Entry material,
            ConstructionBotEntity builder) {
        ServerLevel level = player.serverLevel();
        ItemStack first = stack(stored.firstInput(), stored.firstInputCount());
        ItemStack second = stack(stored.secondInput(), stored.secondInputCount());
        ItemStack fuel = stack(stored.fuel(), stored.fuelCount());
        var admission = ImmersiveEngineeringV1020AlloySmelterProduction
                .observeReviewedAdmission(level, active.origin, first, second, fuel);
        if (!admission.verified()) {
            var processing = ImmersiveEngineeringV1020AlloySmelterProduction
                    .observeReviewedProcessing(level, active.origin, first, second,
                            stack(stored.output(), stored.outputCount()));
            if (processing.verified()) {
                consumeBatch(level, stored);
                checkpoint(player, active.orderId, IndustrialLifecyclePhase.RUNNING,
                        Stage.PROCESSING, EFFECT_BATCH);
                return;
            }
            first = removeFromStaging(level, active.staging,
                    stored.firstInput(), stored.firstInputCount());
            second = removeFromStaging(level, active.staging,
                    stored.secondInput(), stored.secondInputCount());
            fuel = removeFromStaging(level, active.staging, stored.fuel(), stored.fuelCount());
            builder.setItemSlot(EquipmentSlot.MAINHAND, first.copy());
            var fed = ImmersiveEngineeringV1020AlloySmelterProduction.feedReviewedBatch(
                    level, active.origin, first, second, fuel);
            builder.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            if (!fed.success()) {
                List<ItemStack> recovered = fed.recoveredOnFailure().isEmpty()
                        ? List.of(first, second, fuel) : fed.recoveredOnFailure();
                if (!restoreRecoveredToStaging(level, active.staging, recovered)) {
                    throw new OrderFailure(fed.code() + ":RECOVERY_STAGING_FULL");
                }
                throw new OrderFailure(fed.code());
            }
        }
        consumeBatch(level, stored);
        checkpoint(player, active.orderId, IndustrialLifecyclePhase.RUNNING,
                Stage.PROCESSING, EFFECT_BATCH);
    }

    private static void observeAndClaimOutput(
            ServerPlayer player, Active active, StoredOrder stored) {
        ServerLevel level = player.serverLevel();
        long delivered = taggedOutputCount(level, stored);
        if (delivered == stored.outputCount()) {
            checkpoint(player, active.orderId, IndustrialLifecyclePhase.RUNNING,
                    Stage.OUTPUT_OBSERVED, EFFECT_OUTPUT);
            return;
        }
        if (delivered != 0) throw new OrderFailure("DELIVERED_OUTPUT_NOT_UNIQUE");
        ItemStack expected = stack(stored.output(), stored.outputCount());
        var observed = ImmersiveEngineeringV1020AlloySmelterProduction.observeUniqueOutput(
                level, active.origin, expected, stored.fuelCount());
        if (observed.code().equals("ALLOY_SMELTER_OUTPUT_NOT_YET_OBSERVED")) return;
        if (!observed.verified()) throw new OrderFailure(observed.code());
        ItemStack actual = ImmersiveEngineeringV1020AlloySmelterProduction
                .claimUniqueOutput(level, active.origin, expected);
        deliverTaggedOutput(level, stored, actual);
        checkpoint(player, active.orderId, IndustrialLifecyclePhase.RUNNING,
                Stage.OUTPUT_OBSERVED, EFFECT_OUTPUT);
    }

    private static void generateReport(
            ServerPlayer player, Active active, StoredOrder stored) {
        ServerLevel level = player.serverLevel();
        Entry entry = PlayerMaterialSavedData.forLevel(level)
                .entry(active.orderId).orElseThrow();
        MaterialLedgerProjection.Evidence evidence = MaterialLedgerProjection.project(entry);
        long duplicateWithdrawals = duplicates(entry, MaterialTransactionState.WITHDRAWN);
        long duplicateReturns = duplicates(entry, MaterialTransactionState.RETURNED);
        long unaccounted = unaccounted(entry.requirements(), evidence);
        long planned = total(entry.requirements());
        long withdrawn = total(evidence.withdrawn());
        long consumed = total(evidence.consumed());
        long returned = total(evidence.returned());
        boolean balanced = planned == withdrawn && withdrawn == consumed + returned
                && duplicateWithdrawals == 0 && duplicateReturns == 0 && unaccounted == 0;
        var materialReport = new PlayerMaterialSavedData.CompletionReport(
                planned, withdrawn, consumed, returned, 0, stored.outputCount(),
                stored.outputCount(), duplicateWithdrawals, duplicateReturns,
                unaccounted, 0, balanced);
        Entry reported = entry.withTransactions(entry.transactions(),
                Instant.now().toEpochMilli(), balanced ? "MATERIAL_LEDGER_BALANCED"
                        : "MATERIAL_LEDGER_RECONCILIATION_REQUIRED", materialReport);
        PlayerMaterialSavedData.forLevel(level).put(reported);
        IndustrialCompletionReportV1 report = IndustrialCompletionReportV1.create(
                entry.requirements(), evidence.withdrawn(), evidence.consumed(), evidence.returned(),
                Map.of(), Map.of(), Map.of(stored.output(), (long) stored.outputCount()), 0,
                duplicateWithdrawals, duplicateReturns, 0, 0, unaccounted, 0,
                baselineMatches(level, stored.baseline()), stored.baselineHash());
        if (!report.accepted(Map.of(), Map.of(stored.output(), (long) stored.outputCount()))) {
            pause(player, active.orderId, "MATERIAL_OR_OUTPUT_RECONCILIATION_REQUIRED");
            ACTIVE.remove(active.orderId);
            return;
        }
        checkpoint(player, active.orderId, IndustrialLifecyclePhase.DISMANTLING,
                Stage.REPORT_GENERATED, EFFECT_REPORT);
        var completed = IndustrialPlayerOrderService.completeWithReport(
                player, active.orderId, report);
        if (!completed.success()) throw new OrderFailure(completed.code());
        save(level);
        finishCompleted(level, stored);
        ACTIVE.remove(active.orderId);
    }

    private static void recoverMissing(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0) return;
        ServerLevel level = server.overworld();
        for (StoredOrder stored : AlloySmelterOrderSavedData.forLevel(level).orders().values()) {
            ServerPlayer player = server.getPlayerList().getPlayer(stored.playerId());
            if (player == null) continue;
            recoverStored(player, stored);
        }
    }

    /** Test/runtime-login bridge: a real player is already discoverable by the normal loop. */
    static boolean recoverForOwner(ServerPlayer player) {
        StoredOrder stored = AlloySmelterOrderSavedData.forLevel(player.serverLevel())
                .orders().values().stream().filter(value -> value.playerId().equals(player.getUUID()))
                .max(Comparator.comparing(value -> order(player.serverLevel(), value.orderId())
                        .updatedAt())).orElse(null);
        return stored != null && recoverStored(player, stored);
    }

    /** Warehouse recovery uses its durable project identity, never a latest-order guess. */
    public static boolean recoverForOrder(ServerPlayer actor, UUID orderId) {
        StoredOrder stored = AlloySmelterOrderSavedData.forLevel(actor.serverLevel())
                .order(orderId).orElse(null);
        return stored != null && stored.playerId().equals(actor.getUUID())
                && recoverStored(actor, stored);
    }

    /** Exact read-only envelope lookup for warehouse batch settlement. */
    public static Optional<IndustrialPlayerOrderV1> findOrder(
            ServerLevel level, UUID orderId) {
        return IndustrialPlayerOrderSavedData.forLevel(level).order(orderId);
    }

    private static boolean recoverStored(ServerPlayer player, StoredOrder stored) {
        ServerLevel level = player.serverLevel();
        IndustrialPlayerOrderV1 common = IndustrialPlayerOrderSavedData.forLevel(level)
                .order(stored.orderId()).orElse(null);
        if (common == null || !bindingMatches(common, stored)) return false;
        if (common.phase() == IndustrialLifecyclePhase.COMPLETED
                || common.phase() == IndustrialLifecyclePhase.RECOVERED) {
            finishCompleted(level, stored);
            return true;
        }
        if (common.phase() == IndustrialLifecyclePhase.CANCELLED
                || common.phase() == IndustrialLifecyclePhase.FAILED
                || ACTIVE.containsKey(stored.orderId())) return false;
        Entry entry = PlayerMaterialSavedData.forLevel(level)
                .entry(stored.orderId()).orElse(null);
        if (entry == null || entry.logistics() == null
                || !entry.logistics().staging().equals(stored.staging())) {
            pause(player, stored.orderId(), "MATERIAL_RECONCILIATION_REQUIRED");
            return false;
        }
        try {
            common = reconcileReload(player, stored, common, entry);
        } catch (RuntimeException failure) {
            pause(player, stored.orderId(), "RELOAD_RECONCILIATION_DIVERGED:"
                    + bounded(failure.getMessage()));
            return false;
        }
        if (common.phase() == IndustrialLifecyclePhase.PAUSED) return false;
        DeploymentBoundingBox recoveryBounds = bounds(stored.baseline().stream()
                .map(value -> block(value.position())).toList(), block(stored.materialSource()),
                player.blockPosition());
        PlayerConstructionService.MaterialCourier courier = null;
        Stage stage = Stage.valueOf(common.stage());
        if (stage == Stage.MATERIALS_RESERVED) {
            try {
                courier = PlayerConstructionService.MaterialCourier.resumeForOwner(level,
                        stored.orderId(), entry, stored.staging(), recoveryBounds, player);
            } catch (RuntimeException failure) {
                pause(player, stored.orderId(), "MATERIAL_COURIER_RECOVERY_DIVERGED");
                return false;
            }
        }
        ACTIVE.put(stored.orderId(), new Active(stored.orderId(), stored.playerId(),
                block(stored.machineOrigin()), block(stored.staging()), recoveryBounds, courier,
                null, player));
        return true;
    }

    private static IndustrialPlayerOrderV1 reconcileReload(
            ServerPlayer player,
            StoredOrder stored,
            IndustrialPlayerOrderV1 common,
            Entry entry) {
        ServerLevel level = player.serverLevel();
        Set<ResourceId> effects = common.durableEffects();
        if (effects.contains(EFFECT_RETURN) && !settled(entry)) {
            throw new OrderFailure("RETURN_LEDGER_DIVERGED");
        }
        if (effects.contains(EFFECT_BASELINE)
                && !baselineMatches(level, stored.baseline())) {
            throw new OrderFailure("BASELINE_DIVERGED");
        }
        if (effects.contains(EFFECT_OUTPUT)
                && taggedOutputCount(level, stored) != stored.outputCount()) {
            throw new OrderFailure("OUTPUT_DELIVERY_DIVERGED");
        }
        if (effects.contains(EFFECT_BATCH) && !effects.contains(EFFECT_BASELINE)) {
            var processing = ImmersiveEngineeringV1020AlloySmelterProduction
                    .observeReviewedProcessing(level, block(stored.machineOrigin()),
                            stack(stored.firstInput(), stored.firstInputCount()),
                            stack(stored.secondInput(), stored.secondInputCount()),
                            stack(stored.output(), stored.outputCount()));
            if (!processing.verified() && !effects.contains(EFFECT_OUTPUT)
                    && taggedOutputCount(level, stored) != stored.outputCount()) {
                throw new OrderFailure(processing.code());
            }
        }
        if (effects.contains(EFFECT_FORMATION) && !effects.contains(EFFECT_BASELINE)
                && !ImmersiveEngineeringV1020AlloySmelterProduction.multiblockFormed(
                        level, block(stored.machineOrigin()))) {
            throw new OrderFailure("FORMED_MULTIBLOCK_DIVERGED");
        }
        if (effects.contains(EFFECT_STRUCTURE) && !effects.contains(EFFECT_FORMATION)
                && !ImmersiveEngineeringV1020AlloySmelterProduction.rawStructurePresent(
                        level, block(stored.machineOrigin()))) {
            throw new OrderFailure("RAW_STRUCTURE_DIVERGED");
        }

        if (!effects.contains(EFFECT_WITHDRAWAL)
                && allTransactions(entry, MaterialTransactionState.DELIVERED)) {
            common = checkpoint(player, stored.orderId(), IndustrialLifecyclePhase.PLACED,
                    Stage.MATERIALS_DELIVERED, EFFECT_WITHDRAWAL);
            effects = common.durableEffects();
        }
        if (!effects.contains(EFFECT_STRUCTURE)
                && ImmersiveEngineeringV1020AlloySmelterProduction.rawStructurePresent(
                        level, block(stored.machineOrigin()))) {
            common = checkpoint(player, stored.orderId(), IndustrialLifecyclePhase.PLACED,
                    Stage.STRUCTURE_BUILT, EFFECT_STRUCTURE);
            effects = common.durableEffects();
        }
        if (!effects.contains(EFFECT_FORMATION)
                && ImmersiveEngineeringV1020AlloySmelterProduction.multiblockFormed(
                        level, block(stored.machineOrigin()))) {
            common = checkpoint(player, stored.orderId(), IndustrialLifecyclePhase.FORMED,
                    Stage.MULTIBLOCK_FORMED, EFFECT_FORMATION);
            effects = common.durableEffects();
        }
        if (!effects.contains(EFFECT_BATCH) && effects.contains(EFFECT_FORMATION)) {
            var admission = ImmersiveEngineeringV1020AlloySmelterProduction
                    .observeReviewedAdmission(level, block(stored.machineOrigin()),
                            stack(stored.firstInput(), stored.firstInputCount()),
                            stack(stored.secondInput(), stored.secondInputCount()),
                            stack(stored.fuel(), stored.fuelCount()));
            if (admission.verified()) {
                consumeBatch(level, stored);
                common = checkpoint(player, stored.orderId(), IndustrialLifecyclePhase.RUNNING,
                        Stage.PROCESSING, EFFECT_BATCH);
                effects = common.durableEffects();
            }
        }
        if (!effects.contains(EFFECT_OUTPUT)
                && taggedOutputCount(level, stored) == stored.outputCount()) {
            common = checkpoint(player, stored.orderId(), IndustrialLifecyclePhase.RUNNING,
                    Stage.OUTPUT_OBSERVED, EFFECT_OUTPUT);
            effects = common.durableEffects();
        }

        Stage stage;
        IndustrialLifecyclePhase phase;
        if (effects.contains(EFFECT_RETURN)) {
            stage = Stage.MATERIALS_RETURNED; phase = IndustrialLifecyclePhase.DISMANTLING;
        } else if (effects.contains(EFFECT_BASELINE)) {
            stage = Stage.BASELINE_RESTORED; phase = IndustrialLifecyclePhase.DISMANTLING;
        } else if (effects.contains(EFFECT_OUTPUT)) {
            stage = Stage.OUTPUT_OBSERVED; phase = IndustrialLifecyclePhase.RUNNING;
        } else if (effects.contains(EFFECT_BATCH)) {
            stage = Stage.PROCESSING; phase = IndustrialLifecyclePhase.RUNNING;
        } else if (effects.contains(EFFECT_FORMATION)) {
            stage = Stage.MULTIBLOCK_FORMED; phase = IndustrialLifecyclePhase.FORMED;
        } else if (effects.contains(EFFECT_STRUCTURE)) {
            stage = Stage.STRUCTURE_BUILT; phase = IndustrialLifecyclePhase.PLACED;
        } else if (effects.contains(EFFECT_WITHDRAWAL)) {
            stage = Stage.MATERIALS_DELIVERED; phase = IndustrialLifecyclePhase.PLACED;
        } else {
            stage = Stage.MATERIALS_RESERVED; phase = IndustrialLifecyclePhase.PLANNED;
        }
        return checkpoint(player, stored.orderId(), phase, stage, null);
    }

    public static StatusResult status(ServerPlayer player) {
        StoredOrder stored = AlloySmelterOrderSavedData.forLevel(player.serverLevel())
                .orders().values().stream().filter(value -> value.playerId().equals(player.getUUID()))
                .max(Comparator.comparing(value -> order(player.serverLevel(), value.orderId())
                        .updatedAt())).orElse(null);
        if (stored == null) return new StatusResult(false, "ORDER_NOT_FOUND", null, null);
        return new StatusResult(true, "OK", order(player.serverLevel(), stored.orderId()),
                PlayerMaterialSavedData.forLevel(player.serverLevel())
                        .entry(stored.orderId()).orElse(null));
    }

    public static StartResult cancel(ServerPlayer player) {
        StatusResult status = status(player);
        if (!status.success() || status.order() == null) {
            return StartResult.failure("ORDER_NOT_FOUND");
        }
        IndustrialPlayerOrderV1 common = status.order();
        if (terminal(common)) return StartResult.failure("ORDER_ALREADY_TERMINAL");
        if (common.durableEffects().contains(EFFECT_BATCH)
                || common.durableEffects().contains(EFFECT_OUTPUT)) {
            return StartResult.failure("ORDER_COMMITTED_CANNOT_CANCEL_AFTER_INPUT");
        }
        ServerLevel level = player.serverLevel();
        StoredOrder stored = AlloySmelterOrderSavedData.forLevel(level)
                .order(common.orderId()).orElseThrow();
        Active active = ACTIVE.remove(common.orderId());
        Entry entry = PlayerMaterialSavedData.forLevel(level)
                .entry(common.orderId()).orElse(null);
        if (entry == null) return StartResult.failure("MATERIAL_LEDGER_MISSING");
        PlayerConstructionService.MaterialCourier courier = active == null ? null : active.courier;
        boolean inFlight = entry.transactions().stream().anyMatch(value ->
                value.state() == MaterialTransactionState.WITHDRAWN);
        if (courier == null && inFlight) {
            try {
                courier = PlayerConstructionService.MaterialCourier.resumeForOwner(level,
                        common.orderId(), entry, stored.staging(), bounds(stored.baseline().stream()
                                .map(value -> block(value.position())).toList(),
                                block(stored.materialSource()), player.blockPosition()), player);
            } catch (RuntimeException failure) {
                pause(player, common.orderId(), "WITHDRAWN_MATERIAL_RECOVERY_REQUIRED");
                return StartResult.failure("WITHDRAWN_MATERIAL_RECOVERY_REQUIRED");
            }
        }
        if (courier != null && !courier.cancelAndReturn()) {
            pause(player, common.orderId(), "RETURN_PENDING_SOURCE_FULL_OR_CHANGED");
            return StartResult.failure("RETURN_PENDING_SOURCE_FULL_OR_CHANGED");
        }
        releasePreparedTransactions(level, common.orderId());
        if (entry.transactions().stream().anyMatch(value ->
                value.state() == MaterialTransactionState.DELIVERED
                        || value.state() == MaterialTransactionState.RETURN_PENDING)) {
            Active settlement = active != null ? active : new Active(common.orderId(),
                    stored.playerId(), block(stored.machineOrigin()), block(stored.staging()),
                    bounds(stored.baseline().stream().map(value -> block(value.position())).toList(),
                            block(stored.materialSource()), player.blockPosition()),
                    null, findBuilder(level, common.orderId()).map(Entity::getUUID).orElse(null), player);
            if (!returnMaterials(player, settlement, stored)) {
                pause(player, common.orderId(), "RETURN_PENDING_SOURCE_FULL_OR_CHANGED");
                return StartResult.failure("RETURN_PENDING_SOURCE_FULL_OR_CHANGED");
            }
        }
        ImmersiveEngineeringV1020AlloySmelterProduction.clear(
                level, block(stored.machineOrigin()));
        restoreBaseline(level, stored.baseline());
        if (!baselineMatches(level, stored.baseline())) {
            pause(player, common.orderId(), "BASELINE_RESTORE_MISMATCH");
            return StartResult.failure("BASELINE_RESTORE_MISMATCH");
        }
        discardOrderBots(level, common.orderId());
        IndustrialPlayerOrderService.cancel(player, common.orderId(), true);
        save(level);
        return new StartResult(true, "ORDER_CANCELLED", common.orderId(),
                order(level, common.orderId()), PlayerMaterialSavedData.forLevel(level)
                        .entry(common.orderId()).orElse(null));
    }

    public static void clearServerState() { ACTIVE.clear(); }

    private static boolean returnMaterials(
            ServerPlayer player, Active active, StoredOrder stored) {
        ServerLevel level = player.serverLevel();
        PlayerMaterialSavedData data = PlayerMaterialSavedData.forLevel(level);
        Entry entry = data.entry(stored.orderId()).orElse(null);
        if (entry == null) return returnFailure(stored, "LEDGER_MISSING");
        Map<UUID, Source> sources = new LinkedHashMap<>();
        entry.sources().forEach(value -> sources.put(value.sourceId(), value));
        Map<UUID, Transaction> transactions = new LinkedHashMap<>();
        entry.transactions().forEach(value -> transactions.put(value.reservationId(), value));
        Map<MaterialIdentity, Long> stagingAvailable = stagingCounts(level, stored.staging());
        boolean structurePlaced = order(level, stored.orderId()).durableEffects()
                .contains(EFFECT_STRUCTURE);
        long syntheticBricks = structurePlaced ? STRUCTURE_BLOCKS : 0;
        ItemStack carriedHammer = builderHammer(level, active);

        for (Reservation reservation : entry.reservations()) {
            Transaction transaction = transactions.get(reservation.reservationId());
            if (transaction == null) return returnFailure(stored,
                    "TRANSACTION_MISSING:" + reservation.reservationId());
            if (transaction.state() == MaterialTransactionState.CONSUMED
                    || transaction.state() == MaterialTransactionState.RETURNED
                    || transaction.state() == MaterialTransactionState.RELEASED) continue;
            if (transaction.state() != MaterialTransactionState.DELIVERED
                    && transaction.state() != MaterialTransactionState.RETURN_PENDING) {
                return returnFailure(stored, "TRANSACTION_STATE:" + transaction.state()
                        + ":" + reservation.identity().itemId());
            }
            Source source = sources.get(reservation.sourceId());
            if (source == null || !(level.getBlockEntity(block(source.position()))
                    instanceof Container destination)) {
                return returnFailure(stored, "SOURCE_MISSING:" + reservation.identity().itemId());
            }
            PlayerMaterialSavedData.Slot original = source.slots().stream()
                    .filter(value -> value.slot() == reservation.slot()
                            && value.identity().equals(reservation.identity())).findFirst().orElse(null);
            if (original == null || original.quantity() < reservation.quantity()) {
                return returnFailure(stored, "SOURCE_SNAPSHOT_MISMATCH:"
                        + reservation.identity().itemId());
            }
            ItemStack current = destination.getItem(reservation.slot());
            long afterWithdrawal = original.quantity() - reservation.quantity();
            boolean alreadyReturned = current.getCount() == original.quantity()
                    && (reservation.identity().itemId().equals(HAMMER)
                            ? !current.isEmpty() && itemId(current).equals(HAMMER)
                            : current.isEmpty() ? original.quantity() == 0
                                    : identity(current).equals(reservation.identity()));
            if (alreadyReturned) continue;
            if (current.getCount() != afterWithdrawal
                    || (!current.isEmpty() && !identity(current).equals(reservation.identity()))) {
                return returnFailure(stored, "SOURCE_SLOT_CHANGED:"
                        + reservation.identity().itemId() + ":slot=" + reservation.slot()
                        + ":expected=" + afterWithdrawal + ":actual=" + current.getCount());
            }
            long available = stagingAvailable.getOrDefault(reservation.identity(), 0L);
            if (available >= reservation.quantity()) {
                stagingAvailable.put(reservation.identity(), available - reservation.quantity());
            } else if (reservation.identity().itemId().equals(ALLOY_BRICK)
                    && syntheticBricks >= reservation.quantity()) {
                syntheticBricks -= reservation.quantity();
            } else if (reservation.identity().itemId().equals(HAMMER)
                    && !carriedHammer.isEmpty() && carriedHammer.getCount() == reservation.quantity()) {
                carriedHammer = ItemStack.EMPTY;
            } else {
                return returnFailure(stored, "RETURN_MATERIAL_UNAVAILABLE:"
                        + reservation.identity().itemId() + ":required="
                        + reservation.quantity() + ":staging=" + available
                        + ":syntheticBricks=" + syntheticBricks + ":hammer="
                        + (carriedHammer.isEmpty() ? "empty" : carriedHammer.getCount()));
            }
        }

        Entry currentEntry = entry;
        for (Reservation reservation : entry.reservations()) {
            Transaction transaction = currentEntry.transactions().stream().filter(value ->
                    value.reservationId().equals(reservation.reservationId())).findFirst().orElse(null);
            if (transaction == null || transaction.state() == MaterialTransactionState.CONSUMED
                    || transaction.state() == MaterialTransactionState.RETURNED
                    || transaction.state() == MaterialTransactionState.RELEASED) continue;
            Source source = sources.get(reservation.sourceId());
            Container destination = (Container) level.getBlockEntity(block(source.position()));
            PlayerMaterialSavedData.Slot original = source.slots().stream()
                    .filter(value -> value.slot() == reservation.slot()
                            && value.identity().equals(reservation.identity())).findFirst().orElseThrow();
            ItemStack existing = destination.getItem(reservation.slot());
            boolean alreadyReturned = existing.getCount() == original.quantity()
                    && (reservation.identity().itemId().equals(HAMMER)
                            ? !existing.isEmpty() && itemId(existing).equals(HAMMER)
                            : !existing.isEmpty() && identity(existing).equals(reservation.identity()));
            if (transaction.state() == MaterialTransactionState.DELIVERED) {
                currentEntry = PlayerConstructionService.advanceTransaction(currentEntry,
                        reservation.reservationId(), MaterialTransactionState.RETURN_PENDING,
                        level.getGameTime(), "IE_ALLOY_ORDER_RETURN_PREPARED");
                data.put(currentEntry);
                save(level);
            }
            if (!alreadyReturned) {
                ItemStack returned = takeReturnStack(level, active, stored, reservation);
                if (returned.isEmpty() || returned.getCount() != reservation.quantity()) {
                    return returnFailure(stored, "RETURN_EXTRACTION_FAILED:"
                            + reservation.identity().itemId() + ":required="
                            + reservation.quantity() + ":actual=" + returned.getCount());
                }
                if (existing.isEmpty()) destination.setItem(reservation.slot(), returned);
                else existing.grow(returned.getCount());
                destination.setChanged();
            }
            currentEntry = PlayerConstructionService.advanceTransaction(currentEntry,
                    reservation.reservationId(), MaterialTransactionState.RETURNED,
                    level.getGameTime(), "IE_ALLOY_ORDER_MATERIAL_RETURNED");
            data.put(currentEntry);
            save(level);
        }
        return true;
    }

    private static boolean returnFailure(StoredOrder stored, String code) {
        LOGGER.warn("IE_ALLOY_RETURN_REFUSED order={} code={}", stored.orderId(), code);
        return false;
    }

    private static ItemStack takeReturnStack(
            ServerLevel level, Active active, StoredOrder stored, Reservation reservation) {
        if (level.getBlockEntity(block(stored.staging())) instanceof Container staging
                && count(staging, reservation.identity()) >= reservation.quantity()) {
            return removeExact(staging, reservation.identity(), reservation.quantity());
        }
        IndustrialPlayerOrderV1 common = order(level, stored.orderId());
        if (reservation.identity().itemId().equals(ALLOY_BRICK)
                && common.durableEffects().contains(EFFECT_STRUCTURE)) {
            return stack(ALLOY_BRICK, Math.toIntExact(reservation.quantity()));
        }
        if (reservation.identity().itemId().equals(HAMMER)) {
            ConstructionBotEntity builder = active.builderId == null ? null
                    : level.getEntity(active.builderId) instanceof ConstructionBotEntity value
                            ? value : null;
            ItemStack hammer = builder == null ? ItemStack.EMPTY
                    : builder.getItemBySlot(EquipmentSlot.OFFHAND);
            if (hammer.isEmpty() || !itemId(hammer).equals(HAMMER)) {
                builder = findBuilderHolding(level, stored.orderId(), HAMMER).orElse(null);
                hammer = builder == null ? ItemStack.EMPTY
                        : builder.getItemBySlot(EquipmentSlot.OFFHAND);
            }
            if (builder == null || hammer.isEmpty() || !itemId(hammer).equals(HAMMER)
                    || hammer.getCount() != reservation.quantity()) return ItemStack.EMPTY;
            builder.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            return hammer.copy();
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack builderHammer(ServerLevel level, Active active) {
        ConstructionBotEntity builder = active.builderId == null ? null
                : level.getEntity(active.builderId) instanceof ConstructionBotEntity value
                        ? value : null;
        ItemStack hammer = builder == null ? ItemStack.EMPTY
                : builder.getItemBySlot(EquipmentSlot.OFFHAND);
        if (hammer.isEmpty() || !itemId(hammer).equals(HAMMER)) {
            builder = findBuilderHolding(level, active.orderId, HAMMER).orElse(null);
            hammer = builder == null ? ItemStack.EMPTY
                    : builder.getItemBySlot(EquipmentSlot.OFFHAND);
        }
        return hammer.isEmpty() ? ItemStack.EMPTY : hammer.copy();
    }

    private static void consumeBatch(ServerLevel level, StoredOrder stored) {
        consumeItem(level, stored.orderId(), stored.firstInput());
        consumeItem(level, stored.orderId(), stored.secondInput());
        consumeItem(level, stored.orderId(), stored.fuel());
    }

    private static void consumeItem(ServerLevel level, UUID projectId, ResourceId resource) {
        PlayerMaterialSavedData data = PlayerMaterialSavedData.forLevel(level);
        Entry current = data.entry(projectId).orElseThrow();
        for (Reservation reservation : current.reservations().stream().filter(value ->
                value.identity().itemId().equals(resource)).toList()) {
            Transaction transaction = current.transactions().stream().filter(value ->
                    value.reservationId().equals(reservation.reservationId())).findFirst().orElseThrow();
            if (transaction.state() == MaterialTransactionState.CONSUMED) continue;
            if (transaction.state() != MaterialTransactionState.DELIVERED) {
                throw new OrderFailure("MATERIAL_NOT_DELIVERED_BEFORE_CONSUMPTION:" + resource);
            }
            current = PlayerConstructionService.advanceTransaction(current,
                    reservation.reservationId(), MaterialTransactionState.CONSUMED,
                    level.getGameTime(), "IE_ALLOY_ORDER_MATERIAL_CONSUMED");
            data.put(current);
            save(level);
        }
    }

    private static IndustrialPlayerOrderV1 checkpoint(
            ServerPlayer player,
            UUID orderId,
            IndustrialLifecyclePhase phase,
            Stage stage,
            ResourceId effect) {
        IndustrialPlayerOrderV1 current = order(player.serverLevel(), orderId);
        LinkedHashSet<ResourceId> effects = new LinkedHashSet<>(current.durableEffects());
        if (effect != null) effects.add(effect);
        var result = IndustrialPlayerOrderService.checkpoint(
                player, orderId, phase, stage.name(), Set.copyOf(effects));
        if (!result.success()) throw new OrderFailure(result.code());
        save(player.serverLevel());
        return order(player.serverLevel(), orderId);
    }

    private static void pause(ServerPlayer player, UUID orderId, String code) {
        IndustrialPlayerOrderV1 current = IndustrialPlayerOrderSavedData.forLevel(
                player.serverLevel()).order(orderId).orElse(null);
        if (current == null || terminal(current)) return;
        String stage = "PAUSED:" + bounded(code);
        IndustrialPlayerOrderService.checkpoint(player, orderId,
                IndustrialLifecyclePhase.PAUSED, stage, current.durableEffects());
        save(player.serverLevel());
    }

    private static IndustrialPlayerOrderV1 order(ServerLevel level, UUID orderId) {
        return IndustrialPlayerOrderSavedData.forLevel(level).order(orderId).orElseThrow();
    }

    private static boolean bindingMatches(IndustrialPlayerOrderV1 common, StoredOrder stored) {
        return common.orderId().equals(stored.orderId())
                && common.projectId().equals(stored.orderId())
                && common.ownerId().equals(stored.playerId())
                && common.dimension().equals(stored.dimension())
                && common.orderType().equals(ORDER_TYPE)
                && common.target().equals(stored.output())
                && common.recipeId().equals(stored.recipeId())
                && common.anchor().equals(stored.machineOrigin())
                && common.planHash().equals(stored.planHash())
                && common.runtimeFingerprint().equals(stored.runtimeFingerprint())
                && common.baselineHash().equals(stored.baselineHash());
    }

    private static boolean terminal(ServerLevel level, UUID orderId) {
        return IndustrialPlayerOrderSavedData.forLevel(level).order(orderId)
                .map(AlloySmelterProductionService::terminal).orElse(false);
    }

    private static boolean terminal(IndustrialPlayerOrderV1 order) {
        return order.phase() == IndustrialLifecyclePhase.COMPLETED
                || order.phase() == IndustrialLifecyclePhase.RECOVERED
                || order.phase() == IndustrialLifecyclePhase.CANCELLED
                || order.phase() == IndustrialLifecyclePhase.FAILED;
    }

    private static void deliverTaggedOutput(
            ServerLevel level, StoredOrder stored, ItemStack output) {
        if (taggedOutputCount(level, stored) != 0) {
            throw new OrderFailure("DUPLICATE_OUTPUT_DELIVERY_REFUSED");
        }
        CompoundTag tag = output.getOrCreateTag();
        if (tag.contains(OUTPUT_STACK_ORDER)) {
            throw new OrderFailure("OUTPUT_ALREADY_ORDER_TAGGED");
        }
        tag.putUUID(OUTPUT_STACK_ORDER, stored.orderId());
        if (stored.warehouseBinding().isPresent()) {
            WarehouseBinding binding = stored.warehouseBinding().orElseThrow();
            BlockPos destinationPosition = block(binding.outputDestination());
            if (!(level.getBlockEntity(destinationPosition) instanceof Container destination)) {
                throw new OrderFailure("WAREHOUSE_OUTPUT_DESTINATION_CHANGED");
            }
            Set<Integer> reserved = PlayerMaterialSavedData.forLevel(level)
                    .entry(stored.orderId()).stream().flatMap(value -> value.reservations().stream())
                    .filter(value -> value.sourceId().equals(sourceIdAt(level, stored,
                            binding.outputDestination())))
                    .map(Reservation::slot).collect(java.util.stream.Collectors.toSet());
            for (int slot = 0; slot < destination.getContainerSize(); slot++) {
                if (reserved.contains(slot) || !destination.getItem(slot).isEmpty()) continue;
                destination.setItem(slot, output);
                destination.setChanged();
                return;
            }
            throw new OrderFailure("WAREHOUSE_OUTPUT_DESTINATION_FULL");
        }
        BlockPos source = block(stored.materialSource());
        ItemEntity entity = new ItemEntity(level, source.getX() + 0.5D,
                source.getY() + 1.25D, source.getZ() + 0.5D, output);
        entity.setNeverPickUp();
        entity.setUnlimitedLifetime();
        entity.addTag(ORDER_TAG + stored.orderId().toString().replace("-", ""));
        if (!level.addFreshEntity(entity)) throw new OrderFailure("OUTPUT_DELIVERY_FAILED");
    }

    private static long taggedOutputCount(ServerLevel level, StoredOrder stored) {
        long count = 0;
        String entityTag = ORDER_TAG + stored.orderId().toString().replace("-", "");
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof ItemEntity item) || !entity.isAlive()
                    || !entity.getTags().contains(entityTag)) continue;
            ItemStack stack = item.getItem();
            if (!itemId(stack).equals(stored.output()) || stack.getTag() == null
                    || !stack.getTag().hasUUID(OUTPUT_STACK_ORDER)
                    || !stack.getTag().getUUID(OUTPUT_STACK_ORDER).equals(stored.orderId())) {
                throw new OrderFailure("TAGGED_OUTPUT_IDENTITY_DIVERGED");
            }
            count = Math.addExact(count, stack.getCount());
        }
        if (stored.warehouseBinding().isPresent()) {
            BlockPos destination = block(stored.warehouseBinding().orElseThrow()
                    .outputDestination());
            if (!(level.getBlockEntity(destination) instanceof Container container)) {
                throw new OrderFailure("WAREHOUSE_OUTPUT_DESTINATION_CHANGED");
            }
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                CompoundTag tag = stack.getTag();
                if (tag == null || !tag.hasUUID(OUTPUT_STACK_ORDER)
                        || !tag.getUUID(OUTPUT_STACK_ORDER).equals(stored.orderId())) continue;
                if (!itemId(stack).equals(stored.output())) {
                    throw new OrderFailure("TAGGED_OUTPUT_IDENTITY_DIVERGED");
                }
                count = Math.addExact(count, stack.getCount());
            }
        }
        return count;
    }

    private static void finishCompleted(ServerLevel level, StoredOrder stored) {
        String entityTag = ORDER_TAG + stored.orderId().toString().replace("-", "");
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof ItemEntity item) || !entity.getTags().contains(entityTag)) continue;
            ItemStack stack = item.getItem();
            CompoundTag tag = stack.getTag();
            if (tag != null && tag.hasUUID(OUTPUT_STACK_ORDER)
                    && tag.getUUID(OUTPUT_STACK_ORDER).equals(stored.orderId())) {
                tag.remove(OUTPUT_STACK_ORDER);
                if (tag.isEmpty()) stack.setTag(null);
                item.setItem(stack);
                item.setDefaultPickUpDelay();
                item.setExtendedLifetime();
                entity.removeTag(entityTag);
            }
        }
        stored.warehouseBinding().ifPresent(binding -> {
            if (!(level.getBlockEntity(block(binding.outputDestination()))
                    instanceof Container container)) return;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                CompoundTag tag = stack.getTag();
                if (tag == null || !tag.hasUUID(OUTPUT_STACK_ORDER)
                        || !tag.getUUID(OUTPUT_STACK_ORDER).equals(stored.orderId())) continue;
                tag.remove(OUTPUT_STACK_ORDER);
                if (tag.isEmpty()) stack.setTag(null);
                container.setItem(slot, stack);
            }
            container.setChanged();
        });
        discardOrderBots(level, stored.orderId());
    }

    private static UUID sourceIdAt(
            ServerLevel level, StoredOrder stored, BlockPos3i position) {
        return PlayerMaterialSavedData.forLevel(level).entry(stored.orderId()).orElseThrow()
                .sources().stream().filter(value -> value.position().equals(position))
                .map(Source::sourceId).findFirst()
                .orElseThrow(() -> new OrderFailure("WAREHOUSE_OUTPUT_SOURCE_BINDING_MISSING"));
    }

    private static ConstructionBotEntity ensureBuilder(
            ServerLevel level, Active active, ServerPlayer player) {
        if (active.builderId != null && level.getEntity(active.builderId)
                instanceof ConstructionBotEntity bot && bot.isAlive()) return bot;
        ConstructionBotEntity existing = findBuilder(level, active.orderId).orElse(null);
        if (existing != null) {
            active.builderId = existing.getUUID();
            return existing;
        }
        ConstructionBotEntity bot = Objects.requireNonNull(
                ConstructionBotEntities.CONSTRUCTION_BOT.get().create(level));
        bot.setRole(ConstructionBotEntity.Role.BUILDER_INSPECTOR);
        bot.moveTo(player.getX(), player.getY(), player.getZ(), 0, 0);
        bot.setCustomName(Component.literal("Steve · IE Alloy Smelter Builder"));
        bot.setCustomNameVisible(true);
        bot.addTag(BUILDER_TAG);
        bot.addTag(ORDER_TAG + active.orderId.toString().replace("-", ""));
        if (!level.addFreshEntity(bot)) throw new OrderFailure("BUILDER_BOT_SPAWN_FAILED");
        active.builderId = bot.getUUID();
        return bot;
    }

    private static java.util.Optional<ConstructionBotEntity> findBuilder(
            ServerLevel level, UUID orderId) {
        String tag = ORDER_TAG + orderId.toString().replace("-", "");
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof ConstructionBotEntity bot && bot.isAlive()
                    && bot.getTags().contains(BUILDER_TAG) && bot.getTags().contains(tag)) {
                return java.util.Optional.of(bot);
            }
        }
        return java.util.Optional.empty();
    }

    /** Finds the actual leased-tool carrier when recovery briefly created an empty duplicate. */
    private static java.util.Optional<ConstructionBotEntity> findBuilderHolding(
            ServerLevel level, UUID orderId, ResourceId resource) {
        String tag = ORDER_TAG + orderId.toString().replace("-", "");
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof ConstructionBotEntity bot) || !bot.isAlive()
                    || !bot.getTags().contains(BUILDER_TAG) || !bot.getTags().contains(tag)) {
                continue;
            }
            ItemStack held = bot.getItemBySlot(EquipmentSlot.OFFHAND);
            if (!held.isEmpty() && itemId(held).equals(resource)) return Optional.of(bot);
        }
        return Optional.empty();
    }

    private static void discardOrderBots(ServerLevel level, UUID orderId) {
        String tag = ORDER_TAG + orderId.toString().replace("-", "");
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof ConstructionBotEntity && entity.getTags().contains(tag)) {
                entity.discard();
            }
        }
    }

    private static BlockPos workCell(ServerLevel level, BlockPos origin) {
        for (int radius = 2; radius <= 5; radius++) {
            for (BlockPos candidate : List.of(origin.offset(radius, 0, 0),
                    origin.offset(-radius, 0, 0), origin.offset(0, 0, radius),
                    origin.offset(0, 0, -radius))) {
                if (level.getBlockState(candidate).isAir()
                        && level.getBlockState(candidate.above()).isAir()) return candidate;
            }
        }
        throw new OrderFailure("BUILDER_WORK_CELL_UNAVAILABLE");
    }

    private static boolean at(ConstructionBotEntity bot, BlockPos target) {
        double dx = bot.getX() - (target.getX() + 0.5D);
        double dy = bot.getY() - target.getY();
        double dz = bot.getZ() - (target.getZ() + 0.5D);
        return dx * dx + dy * dy + dz * dz < 1.0E-6D;
    }

    private static boolean placeStaging(ServerLevel level, BlockPos position) {
        return level.getBlockState(position).isAir()
                && level.setBlockAndUpdate(position, Blocks.CHEST.defaultBlockState())
                && level.getBlockEntity(position) instanceof ChestBlockEntity;
    }

    private static ItemStack removeFromStaging(
            ServerLevel level, BlockPos staging, ResourceId resource, int quantity) {
        if (!(level.getBlockEntity(staging) instanceof Container container)) {
            throw new OrderFailure("STAGING_CONTAINER_MISSING");
        }
        MaterialIdentity expected = new MaterialIdentity(
                resource, MaterialIdentity.EMPTY_PAYLOAD_SHA256);
        ItemStack removed = removeExact(container, expected, quantity);
        if (removed.isEmpty() || removed.getCount() != quantity) {
            throw new OrderFailure("STAGING_MATERIAL_MISSING:" + resource);
        }
        return removed;
    }

    private static ItemStack removeExact(
            Container container, MaterialIdentity expected, long quantity) {
        if (quantity < 1 || quantity > Integer.MAX_VALUE) return ItemStack.EMPTY;
        ItemStack result = stack(expected.itemId(), Math.toIntExact(quantity));
        int remaining = Math.toIntExact(quantity);
        for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
            ItemStack current = container.getItem(slot);
            if (!current.isEmpty() && identity(current).equals(expected)) {
                int taken = Math.min(remaining, current.getCount());
                container.removeItem(slot, taken);
                remaining -= taken;
            }
        }
        container.setChanged();
        return remaining == 0 ? result : ItemStack.EMPTY;
    }

    private static boolean restoreRecoveredToStaging(
            ServerLevel level, BlockPos staging, List<ItemStack> recovered) {
        if (!(level.getBlockEntity(staging) instanceof Container container)) return false;
        for (ItemStack stack : recovered) {
            if (!insert(container, stack.copy())) return false;
        }
        return true;
    }

    private static boolean insert(Container container, ItemStack stack) {
        for (int slot = 0; slot < container.getContainerSize() && !stack.isEmpty(); slot++) {
            ItemStack current = container.getItem(slot);
            if (!current.isEmpty() && ItemStack.isSameItemSameTags(current, stack)) {
                int moved = Math.min(stack.getCount(), current.getMaxStackSize() - current.getCount());
                if (moved > 0) { current.grow(moved); stack.shrink(moved); }
            }
        }
        for (int slot = 0; slot < container.getContainerSize() && !stack.isEmpty(); slot++) {
            if (container.getItem(slot).isEmpty()) {
                int moved = Math.min(stack.getCount(), stack.getMaxStackSize());
                ItemStack placed = stack.copy(); placed.setCount(moved);
                container.setItem(slot, placed); stack.shrink(moved);
            }
        }
        container.setChanged();
        return stack.isEmpty();
    }

    private static Map<MaterialIdentity, Long> stagingCounts(
            ServerLevel level, BlockPos3i staging) {
        LinkedHashMap<MaterialIdentity, Long> result = new LinkedHashMap<>();
        if (level.getBlockEntity(block(staging)) instanceof Container container) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty()) result.merge(identity(stack), (long) stack.getCount(),
                        Math::addExact);
            }
        }
        return result;
    }

    private static long count(Container container, MaterialIdentity identity) {
        long total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty() && identity(stack).equals(identity)) total += stack.getCount();
        }
        return total;
    }

    private static void releasePreparedTransactions(ServerLevel level, UUID projectId) {
        PlayerMaterialSavedData data = PlayerMaterialSavedData.forLevel(level);
        Entry current = data.entry(projectId).orElse(null);
        if (current == null) return;
        for (Transaction transaction : List.copyOf(current.transactions())) {
            if (transaction.state() != MaterialTransactionState.PREPARED) continue;
            current = PlayerConstructionService.advanceTransaction(current,
                    transaction.reservationId(), MaterialTransactionState.RELEASED,
                    level.getGameTime(), "IE_ALLOY_ORDER_RESERVATION_RELEASED");
            data.put(current);
        }
        save(level);
    }

    private static boolean settled(Entry entry) {
        return !entry.transactions().isEmpty() && entry.transactions().stream().allMatch(value ->
                value.state() == MaterialTransactionState.CONSUMED
                        || value.state() == MaterialTransactionState.RETURNED
                        || value.state() == MaterialTransactionState.RELEASED);
    }

    private static boolean allTransactions(Entry entry, MaterialTransactionState state) {
        return !entry.transactions().isEmpty()
                && entry.transactions().stream().allMatch(value -> value.state() == state);
    }

    private static long duplicates(Entry entry, MaterialTransactionState state) {
        return entry.journal().stream().filter(value -> value.state() == state)
                .collect(java.util.stream.Collectors.groupingBy(
                        PlayerMaterialSavedData.JournalEvent::transactionId,
                        java.util.stream.Collectors.counting()))
                .values().stream().mapToLong(value -> Math.max(0, value - 1)).sum();
    }

    private static long unaccounted(
            Map<ResourceId, Long> planned, MaterialLedgerProjection.Evidence evidence) {
        TreeSet<ResourceId> resources = new TreeSet<>(Comparator.comparing(ResourceId::toString));
        resources.addAll(planned.keySet());
        resources.addAll(evidence.withdrawn().keySet());
        resources.addAll(evidence.consumed().keySet());
        resources.addAll(evidence.returned().keySet());
        long total = 0;
        for (ResourceId resource : resources) {
            long expected = planned.getOrDefault(resource, 0L);
            long withdrawn = evidence.withdrawn().getOrDefault(resource, 0L);
            long settled = evidence.consumed().getOrDefault(resource, 0L)
                    + evidence.returned().getOrDefault(resource, 0L);
            total = Math.addExact(total, Math.abs(expected - withdrawn));
            total = Math.addExact(total, Math.abs(withdrawn - settled));
        }
        return total;
    }

    private static long total(Map<ResourceId, Long> values) {
        return values.values().stream().mapToLong(Long::longValue).sum();
    }

    private static void restoreBaseline(ServerLevel level, List<BaselineBlock> baseline) {
        HolderLookup<net.minecraft.world.level.block.Block> lookup = level.holderLookup(Registries.BLOCK);
        for (BaselineBlock row : baseline) {
            level.setBlockAndUpdate(block(row.position()),
                    NbtUtils.readBlockState(lookup, row.serializedState()));
        }
    }

    private static boolean baselineMatches(ServerLevel level, List<BaselineBlock> baseline) {
        HolderLookup<net.minecraft.world.level.block.Block> lookup = level.holderLookup(Registries.BLOCK);
        return baseline.stream().allMatch(row -> level.getBlockState(block(row.position()))
                .equals(NbtUtils.readBlockState(lookup, row.serializedState())));
    }

    private static String withMissingDetail(
            PlayerMaterialService.SelectionResult reserved,
            PlayerMaterialService.SelectionResult selected) {
        StringBuilder missing = new StringBuilder(reserved.statusCode());
        PlayerMaterialService.Summary summary = selected.summary();
        if (summary != null) summary.required().forEach((item, quantity) -> {
            long available = summary.available().getOrDefault(item, 0L);
            if (available < quantity) missing.append('|').append(item).append(':')
                    .append(available).append('/').append(quantity);
        });
        return missing.toString();
    }

    private static DeploymentBoundingBox bounds(
            List<BlockPos> positions, BlockPos source, BlockPos player) {
        ArrayList<BlockPos3i> values = new ArrayList<>();
        positions.forEach(value -> values.add(pos(value)));
        values.add(pos(source)); values.add(pos(player));
        return DeploymentBoundingBox.enclosing(values);
    }

    private static String planHash(
            String runtime,
            ResourceLocation recipe,
            Map<ResourceId, Long> requirements,
            ResourceId first,
            int firstCount,
            ResourceId second,
            int secondCount,
            ResourceId output,
            int outputCount) {
        StringBuilder value = new StringBuilder(recipe.toString()).append('\n').append(runtime)
                .append('\n').append(first).append('=').append(firstCount)
                .append('\n').append(second).append('=').append(secondCount)
                .append('\n').append("minecraft:coal=1")
                .append('\n').append(output).append('=').append(outputCount);
        requirements.entrySet().stream().sorted(Map.Entry.comparingByKey(
                Comparator.comparing(ResourceId::toString))).forEach(row -> value.append('\n')
                .append("require:").append(row.getKey()).append('=').append(row.getValue()));
        return sha256(value.toString());
    }

    private static String baselineHash(List<BaselineBlock> baseline) {
        StringBuilder value = new StringBuilder();
        baseline.forEach(row -> value.append(row.position()).append('=')
                .append(row.serializedState()).append('\n'));
        return sha256(value.toString());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ItemStack stack(ResourceId resource, int quantity) {
        return new ItemStack(item(resource), quantity);
    }

    private static Item item(ResourceId resource) {
        Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(resource.toString()));
        if (item == null || item == Items.AIR) throw new OrderFailure("ITEM_UNAVAILABLE:" + resource);
        return item;
    }

    private static ResourceId itemId(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) throw new OrderFailure("UNREGISTERED_ITEM");
        return ResourceId.parse(id.toString());
    }

    private static MaterialIdentity identity(ItemStack stack) {
        return new MaterialIdentity(itemId(stack), PlayerMaterialService.canonicalPayload(stack));
    }

    private static Comparator<BlockPos> positionOrder() {
        return Comparator.comparingInt((BlockPos value) -> value.getX())
                .thenComparingInt(value -> value.getY())
                .thenComparingInt(value -> value.getZ());
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) return "NO_DETAIL";
        String cleaned = value.replaceAll("[^A-Za-z0-9 _:\\-]", "_").replace(' ', '_');
        return cleaned.length() <= 160 ? cleaned : cleaned.substring(0, 160);
    }

    private static void save(ServerLevel level) {
        level.getServer().overworld().getDataStorage().save();
    }

    private static ResourceId effect(String value) {
        return id("steve_industrial:alloy_" + value);
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
    private static BlockPos3i pos(BlockPos value) {
        return new BlockPos3i(value.getX(), value.getY(), value.getZ());
    }
    private static BlockPos block(BlockPos3i value) {
        return new BlockPos(value.x(), value.y(), value.z());
    }

    public record StartResult(
            boolean success,
            String code,
            UUID orderId,
            IndustrialPlayerOrderV1 order,
            Entry material) {
        static StartResult failure(String code) {
            return new StartResult(false, code, null, null, null);
        }
    }

    public record StatusResult(
            boolean success, String code, IndustrialPlayerOrderV1 order, Entry material) {}

    public record RequirementResult(
            boolean success,
            String code,
            Map<ResourceId, Long> requirements,
            ResourceId output,
            int outputCount,
            ResourceId recipeId,
            String runtimeFingerprint) {
        public RequirementResult {
            Objects.requireNonNull(code, "code");
            requirements = Map.copyOf(Objects.requireNonNull(requirements, "requirements"));
            if (success) {
                Objects.requireNonNull(output, "output");
                Objects.requireNonNull(recipeId, "recipeId");
                if (requirements.isEmpty() || outputCount < 1
                        || runtimeFingerprint == null || runtimeFingerprint.isBlank()) {
                    throw new IllegalArgumentException("successful Alloy requirement is invalid");
                }
            }
        }

        static RequirementResult failure(String code) {
            return new RequirementResult(false, code, Map.of(), null, 0, null, "");
        }
    }

    private enum Stage {
        MATERIALS_RESERVED,
        MATERIALS_DELIVERED,
        STRUCTURE_BUILT,
        MULTIBLOCK_FORMED,
        PROCESSING,
        OUTPUT_OBSERVED,
        TEARDOWN,
        BASELINE_RESTORED,
        MATERIALS_RETURNED,
        REPORT_GENERATED,
        COMPLETED,
        PAUSED,
        CANCELLED
    }

    private static final class Active {
        private final UUID orderId;
        private final UUID playerId;
        private final BlockPos origin;
        private final BlockPos staging;
        private final DeploymentBoundingBox bounds;
        private PlayerConstructionService.MaterialCourier courier;
        private UUID builderId;
        private final ServerPlayer livePlayer;

        private Active(
                UUID orderId,
                UUID playerId,
                BlockPos origin,
                BlockPos staging,
                DeploymentBoundingBox bounds,
                PlayerConstructionService.MaterialCourier courier,
                UUID builderId,
                ServerPlayer livePlayer) {
            this.orderId = orderId;
            this.playerId = playerId;
            this.origin = origin;
            this.staging = staging;
            this.bounds = bounds;
            this.courier = courier;
            this.builderId = builderId;
            this.livePlayer = livePlayer;
        }
    }

    private static final class OrderFailure extends RuntimeException {
        private OrderFailure(String code) { super(code); }
    }
}
